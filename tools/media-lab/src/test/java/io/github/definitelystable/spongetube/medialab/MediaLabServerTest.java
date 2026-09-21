package io.github.definitelystable.spongetube.medialab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void servesControlAndFixtureHttpContractAndWritesTrace() throws Exception {
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
                4);

        HttpClient client = HttpClient.newHttpClient();

        try (MediaLabServer server = MediaLabServer.create(config)) {
            server.start();
            assertTrue(server.port() > 0);
            assertTrue(server.readyJson().contains("\"MEDIA_LAB_READY\""));

            URI base = URI.create("http://127.0.0.1:" + server.port());

            HttpResponse<String> health = sendText(
                    client,
                    HttpRequest.newBuilder(base.resolve("/__lab/health")).GET().build());
            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("\"status\":\"ok\""));

            HttpResponse<String> configResponse = sendText(
                    client,
                    HttpRequest.newBuilder(base.resolve("/__lab/config")).GET().build());
            assertEquals(200, configResponse.statusCode());
            assertTrue(configResponse.body().contains("\"profileId\":\"N0\""));

            HttpResponse<byte[]> full = sendBytes(
                    client,
                    HttpRequest.newBuilder(base.resolve("/fixtures/F0/sample.bin")).GET().build());
            assertEquals(200, full.statusCode());
            assertArrayEquals(source, full.body());
            assertEquals("bytes", full.headers().firstValue("Accept-Ranges").orElseThrow());

            HttpResponse<byte[]> partial = sendBytes(
                    client,
                    HttpRequest.newBuilder(base.resolve("/fixtures/F0/sample.bin"))
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
                    HttpRequest.newBuilder(base.resolve("/fixtures/F0/sample.bin"))
                            .header("Range", "bytes=-4")
                            .GET()
                            .build());
            assertEquals(206, suffix.statusCode());
            assertArrayEquals(java.util.Arrays.copyOfRange(source, 252, 256), suffix.body());

            HttpResponse<byte[]> unsatisfiable = sendBytes(
                    client,
                    HttpRequest.newBuilder(base.resolve("/fixtures/F0/sample.bin"))
                            .header("Range", "bytes=256-300")
                            .GET()
                            .build());
            assertEquals(416, unsatisfiable.statusCode());
            assertEquals(
                    "bytes */256",
                    unsatisfiable.headers().firstValue("Content-Range").orElseThrow());

            HttpResponse<byte[]> malformed = sendBytes(
                    client,
                    HttpRequest.newBuilder(base.resolve("/fixtures/F0/sample.bin"))
                            .header("Range", "bytes=bad")
                            .GET()
                            .build());
            assertEquals(200, malformed.statusCode());
            assertArrayEquals(source, malformed.body());

            HttpResponse<byte[]> multiple = sendBytes(
                    client,
                    HttpRequest.newBuilder(base.resolve("/fixtures/F0/sample.bin"))
                            .header("Range", "bytes=0-1,4-5")
                            .GET()
                            .build());
            assertEquals(200, multiple.statusCode());
            assertArrayEquals(source, multiple.body());

            HttpResponse<byte[]> head = sendBytes(
                    client,
                    HttpRequest.newBuilder(base.resolve("/fixtures/F0/sample.bin"))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .build());
            assertEquals(200, head.statusCode());
            assertEquals(0, head.body().length);
            assertEquals("256", head.headers().firstValue("Content-Length").orElseThrow());

            HttpResponse<String> missing = sendText(
                    client,
                    HttpRequest.newBuilder(base.resolve("/fixtures/F0/missing.bin")).GET().build());
            assertEquals(404, missing.statusCode());

            HttpResponse<String> traversal = sendText(
                    client,
                    HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + server.port()
                                            + "/fixtures/F0/%252e%252e/secret"))
                            .GET()
                            .build());
            assertEquals(404, traversal.statusCode());

            HttpResponse<String> methodNotAllowed = sendText(
                    client,
                    HttpRequest.newBuilder(base.resolve("/fixtures/F0/sample.bin"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build());
            assertEquals(405, methodNotAllowed.statusCode());
            assertEquals("GET, HEAD", methodNotAllowed.headers().firstValue("Allow").orElseThrow());
        }

        List<String> traceLines = Files.readAllLines(tracePath);
        assertEquals(12, traceLines.size());
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
