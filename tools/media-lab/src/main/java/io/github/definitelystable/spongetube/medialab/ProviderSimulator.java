package io.github.definitelystable.spongetube.medialab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Deterministic provider-plane state machine for the N8/N9/N10 scenario
 * families. It owns the per-session media fault counter, the binding generation
 * and the normalized fault decisions. It performs no request I/O and never
 * talks to a live provider.
 */
final class ProviderSimulator {

    /** Fixed virtual provider wall clock (2026-09-25T12:00:00Z) used when no epoch is configured. */
    static final long DEFAULT_PROVIDER_WALL_CLOCK_EPOCH_MS = 1_790_337_600_000L;

    static final long RETRY_AFTER_DELAY_SECONDS = 2L;
    static final long RETRY_AFTER_HTTP_DATE_OFFSET_MS = 2_000L;

    private static final Pattern GENERATION = Pattern.compile("gen-[1-9][0-9]*");
    private static final Pattern BINDING_REVISION = Pattern.compile("binding-[1-9][0-9]*");

    private final ProviderVariant variant;
    private final long providerWallClockEpochMs;
    private final NavigableMap<String, Long> catalogLengths;
    private final AtomicInteger mediaGetCount = new AtomicInteger();
    private final AtomicInteger currentGeneration = new AtomicInteger(1);
    private final AtomicBoolean bindingRefreshed = new AtomicBoolean();

    ProviderSimulator(
            ProviderVariant variant,
            long providerWallClockEpochMs,
            Map<String, Long> catalogLengths) {
        this.variant = Objects.requireNonNull(variant, "variant");
        this.providerWallClockEpochMs = providerWallClockEpochMs;
        this.catalogLengths = new TreeMap<>(Objects.requireNonNull(catalogLengths, "catalogLengths"));
    }

    /**
     * Decision for one provider media GET; {@code faultEvent} is non-null
     * exactly when a fault is injected. HEAD is served without faults and never
     * reaches this decision.
     */
    record MediaDecision(
            ProviderFaultEvent faultEvent,
            int statusCode,
            String errorCode,
            String retryAfterHeader,
            boolean staleBindingHeader) {

        static MediaDecision serve() {
            return new MediaDecision(null, 0, null, null, false);
        }

        boolean isFault() {
            return faultEvent != null;
        }
    }

    /** Decision for one provider refresh POST. */
    record RefreshDecision(
            ProviderFaultEvent faultEvent,
            int statusCode,
            String bodyJson) {

        boolean successful() {
            return statusCode == 200;
        }
    }

    /** Snapshot of every catalog resource raw path with its served length. */
    static NavigableMap<String, Long> catalogLengths(FixtureCatalog catalog) throws IOException {
        NavigableMap<String, Long> lengths = new TreeMap<>();
        try (Stream<Path> fixtureDirs = Files.list(catalog.root())) {
            for (Path fixtureDir : fixtureDirs.sorted().toList()) {
                if (!Files.isDirectory(fixtureDir, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String fixtureId = fixtureDir.getFileName().toString();
                try (Stream<Path> paths = Files.walk(fixtureDir)) {
                    for (Path path : paths.sorted().toList()) {
                        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                            continue;
                        }
                        Path relative = fixtureDir.relativize(path);
                        StringBuilder resourceId = new StringBuilder();
                        for (Path part : relative) {
                            if (!resourceId.isEmpty()) {
                                resourceId.append('/');
                            }
                            resourceId.append(part);
                        }
                        String rawPath = "/fixtures/" + fixtureId + "/" + resourceId;
                        FixtureResource resource = catalog.findRawPath(rawPath);
                        if (resource != null) {
                            lengths.put(rawPath, resource.length());
                        }
                    }
                }
            }
        }
        return lengths;
    }

    static String generationName(int generation) {
        return "gen-" + generation;
    }

    static int generationNumber(String generation) {
        if (generation == null || !GENERATION.matcher(generation).matches()) {
            return -1;
        }
        long number = 0;
        for (int index = 4; index < generation.length(); index++) {
            number = number * 10 + (generation.charAt(index) - '0');
            if (number > Integer.MAX_VALUE) {
                return -1;
            }
        }
        return (int) number;
    }

    boolean supportsRefresh() {
        return variant.family() == MediaLabProfile.N10;
    }

    boolean isIssuedGeneration(String generation) {
        int number = generationNumber(generation);
        return number >= 1 && number <= currentGeneration.get();
    }

    int currentGeneration() {
        return currentGeneration.get();
    }

    int mediaGetCount() {
        return mediaGetCount.get();
    }

    /** One provider media GET: fault injection is counted per session and never applies to HEAD. */
    synchronized MediaDecision mediaGet(
            long requestCorrelationId,
            String generation,
            String bindingRevisionHeader,
            long hostMonotonicNs) {
        int requestOrdinal = mediaGetCount.incrementAndGet();
        String bindingRevision = normalizedBindingRevision(bindingRevisionHeader);
        return switch (variant) {
            case HTTP_403_BARE -> new MediaDecision(
                    ProviderFaultEvent.media(
                            requestCorrelationId,
                            hostMonotonicNs,
                            ProviderFaultEvent.FaultKind.HTTP_403_BARE,
                            403,
                            null,
                            ProviderFaultEvent.ProviderSignal.NONE,
                            bindingRevision,
                            generation,
                            providerWallClockEpochMs),
                    403,
                    "provider_forbidden",
                    null,
                    false);
            case HTTP_429_RETRY_AFTER_DELAY_SECONDS,
                    HTTP_429_RETRY_AFTER_HTTP_DATE,
                    HTTP_429_RETRY_AFTER_ABSENT,
                    HTTP_429_RETRY_AFTER_MALFORMED ->
                    firstRetryAfter(
                            requestOrdinal,
                            requestCorrelationId,
                            generation,
                            bindingRevision,
                            hostMonotonicNs);
            case BINDING_EXPIRY_REFRESH,
                    BINDING_REFRESH_INCOMPATIBLE,
                    BINDING_REFRESH_FAILED ->
                    staleOrServe(
                            requestCorrelationId,
                            generation,
                            bindingRevision,
                            hostMonotonicNs);
        };
    }

    /** One provider refresh POST; only the N10 family serves this endpoint. */
    synchronized RefreshDecision refresh(
            long requestCorrelationId,
            String declaredGeneration,
            String bindingRevisionHeader,
            long hostMonotonicNs) {
        if (!generationName(currentGeneration.get()).equals(declaredGeneration)) {
            return new RefreshDecision(
                    ProviderFaultEvent.refresh(
                            requestCorrelationId,
                            hostMonotonicNs,
                            ProviderFaultEvent.FaultKind.REFRESH_FAILED,
                            409,
                            null,
                            providerWallClockEpochMs),
                    409,
                    errorJson("generation_mismatch"));
        }
        return switch (variant) {
            case BINDING_EXPIRY_REFRESH -> advance(
                    requestCorrelationId,
                    hostMonotonicNs,
                    ProviderFaultEvent.FaultKind.REFRESH_SUCCEEDED,
                    false);
            case BINDING_REFRESH_INCOMPATIBLE -> advance(
                    requestCorrelationId,
                    hostMonotonicNs,
                    ProviderFaultEvent.FaultKind.REFRESH_INCOMPATIBLE,
                    true);
            case BINDING_REFRESH_FAILED -> new RefreshDecision(
                    ProviderFaultEvent.refresh(
                            requestCorrelationId,
                            hostMonotonicNs,
                            ProviderFaultEvent.FaultKind.REFRESH_FAILED,
                            503,
                            null,
                            providerWallClockEpochMs),
                    503,
                    errorJson("refresh_failed"));
            default -> throw new IllegalStateException(
                    variant.family().name() + " has no provider refresh endpoint");
        };
    }

    private MediaDecision firstRetryAfter(
            int requestOrdinal,
            long requestCorrelationId,
            String generation,
            String bindingRevision,
            long hostMonotonicNs) {
        if (requestOrdinal != 1) {
            return MediaDecision.serve();
        }

        ProviderFaultEvent.RetryAfter retryAfter;
        String retryAfterHeader;
        switch (variant) {
            case HTTP_429_RETRY_AFTER_DELAY_SECONDS -> {
                retryAfter = ProviderFaultEvent.RetryAfter.delaySeconds(RETRY_AFTER_DELAY_SECONDS);
                retryAfterHeader = Long.toString(RETRY_AFTER_DELAY_SECONDS);
            }
            case HTTP_429_RETRY_AFTER_HTTP_DATE -> {
                long notBeforeUtcEpochMs =
                        providerWallClockEpochMs + RETRY_AFTER_HTTP_DATE_OFFSET_MS;
                retryAfter = ProviderFaultEvent.RetryAfter.httpDate(notBeforeUtcEpochMs);
                retryAfterHeader = HttpDates.imfFixdate(notBeforeUtcEpochMs);
            }
            case HTTP_429_RETRY_AFTER_ABSENT -> {
                retryAfter = ProviderFaultEvent.RetryAfter.ABSENT;
                retryAfterHeader = null;
            }
            default -> {
                retryAfter = ProviderFaultEvent.RetryAfter.MALFORMED;
                retryAfterHeader = "soon";
            }
        }

        return new MediaDecision(
                ProviderFaultEvent.media(
                        requestCorrelationId,
                        hostMonotonicNs,
                        ProviderFaultEvent.FaultKind.HTTP_429,
                        429,
                        retryAfter,
                        ProviderFaultEvent.ProviderSignal.NONE,
                        bindingRevision,
                        generation,
                        providerWallClockEpochMs),
                429,
                "provider_rate_limited",
                retryAfterHeader,
                false);
    }

    private MediaDecision staleOrServe(
            long requestCorrelationId,
            String generation,
            String bindingRevision,
            long hostMonotonicNs) {
        if (bindingRefreshed.get() && generationName(currentGeneration.get()).equals(generation)) {
            return MediaDecision.serve();
        }
        return new MediaDecision(
                ProviderFaultEvent.media(
                        requestCorrelationId,
                        hostMonotonicNs,
                        ProviderFaultEvent.FaultKind.BINDING_STALE,
                        403,
                        null,
                        ProviderFaultEvent.ProviderSignal.BINDING_STALE_CONFIRMED,
                        bindingRevision,
                        generation,
                        providerWallClockEpochMs),
                403,
                "binding_stale",
                null,
                true);
    }

    private RefreshDecision advance(
            long requestCorrelationId,
            long hostMonotonicNs,
            ProviderFaultEvent.FaultKind faultKind,
            boolean incompatibleLengths) {
        int generation = currentGeneration.incrementAndGet();
        bindingRefreshed.set(true);
        String generationName = generationName(generation);
        return new RefreshDecision(
                ProviderFaultEvent.refresh(
                        requestCorrelationId,
                        hostMonotonicNs,
                        faultKind,
                        200,
                        generationName,
                        providerWallClockEpochMs),
                200,
                refreshBody(generationName, incompatibleLengths));
    }

    private String refreshBody(String generation, boolean incompatibleLengths) {
        StringBuilder json = new StringBuilder(128 + catalogLengths.size() * 48);
        json.append('{')
                .append(Json.quote("generation")).append(':').append(Json.quote(generation)).append(',')
                .append(Json.quote("resources")).append(":{");
        boolean first = true;
        for (Map.Entry<String, Long> entry : catalogLengths.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            long length = incompatibleLengths ? entry.getValue() + 1 : entry.getValue();
            json.append(Json.quote(entry.getKey())).append(':').append(length);
        }
        return json.append("}}").toString();
    }

    private static String errorJson(String error) {
        return "{" + Json.quote("error") + ":" + Json.quote(error) + "}";
    }

    private static String normalizedBindingRevision(String value) {
        return value != null && BINDING_REVISION.matcher(value).matches() ? value : null;
    }
}
