package io.github.definitelystable.spongetube.medialab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaLabServerTest {

    @TempDir
    Path temp;

    @Test
    void dataBindFailureDoesNotCreateTraceFile() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("data-bind-fixtures"));
        Files.createDirectory(fixtureRoot.resolve("F0"));
        Path tracePath = temp.resolve("data-bind-failure.jsonl");

        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            MediaLabConfig config = new MediaLabConfig(
                    fixtureRoot,
                    tracePath,
                    "data-bind-failure",
                    MediaLabProfile.N0,
                    occupied.getLocalPort(),
                    2,
                    0,
                    1);

            assertThrows(java.net.BindException.class, () -> MediaLabServer.create(config));
        }

        assertFalse(Files.exists(tracePath));
    }

    @Test
    void controlBindFailureDoesNotCreateTraceFile() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("control-bind-fixtures"));
        Files.createDirectory(fixtureRoot.resolve("F0"));
        Path tracePath = temp.resolve("control-bind-failure.jsonl");

        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            MediaLabConfig config = new MediaLabConfig(
                    fixtureRoot,
                    tracePath,
                    "control-bind-failure",
                    MediaLabProfile.N0,
                    0,
                    2,
                    occupied.getLocalPort(),
                    1);

            assertThrows(java.net.BindException.class, () -> MediaLabServer.create(config));
        }

        assertFalse(Files.exists(tracePath));
    }

    @Test
    void separatesControlAndDataPlanesAndWritesCorrelatedTrace() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("fixtures"));
        Path fixture = Files.createDirectory(fixtureRoot.resolve("F0"));

        byte[] source = new byte[256];
        for (int i = 0; i < source.length; i++) {
            source[i] = (byte) i;
        }
        Files.write(fixture.resolve("sample.bin"), source);

        Path tracePath = temp.resolve("trace.jsonl");
        MediaLabConfig config = new MediaLabConfig(
                fixtureRoot,
                tracePath,
                "integration-1",
                MediaLabProfile.N0,
                0,
                4,
                0,
                2);

        HttpClient client = HttpClient.newHttpClient();

        try (MediaLabServer server = MediaLabServer.create(config)) {
            server.start();

            assertTrue(server.dataPort() > 0);
            assertTrue(server.controlPort() > 0);
            assertNotEquals(server.dataPort(), server.controlPort());
            assertTrue(server.readyJson().contains("\"schemaVersion\":2"));
            assertTrue(server.readyJson().contains("\"MEDIA_LAB_READY\""));
            assertTrue(server.readyJson().contains("\"dataPort\":" + server.dataPort()));
            assertTrue(server.readyJson().contains("\"controlPort\":" + server.controlPort()));

            URI dataBase = URI.create("http://127.0.0.1:" + server.dataPort());
            URI controlBase = URI.create("http://127.0.0.1:" + server.controlPort());

            HttpResponse<String> health = sendText(
                    client,
                    HttpRequest.newBuilder(controlBase.resolve("/__lab/health")).GET().build());
            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("\"status\":\"ok\""));
            assertEquals(
                    "control",
                    health.headers().firstValue("X-Sponge-Lab-Plane").orElseThrow());
            assertEquals(
                    "integration-1",
                    health.headers().firstValue("X-Sponge-Lab-Session").orElseThrow());

            HttpResponse<String> configResponse = sendText(
                    client,
                    HttpRequest.newBuilder(controlBase.resolve("/__lab/config")).GET().build());
            assertEquals(200, configResponse.statusCode());
            assertTrue(configResponse.body().contains("\"profileId\":\"N0\""));
            assertTrue(configResponse.body().contains("\"dataPort\":" + server.dataPort()));
            assertTrue(configResponse.body().contains("\"controlPort\":" + server.controlPort()));

            HttpResponse<String> dataHealth = sendText(
                    client,
                    HttpRequest.newBuilder(dataBase.resolve("/__lab/health")).GET().build());
            assertEquals(404, dataHealth.statusCode());

            HttpResponse<String> controlFixture = sendText(
                    client,
                    HttpRequest.newBuilder(controlBase.resolve("/fixtures/F0/sample.bin")).GET().build());
            assertEquals(404, controlFixture.statusCode());

            HttpResponse<byte[]> full = sendBytes(
                    client,
                    HttpRequest.newBuilder(dataBase.resolve("/fixtures/F0/sample.bin")).GET().build());
            assertEquals(200, full.statusCode());
            assertArrayEquals(source, full.body());
            assertEquals("bytes", full.headers().firstValue("Accept-Ranges").orElseThrow());
            assertEquals("data", full.headers().firstValue("X-Sponge-Lab-Plane").orElseThrow());
            assertEquals("N0", full.headers().firstValue("X-Sponge-Lab-Profile").orElseThrow());
            assertTrue(Long.parseLong(
                    full.headers().firstValue("X-Sponge-Lab-Request").orElseThrow()) > 0);

            HttpResponse<byte[]> partial = sendBytes(
                    client,
                    HttpRequest.newBuilder(dataBase.resolve("/fixtures/F0/sample.bin"))
                            .header("Range", "bytes=10-19")
                            .GET()
                            .build());
            assertEquals(206, partial.statusCode());
            assertArrayEquals(java.util.Arrays.copyOfRange(source, 10, 20), partial.body());
            assertEquals(
                    "bytes 10-19/256",
                    partial.headers().firstValue("Content-Range").orElseThrow());

            HttpResponse<byte[]> suffix = sendBytes(
                    client,
                    HttpRequest.newBuilder(dataBase.resolve("/fixtures/F0/sample.bin"))
                            .header("Range", "bytes=-4")
                            .GET()
                            .build());
            assertEquals(206, suffix.statusCode());
            assertArrayEquals(java.util.Arrays.copyOfRange(source, 252, 256), suffix.body());

            HttpResponse<byte[]> unsatisfiable = sendBytes(
                    client,
                    HttpRequest.newBuilder(dataBase.resolve("/fixtures/F0/sample.bin"))
                            .header("Range", "bytes=256-300")
                            .GET()
                            .build());
            assertEquals(416, unsatisfiable.statusCode());
            assertEquals(
                    "bytes */256",
                    unsatisfiable.headers().firstValue("Content-Range").orElseThrow());

            HttpResponse<byte[]> malformed = sendBytes(
                    client,
                    HttpRequest.newBuilder(dataBase.resolve("/fixtures/F0/sample.bin"))
                            .header("Range", "bytes=bad")
                            .GET()
                            .build());
            assertEquals(200, malformed.statusCode());
            assertArrayEquals(source, malformed.body());

            HttpResponse<byte[]> multiple = sendBytes(
                    client,
                    HttpRequest.newBuilder(dataBase.resolve("/fixtures/F0/sample.bin"))
                            .header("Range", "bytes=0-1,4-5")
                            .GET()
                            .build());
            assertEquals(200, multiple.statusCode());
            assertArrayEquals(source, multiple.body());

            HttpResponse<byte[]> head = sendBytes(
                    client,
                    HttpRequest.newBuilder(dataBase.resolve("/fixtures/F0/sample.bin"))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .build());
            assertEquals(200, head.statusCode());
            assertEquals(0, head.body().length);
            assertEquals("256", head.headers().firstValue("Content-Length").orElseThrow());

            HttpResponse<String> missing = sendText(
                    client,
                    HttpRequest.newBuilder(dataBase.resolve("/fixtures/F0/missing.bin")).GET().build());
            assertEquals(404, missing.statusCode());

            HttpResponse<byte[]> missingHead = sendBytes(
                    client,
                    HttpRequest.newBuilder(dataBase.resolve("/fixtures/F0/missing.bin"))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .build());
            assertEquals(404, missingHead.statusCode());
            assertEquals(0, missingHead.body().length);
            assertTrue(missingHead.headers().firstValue("Content-Length").isPresent());

            HttpResponse<byte[]> controlHead = sendBytes(
                    client,
                    HttpRequest.newBuilder(controlBase.resolve("/__lab/health"))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .build());
            assertEquals(405, controlHead.statusCode());
            assertEquals(0, controlHead.body().length);
            assertEquals("GET", controlHead.headers().firstValue("Allow").orElseThrow());

            HttpResponse<String> traversal = sendText(
                    client,
                    HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + server.dataPort()
                                            + "/fixtures/F0/%252e%252e/secret"))
                            .GET()
                            .build());
            assertEquals(404, traversal.statusCode());

            HttpResponse<String> methodNotAllowed = sendText(
                    client,
                    HttpRequest.newBuilder(dataBase.resolve("/fixtures/F0/sample.bin"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build());
            assertEquals(405, methodNotAllowed.statusCode());
            assertEquals("GET, HEAD", methodNotAllowed.headers().firstValue("Allow").orElseThrow());
        }

        List<String> traceLines = Files.readAllLines(tracePath);
        assertEquals(16, traceLines.size());
        assertTrue(traceLines.stream().allMatch(line -> line.startsWith("{") && line.endsWith("}")));
        assertTrue(traceLines.stream().anyMatch(line ->
                line.contains("\"status\":200")
                        && line.contains("\"resolvedRangeStart\":0")
                        && line.contains("\"resolvedRangeEndExclusive\":256")));
        assertTrue(traceLines.stream().anyMatch(line ->
                line.contains("\"status\":206")
                        && line.contains("\"resolvedRangeStart\":10")
                        && line.contains("\"resolvedRangeEndExclusive\":20")));
        assertTrue(traceLines.stream().anyMatch(line ->
                line.contains("\"status\":416")
                        && line.contains("\"outcome\":\"RANGE_UNSATISFIABLE\"")));
    }

    private static HttpResponse<String> sendText(HttpClient client, HttpRequest request)
            throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<byte[]> sendBytes(HttpClient client, HttpRequest request)
            throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }
}
