package io.github.definitelystable.spongetube.medialab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaLabProviderIntegrationTest {

    private static final long WALL_CLOCK = ProviderSimulator.DEFAULT_PROVIDER_WALL_CLOCK_EPOCH_MS;

    @TempDir
    Path temp;

    @Test
    void n8InjectsBareForbiddenOnEveryProviderMediaGet() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("n8-fixtures"));
        byte[] source = writeSample(fixtureRoot, 256);
        MediaLabConfig config = providerConfig(
                MediaLabProfile.N8, ProviderVariant.HTTP_403_BARE, fixtureRoot, "n8.jsonl");

        HttpClient client = HttpClient.newHttpClient();

        try (MediaLabServer server = MediaLabServer.create(config)) {
            server.start();
            URI data = base(server.dataPort());
            URI control = base(server.controlPort());

            HttpResponse<byte[]> first = sendBytes(
                    client,
                    HttpRequest.newBuilder(
                                    data.resolve("/provider/gen-1/fixtures/F0/sample.bin"))
                            .header("X-Sponge-Binding-Revision", "binding-3")
                            .GET()
                            .build());
            assertEquals(403, first.statusCode());
            assertEquals(
                    "{\"error\":\"provider_forbidden\"}",
                    new String(first.body(), StandardCharsets.UTF_8));
            assertTrue(first.headers().firstValue("Retry-After").isEmpty());
            assertTrue(first.headers().firstValue("X-Sponge-Provider-Binding").isEmpty());
            assertTrue(first.headers()
                    .firstValue("X-Sponge-Provider-Wall-Clock-Epoch-Ms")
                    .isEmpty());
            long firstRequestId = Long.parseLong(
                    first.headers().firstValue("X-Sponge-Lab-Request").orElseThrow());

            HttpResponse<byte[]> second = sendBytes(
                    client,
                    HttpRequest.newBuilder(
                                    data.resolve("/provider/gen-1/fixtures/F0/sample.bin"))
                            .header("X-Sponge-Binding-Revision", "binding-0")
                            .GET()
                            .build());
            assertEquals(403, second.statusCode());

            HttpResponse<byte[]> head = sendBytes(
                    client,
                    HttpRequest.newBuilder(
                                    data.resolve("/provider/gen-1/fixtures/F0/sample.bin"))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .build());
            assertEquals(200, head.statusCode());
            assertEquals("256", head.headers().firstValue("Content-Length").orElseThrow());

            HttpResponse<byte[]> plain = sendBytes(
                    client,
                    HttpRequest.newBuilder(data.resolve("/fixtures/F0/sample.bin")).GET().build());
            assertEquals(200, plain.statusCode());
            assertArrayEquals(source, plain.body());

            HttpResponse<String> eventsResponse = sendText(
                    client,
                    HttpRequest.newBuilder(control.resolve("/__lab/provider/events"))
                            .GET()
                            .build());
            assertEquals(200, eventsResponse.statusCode());
            List<Map<String, Object>> events = events(eventsResponse.body());
            assertEquals(2, events.size());

            Map<String, Object> firstEvent = events.get(0);
            assertEquals(1L, firstEvent.get("sequence"));
            assertEquals("fault-1", firstEvent.get("faultId"));
            assertEquals(Long.toString(firstRequestId), firstEvent.get("requestCorrelationId"));
            assertEquals("MEDIA", firstEvent.get("requestKind"));
            assertEquals("HTTP_403_BARE", firstEvent.get("faultKind"));
            assertEquals(403L, firstEvent.get("statusCode"));
            assertNull(firstEvent.get("retryAfter"));
            assertEquals("NONE", firstEvent.get("providerSignal"));
            assertEquals("binding-3", firstEvent.get("bindingRevision"));
            assertEquals("gen-1", firstEvent.get("providerBindingGeneration"));
            assertEquals(WALL_CLOCK, firstEvent.get("providerWallClockUtcEpochMs"));
            assertTrue((Long) firstEvent.get("hostMonotonicNs") >= 0L);

            Map<String, Object> secondEvent = events.get(1);
            assertEquals(2L, secondEvent.get("sequence"));
            assertEquals("fault-2", secondEvent.get("faultId"));
            assertNull(secondEvent.get("bindingRevision"));

            assertEquals(
                    Files.readString(config.providerFaultsPath()),
                    eventsResponse.body());

            List<Map<String, Object>> trace = traceRows(config.tracePath());
            assertEquals(
                    2,
                    trace.stream()
                            .filter(row -> "PROVIDER_FAULT".equals(row.get("outcome")))
                            .filter(row -> "/provider/gen-1/fixtures/F0/sample.bin"
                                    .equals(row.get("path")))
                            .count());
            assertEquals(
                    "SUCCESS",
                    trace.stream()
                            .filter(row -> "/fixtures/F0/sample.bin".equals(row.get("path")))
                            .findFirst()
                            .orElseThrow()
                            .get("outcome"));
        }
    }

    @Test
    void n9InjectsRetryAfterOnlyOnFirstMediaGet() throws Exception {
        List<ProviderVariant> variants = List.of(
                ProviderVariant.HTTP_429_RETRY_AFTER_DELAY_SECONDS,
                ProviderVariant.HTTP_429_RETRY_AFTER_HTTP_DATE,
                ProviderVariant.HTTP_429_RETRY_AFTER_ABSENT,
                ProviderVariant.HTTP_429_RETRY_AFTER_MALFORMED);

        HttpClient client = HttpClient.newHttpClient();

        for (ProviderVariant variant : variants) {
            Path fixtureRoot = Files.createDirectory(
                    temp.resolve("n9-" + variant.name().toLowerCase(Locale.ROOT)));
            byte[] source = writeSample(fixtureRoot, 256);
            MediaLabConfig config = providerConfig(
                    MediaLabProfile.N9, variant, fixtureRoot, "n9-" + variant.name() + ".jsonl");

            try (MediaLabServer server = MediaLabServer.create(config)) {
                server.start();
                URI data = base(server.dataPort());
                URI control = base(server.controlPort());

                // HEAD is served without faults and never consumes the first GET.
                HttpResponse<byte[]> head = sendBytes(
                        client,
                        HttpRequest.newBuilder(
                                        data.resolve("/provider/gen-1/fixtures/F0/sample.bin"))
                                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                                .build());
                assertEquals(200, head.statusCode(), variant.name());

                HttpResponse<byte[]> first = sendBytes(
                        client,
                        HttpRequest.newBuilder(
                                        data.resolve("/provider/gen-1/fixtures/F0/sample.bin"))
                                .GET()
                                .build());
                assertEquals(429, first.statusCode(), variant.name());
                switch (variant) {
                    case HTTP_429_RETRY_AFTER_DELAY_SECONDS -> assertEquals(
                            "2", first.headers().firstValue("Retry-After").orElseThrow());
                    case HTTP_429_RETRY_AFTER_HTTP_DATE -> assertEquals(
                            "Fri, 25 Sep 2026 12:00:02 GMT",
                            first.headers().firstValue("Retry-After").orElseThrow());
                    case HTTP_429_RETRY_AFTER_ABSENT -> assertTrue(
                            first.headers().firstValue("Retry-After").isEmpty());
                    default -> assertEquals(
                            "soon", first.headers().firstValue("Retry-After").orElseThrow());
                }

                HttpResponse<byte[]> second = sendBytes(
                        client,
                        HttpRequest.newBuilder(
                                        data.resolve("/provider/gen-1/fixtures/F0/sample.bin"))
                                .header("Range", "bytes=0-15")
                                .GET()
                                .build());
                assertEquals(206, second.statusCode(), variant.name());
                assertArrayEquals(Arrays.copyOfRange(source, 0, 16), second.body());
                assertEquals(
                        "bytes 0-15/256",
                        second.headers().firstValue("Content-Range").orElseThrow());

                HttpResponse<String> eventsResponse = sendText(
                        client,
                        HttpRequest.newBuilder(control.resolve("/__lab/provider/events"))
                                .GET()
                                .build());
                assertEquals(200, eventsResponse.statusCode());
                List<Map<String, Object>> events = events(eventsResponse.body());
                assertEquals(1, events.size(), variant.name());

                Map<String, Object> event = events.get(0);
                assertEquals("HTTP_429", event.get("faultKind"));
                assertEquals(429L, event.get("statusCode"));
                assertEquals("NONE", event.get("providerSignal"));
                assertEquals("gen-1", event.get("providerBindingGeneration"));
                assertEquals(WALL_CLOCK, event.get("providerWallClockUtcEpochMs"));
                assertNull(event.get("bindingRevision"));

                Map<String, Object> retryAfter = map(event.get("retryAfter"));
                switch (variant) {
                    case HTTP_429_RETRY_AFTER_DELAY_SECONDS -> {
                        assertEquals("DELAY_SECONDS", retryAfter.get("rawKind"));
                        assertEquals(2L, retryAfter.get("delaySeconds"));
                        assertNull(retryAfter.get("notBeforeUtcEpochMs"));
                    }
                    case HTTP_429_RETRY_AFTER_HTTP_DATE -> {
                        assertEquals("HTTP_DATE", retryAfter.get("rawKind"));
                        assertNull(retryAfter.get("delaySeconds"));
                        assertEquals(WALL_CLOCK + 2_000L, retryAfter.get("notBeforeUtcEpochMs"));
                    }
                    case HTTP_429_RETRY_AFTER_ABSENT -> {
                        assertEquals("ABSENT", retryAfter.get("rawKind"));
                        assertNull(retryAfter.get("delaySeconds"));
                        assertNull(retryAfter.get("notBeforeUtcEpochMs"));
                    }
                    default -> {
                        assertEquals("MALFORMED", retryAfter.get("rawKind"));
                        assertNull(retryAfter.get("delaySeconds"));
                        assertNull(retryAfter.get("notBeforeUtcEpochMs"));
                    }
                }

                assertEquals(
                        Files.readString(config.providerFaultsPath()),
                        eventsResponse.body());
            }
        }
    }

    @Test
    void n10ExpiryRefreshFlowAdvancesGeneration() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("n10-expiry-fixtures"));
        byte[] source = writeSample(fixtureRoot, 256);
        MediaLabConfig config = providerConfig(
                MediaLabProfile.N10,
                ProviderVariant.BINDING_EXPIRY_REFRESH,
                fixtureRoot,
                "n10-expiry.jsonl");

        HttpClient client = HttpClient.newHttpClient();

        try (MediaLabServer server = MediaLabServer.create(config)) {
            server.start();
            URI data = base(server.dataPort());
            URI control = base(server.controlPort());

            HttpResponse<byte[]> stale = sendBytes(
                    client,
                    HttpRequest.newBuilder(
                                    data.resolve("/provider/gen-1/fixtures/F0/sample.bin"))
                            .GET()
                            .build());
            assertEquals(403, stale.statusCode());
            assertEquals(
                    "STALE",
                    stale.headers().firstValue("X-Sponge-Provider-Binding").orElseThrow());
            assertEquals(
                    "{\"error\":\"binding_stale\"}",
                    new String(stale.body(), StandardCharsets.UTF_8));

            HttpResponse<byte[]> unissued = sendBytes(
                    client,
                    HttpRequest.newBuilder(
                                    data.resolve("/provider/gen-2/fixtures/F0/sample.bin"))
                            .GET()
                            .build());
            assertEquals(404, unissued.statusCode());

            HttpResponse<String> wrongGeneration = sendText(
                    client,
                    HttpRequest.newBuilder(data.resolve("/provider/refresh"))
                            .header("X-Sponge-Provider-Generation", "gen-2")
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build());
            assertEquals(409, wrongGeneration.statusCode());

            HttpResponse<String> refresh = sendText(
                    client,
                    HttpRequest.newBuilder(data.resolve("/provider/refresh"))
                            .header("X-Sponge-Provider-Generation", "gen-1")
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build());
            assertEquals(200, refresh.statusCode());
            Map<String, Object> refreshBody = document(refresh.body());
            assertEquals("gen-2", refreshBody.get("generation"));
            Map<String, Object> resources = map(refreshBody.get("resources"));
            assertEquals(256L, resources.get("/fixtures/F0/sample.bin"));

            HttpResponse<byte[]> staleAgain = sendBytes(
                    client,
                    HttpRequest.newBuilder(
                                    data.resolve("/provider/gen-1/fixtures/F0/sample.bin"))
                            .GET()
                            .build());
            assertEquals(403, staleAgain.statusCode());
            assertEquals(
                    "STALE",
                    staleAgain.headers().firstValue("X-Sponge-Provider-Binding").orElseThrow());

            HttpResponse<byte[]> served = sendBytes(
                    client,
                    HttpRequest.newBuilder(
                                    data.resolve("/provider/gen-2/fixtures/F0/sample.bin"))
                            .header("Range", "bytes=10-19")
                            .GET()
                            .build());
            assertEquals(206, served.statusCode());
            assertArrayEquals(Arrays.copyOfRange(source, 10, 20), served.body());
            assertEquals(
                    "bytes 10-19/256",
                    served.headers().firstValue("Content-Range").orElseThrow());
            assertTrue(served.headers().firstValue("X-Sponge-Provider-Binding").isEmpty());

            HttpResponse<String> eventsResponse = sendText(
                    client,
                    HttpRequest.newBuilder(control.resolve("/__lab/provider/events"))
                            .GET()
                            .build());
            assertEquals(200, eventsResponse.statusCode());
            List<Map<String, Object>> events = events(eventsResponse.body());
            assertEquals(4, events.size());

            Map<String, Object> staleEvent = events.get(0);
            assertEquals("BINDING_STALE", staleEvent.get("faultKind"));
            assertEquals(403L, staleEvent.get("statusCode"));
            assertEquals("BINDING_STALE_CONFIRMED", staleEvent.get("providerSignal"));
            assertEquals("gen-1", staleEvent.get("providerBindingGeneration"));

            Map<String, Object> rejectedEvent = events.get(1);
            assertEquals("REFRESH", rejectedEvent.get("requestKind"));
            assertEquals("REFRESH_FAILED", rejectedEvent.get("faultKind"));
            assertEquals(409L, rejectedEvent.get("statusCode"));
            assertNull(rejectedEvent.get("providerBindingGeneration"));

            Map<String, Object> refreshedEvent = events.get(2);
            assertEquals("REFRESH", refreshedEvent.get("requestKind"));
            assertEquals("REFRESH_SUCCEEDED", refreshedEvent.get("faultKind"));
            assertEquals(200L, refreshedEvent.get("statusCode"));
            assertEquals("gen-2", refreshedEvent.get("providerBindingGeneration"));
            assertNull(refreshedEvent.get("providerSignal"));

            assertEquals("BINDING_STALE", events.get(3).get("faultKind"));

            assertEquals(
                    Files.readString(config.providerFaultsPath()),
                    eventsResponse.body());

            HttpResponse<String> configResponse = sendText(
                    client,
                    HttpRequest.newBuilder(control.resolve("/__lab/config")).GET().build());
            assertEquals(200, configResponse.statusCode());
            Map<String, Object> configJson = document(configResponse.body());
            assertEquals("N10", configJson.get("profileId"));
            assertEquals("BINDING_EXPIRY_REFRESH", configJson.get("providerVariant"));
            assertEquals(WALL_CLOCK, configJson.get("providerWallClockEpochMs"));
            assertEquals(
                    config.providerFaultsPath().toString(),
                    configJson.get("providerFaults"));

            List<Map<String, Object>> trace = traceRows(config.tracePath());
            assertEquals(
                    "PROVIDER_FAULT",
                    trace.stream()
                            .filter(row -> "POST".equals(row.get("method"))
                                    && "/provider/refresh".equals(row.get("path")))
                            .filter(row -> Long.valueOf(409L).equals(row.get("status")))
                            .findFirst()
                            .orElseThrow()
                            .get("outcome"));
            assertEquals(
                    "SUCCESS",
                    trace.stream()
                            .filter(row -> "POST".equals(row.get("method"))
                                    && "/provider/refresh".equals(row.get("path")))
                            .filter(row -> Long.valueOf(200L).equals(row.get("status")))
                            .findFirst()
                            .orElseThrow()
                            .get("outcome"));
        }
    }

    @Test
    void n10IncompatibleRefreshReportsLengthPlusOne() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("n10-incompatible-fixtures"));
        byte[] source = writeSample(fixtureRoot, 256);
        MediaLabConfig config = providerConfig(
                MediaLabProfile.N10,
                ProviderVariant.BINDING_REFRESH_INCOMPATIBLE,
                fixtureRoot,
                "n10-incompatible.jsonl");

        HttpClient client = HttpClient.newHttpClient();

        try (MediaLabServer server = MediaLabServer.create(config)) {
            server.start();
            URI data = base(server.dataPort());

            HttpResponse<String> refresh = sendText(
                    client,
                    HttpRequest.newBuilder(data.resolve("/provider/refresh"))
                            .header("X-Sponge-Provider-Generation", "gen-1")
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build());
            assertEquals(200, refresh.statusCode());
            Map<String, Object> refreshBody = document(refresh.body());
            assertEquals("gen-2", refreshBody.get("generation"));
            Map<String, Object> resources = map(refreshBody.get("resources"));
            assertEquals(257L, resources.get("/fixtures/F0/sample.bin"));

            HttpResponse<String> eventsResponse = sendText(
                    client,
                    HttpRequest.newBuilder(base(server.controlPort())
                                    .resolve("/__lab/provider/events"))
                            .GET()
                            .build());
            List<Map<String, Object>> events = events(eventsResponse.body());
            assertEquals(1, events.size());
            assertEquals("REFRESH_INCOMPATIBLE", events.get(0).get("faultKind"));
            assertEquals(200L, events.get(0).get("statusCode"));
            assertEquals("gen-2", events.get(0).get("providerBindingGeneration"));
            assertEquals(
                    Files.readString(config.providerFaultsPath()),
                    eventsResponse.body());

            HttpResponse<byte[]> served = sendBytes(
                    client,
                    HttpRequest.newBuilder(
                                    data.resolve("/provider/gen-2/fixtures/F0/sample.bin"))
                            .header("Range", "bytes=0-255")
                            .GET()
                            .build());
            assertEquals(206, served.statusCode());
            assertArrayEquals(source, served.body());
            assertEquals(
                    "bytes 0-255/256",
                    served.headers().firstValue("Content-Range").orElseThrow());
        }
    }

    @Test
    void n10FailedRefreshKeepsGenerationAndStale() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("n10-failed-fixtures"));
        writeSample(fixtureRoot, 256);
        MediaLabConfig config = providerConfig(
                MediaLabProfile.N10,
                ProviderVariant.BINDING_REFRESH_FAILED,
                fixtureRoot,
                "n10-failed.jsonl");

        HttpClient client = HttpClient.newHttpClient();

        try (MediaLabServer server = MediaLabServer.create(config)) {
            server.start();
            URI data = base(server.dataPort());

            HttpResponse<String> refresh = sendText(
                    client,
                    HttpRequest.newBuilder(data.resolve("/provider/refresh"))
                            .header("X-Sponge-Provider-Generation", "gen-1")
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build());
            assertEquals(503, refresh.statusCode());

            HttpResponse<byte[]> stale = sendBytes(
                    client,
                    HttpRequest.newBuilder(
                                    data.resolve("/provider/gen-1/fixtures/F0/sample.bin"))
                            .GET()
                            .build());
            assertEquals(403, stale.statusCode());
            assertEquals(
                    "STALE",
                    stale.headers().firstValue("X-Sponge-Provider-Binding").orElseThrow());

            HttpResponse<byte[]> unissued = sendBytes(
                    client,
                    HttpRequest.newBuilder(
                                    data.resolve("/provider/gen-2/fixtures/F0/sample.bin"))
                            .GET()
                            .build());
            assertEquals(404, unissued.statusCode());

            HttpResponse<String> eventsResponse = sendText(
                    client,
                    HttpRequest.newBuilder(base(server.controlPort())
                                    .resolve("/__lab/provider/events"))
                            .GET()
                            .build());
            List<Map<String, Object>> events = events(eventsResponse.body());
            assertEquals(2, events.size());
            assertEquals("REFRESH_FAILED", events.get(0).get("faultKind"));
            assertEquals(503L, events.get(0).get("statusCode"));
            assertNull(events.get(0).get("providerBindingGeneration"));
            assertEquals("BINDING_STALE", events.get(1).get("faultKind"));
            assertEquals(
                    Files.readString(config.providerFaultsPath()),
                    eventsResponse.body());

            List<Map<String, Object>> trace = traceRows(config.tracePath());
            assertEquals(
                    "PROVIDER_FAULT",
                    trace.stream()
                            .filter(row -> "/provider/refresh".equals(row.get("path")))
                            .findFirst()
                            .orElseThrow()
                            .get("outcome"));
        }
    }

    @Test
    void providerProfilesRejectUnsupportedProviderRoutes() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("provider-route-fixtures"));
        writeSample(fixtureRoot, 256);
        MediaLabConfig config = providerConfig(
                MediaLabProfile.N8, ProviderVariant.HTTP_403_BARE, fixtureRoot, "n8-routes.jsonl");

        HttpClient client = HttpClient.newHttpClient();

        try (MediaLabServer server = MediaLabServer.create(config)) {
            server.start();
            URI data = base(server.dataPort());

            HttpResponse<String> refresh = sendText(
                    client,
                    HttpRequest.newBuilder(data.resolve("/provider/refresh"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build());
            assertEquals(404, refresh.statusCode());

            HttpResponse<String> wrongMethod = sendText(
                    client,
                    HttpRequest.newBuilder(
                                    data.resolve("/provider/gen-1/fixtures/F0/sample.bin"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build());
            assertEquals(405, wrongMethod.statusCode());
            assertEquals(
                    "GET, HEAD", wrongMethod.headers().firstValue("Allow").orElseThrow());

            HttpResponse<String> malformedGeneration = sendText(
                    client,
                    HttpRequest.newBuilder(
                                    data.resolve("/provider/gen-0/fixtures/F0/sample.bin"))
                            .GET()
                            .build());
            assertEquals(404, malformedGeneration.statusCode());

            HttpResponse<String> unissuedGeneration = sendText(
                    client,
                    HttpRequest.newBuilder(
                                    data.resolve("/provider/gen-2/fixtures/F0/sample.bin"))
                            .GET()
                            .build());
            assertEquals(404, unissuedGeneration.statusCode());

            HttpResponse<String> missingResource = sendText(
                    client,
                    HttpRequest.newBuilder(
                                    data.resolve("/provider/gen-1/fixtures/F0/missing.bin"))
                            .GET()
                            .build());
            assertEquals(404, missingResource.statusCode());

            HttpResponse<String> notAFixturePath = sendText(
                    client,
                    HttpRequest.newBuilder(
                                    data.resolve("/provider/gen-1/other/sample.bin"))
                            .GET()
                            .build());
            assertEquals(404, notAFixturePath.statusCode());
        }

        MediaLabConfig n10Config = providerConfig(
                MediaLabProfile.N10,
                ProviderVariant.BINDING_EXPIRY_REFRESH,
                fixtureRoot,
                "n10-routes.jsonl");
        try (MediaLabServer server = MediaLabServer.create(n10Config)) {
            server.start();
            URI data = base(server.dataPort());

            HttpResponse<String> getRefresh = sendText(
                    client,
                    HttpRequest.newBuilder(data.resolve("/provider/refresh")).GET().build());
            assertEquals(405, getRefresh.statusCode());
            assertEquals("POST", getRefresh.headers().firstValue("Allow").orElseThrow());
        }
    }

    @Test
    void nonProviderProfilesRejectProviderRoutesAndConfigKeysAreNull() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("n0-fixtures"));
        byte[] source = writeSample(fixtureRoot, 256);
        MediaLabConfig config = new MediaLabConfig(
                fixtureRoot,
                temp.resolve("n0-provider.jsonl"),
                "n0-provider",
                MediaLabProfile.N0,
                0,
                2,
                0,
                1,
                null,
                null,
                MediaLabConfig.DEFAULT_WRITE_QUANTUM_BYTES);

        HttpClient client = HttpClient.newHttpClient();

        try (MediaLabServer server = MediaLabServer.create(config)) {
            server.start();
            URI data = base(server.dataPort());
            URI control = base(server.controlPort());

            HttpResponse<byte[]> providerMedia = sendBytes(
                    client,
                    HttpRequest.newBuilder(
                                    data.resolve("/provider/gen-1/fixtures/F0/sample.bin"))
                            .GET()
                            .build());
            assertEquals(404, providerMedia.statusCode());

            HttpResponse<String> refresh = sendText(
                    client,
                    HttpRequest.newBuilder(data.resolve("/provider/refresh"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build());
            assertEquals(404, refresh.statusCode());

            HttpResponse<String> eventsResponse = sendText(
                    client,
                    HttpRequest.newBuilder(control.resolve("/__lab/provider/events"))
                            .GET()
                            .build());
            assertEquals(404, eventsResponse.statusCode());

            HttpResponse<byte[]> plain = sendBytes(
                    client,
                    HttpRequest.newBuilder(data.resolve("/fixtures/F0/sample.bin")).GET().build());
            assertEquals(200, plain.statusCode());
            assertArrayEquals(source, plain.body());

            Map<String, Object> configJson = document(
                    sendText(
                                    client,
                                    HttpRequest.newBuilder(control.resolve("/__lab/config"))
                                            .GET()
                                            .build())
                            .body());
            assertNull(configJson.get("providerVariant"));
            assertNull(configJson.get("providerWallClockEpochMs"));
            assertNull(configJson.get("providerFaults"));

            String ready = server.readyJson();
            assertTrue(ready.contains("\"providerVariant\":null"));
            assertTrue(ready.contains("\"providerWallClockEpochMs\":null"));
            assertTrue(ready.contains("\"providerFaults\":null"));
        }
    }

    private MediaLabConfig providerConfig(
            MediaLabProfile profile,
            ProviderVariant variant,
            Path fixtureRoot,
            String traceName) {
        return new MediaLabConfig(
                fixtureRoot,
                temp.resolve(traceName),
                "provider-" + traceName,
                profile,
                0,
                2,
                0,
                1,
                null,
                null,
                MediaLabConfig.DEFAULT_WRITE_QUANTUM_BYTES,
                variant,
                WALL_CLOCK);
    }

    private static byte[] writeSample(Path fixtureRoot, int size) throws Exception {
        Path fixture = Files.createDirectory(fixtureRoot.resolve("F0"));
        byte[] source = new byte[size];
        for (int index = 0; index < source.length; index++) {
            source[index] = (byte) index;
        }
        Files.write(fixture.resolve("sample.bin"), source);
        return source;
    }

    private static URI base(int port) {
        return URI.create("http://127.0.0.1:" + port);
    }

    private static HttpResponse<String> sendText(HttpClient client, HttpRequest request)
            throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<byte[]> sendBytes(HttpClient client, HttpRequest request)
            throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> document(String json) {
        return (Map<String, Object>) MiniJsonParser.parse(json);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> events(String json) {
        return (List<Map<String, Object>>) (List<?>) document(json).get("events");
    }

    private static List<Map<String, Object>> traceRows(Path tracePath) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String line : Files.readAllLines(tracePath)) {
            if (!line.isBlank()) {
                rows.add(document(line));
            }
        }
        return rows;
    }
}
