package io.github.definitelystable.spongetube.medialab;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

record ResolvedScenario(
        int schemaVersion,
        String scenarioId,
        MediaLabProfile profile,
        long firstBodyDelayMs,
        Long referencePlaybackBitrateBps,
        Double aggregateRateRatio,
        Long aggregateRateBps,
        int writeQuantumBytes,
        Long noProgressStartAfterMs,
        Long noProgressDurationMs,
        Long randomSeed) {

    static final long CANONICAL_N4_DURATION_MS = 120_000L;
    static final double CANONICAL_N1_RATE_RATIO = 0.50d;

    ResolvedScenario {
        scenarioId = Objects.requireNonNull(scenarioId, "scenarioId");
        profile = Objects.requireNonNull(profile, "profile");

        if (schemaVersion != 1) {
            throw new IllegalArgumentException("ResolvedScenario schemaVersion must be 1");
        }
        if (scenarioId.isBlank()) {
            throw new IllegalArgumentException("scenarioId must not be blank");
        }
        if (firstBodyDelayMs < 0) {
            throw new IllegalArgumentException("firstBodyDelayMs must be >= 0");
        }
        if (aggregateRateRatio != null && !(aggregateRateRatio > 0.0d)) {
            throw new IllegalArgumentException("aggregateRateRatio must be > 0");
        }
        if (aggregateRateBps != null && aggregateRateBps <= 0) {
            throw new IllegalArgumentException("aggregateRateBps must be > 0");
        }
        if (writeQuantumBytes < 1 || writeQuantumBytes > 1024 * 1024) {
            throw new IllegalArgumentException("writeQuantumBytes must be between 1 and 1048576");
        }
        if ((noProgressStartAfterMs == null) != (noProgressDurationMs == null)) {
            throw new IllegalArgumentException(
                    "no-progress start and duration must either both be present or both be absent");
        }
        if (noProgressStartAfterMs != null && noProgressStartAfterMs < 0) {
            throw new IllegalArgumentException("noProgressStartAfterMs must be >= 0");
        }
        if (noProgressDurationMs != null && noProgressDurationMs <= 0) {
            throw new IllegalArgumentException("noProgressDurationMs must be > 0");
        }
    }

    static ResolvedScenario resolve(MediaLabConfig config) {
        return switch (config.profile()) {
            case N0 -> new ResolvedScenario(
                    1,
                    "N0",
                    MediaLabProfile.N0,
                    0,
                    null,
                    null,
                    null,
                    config.writeQuantumBytes(),
                    null,
                    null,
                    null);
            case N1 -> {
                long reference = Objects.requireNonNull(
                        config.referencePlaybackBitrateBps(),
                        "referencePlaybackBitrateBps");
                long resolvedRate = Math.round(reference * CANONICAL_N1_RATE_RATIO);
                if (resolvedRate <= 0) {
                    throw new IllegalArgumentException("Resolved N1 rate must be > 0");
                }
                yield new ResolvedScenario(
                        1,
                        "N1",
                        MediaLabProfile.N1,
                        120,
                        reference,
                        CANONICAL_N1_RATE_RATIO,
                        resolvedRate,
                        config.writeQuantumBytes(),
                        null,
                        null,
                        null);
            }
            case N4 -> new ResolvedScenario(
                    1,
                    "N4",
                    MediaLabProfile.N4,
                    0,
                    null,
                    null,
                    null,
                    config.writeQuantumBytes(),
                    Objects.requireNonNull(
                            config.noProgressStartAfterMs(),
                            "noProgressStartAfterMs"),
                    CANONICAL_N4_DURATION_MS,
                    null);
        };
    }

    static ResolvedScenario testScenario(
            String scenarioId,
            MediaLabProfile profile,
            long firstBodyDelayMs,
            Long aggregateRateBps,
            int writeQuantumBytes,
            Long noProgressStartAfterMs,
            Long noProgressDurationMs) {
        return new ResolvedScenario(
                1,
                scenarioId,
                profile,
                firstBodyDelayMs,
                null,
                null,
                aggregateRateBps,
                writeQuantumBytes,
                noProgressStartAfterMs,
                noProgressDurationMs,
                null);
    }

    String scenarioHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonicalJson().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    String canonicalJson() {
        return "{"
                + Json.quote("schemaVersion") + ":" + schemaVersion + ","
                + Json.quote("scenarioId") + ":" + Json.quote(scenarioId) + ","
                + Json.quote("profileId") + ":" + Json.quote(profile.name()) + ","
                + Json.quote("firstBodyDelayMs") + ":" + firstBodyDelayMs + ","
                + Json.quote("referencePlaybackBitrateBps") + ":"
                + nullableNumber(referencePlaybackBitrateBps) + ","
                + Json.quote("aggregateRateRatio") + ":" + ratioJson(aggregateRateRatio) + ","
                + Json.quote("aggregateRateBps") + ":" + nullableNumber(aggregateRateBps) + ","
                + Json.quote("writeQuantumBytes") + ":" + writeQuantumBytes + ","
                + Json.quote("noProgressStartAfterMs") + ":" + nullableNumber(noProgressStartAfterMs) + ","
                + Json.quote("noProgressDurationMs") + ":" + nullableNumber(noProgressDurationMs) + ","
                + Json.quote("randomSeed") + ":" + nullableNumber(randomSeed)
                + "}";
    }

    String toJson() {
        String canonical = canonicalJson();
        return canonical.substring(0, canonical.length() - 1)
                + ","
                + Json.quote("scenarioHash") + ":" + Json.quote(scenarioHash())
                + "}";
    }

    private static String ratioJson(Double ratio) {
        return ratio == null ? "null" : String.format(Locale.ROOT, "%.6f", ratio);
    }

    private static String nullableNumber(Long value) {
        return value == null ? "null" : Long.toString(value);
    }
}
