package io.github.definitelystable.spongetube.medialab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaLabImpairmentIntegrationTest {

    @TempDir
    Path temp;

    @Test
    void n1PacesRealSocketAndEmitsCalibrationEvidence() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("n1-fixtures"));
        Path fixture = Files.createDirectory(fixtureRoot.resolve("F0"));
        byte[] source = new byte[16 * 1024];
        Files.write(fixture.resolve("sample.bin"), source);

        MediaLabConfig config = new MediaLabConfig(
                fixtureRoot,
                temp.resolve("n1-trace.jsonl"),
                "n1-socket",
                MediaLabProfile.N1,
                0,
                2,
                0,
                1,
                1_000_000L,
                null,
                4096);

        // 4 KiB every ~20 ms, plus 30 ms first-body delay.
        ResolvedScenario scenario = ResolvedScenario.testScenario(
                "n1-socket-test",
                MediaLabProfile.N1,
                30,
                1_638_400L,
                4096,
                null,
                null);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        long started = System.nanoTime();
        try (MediaLabServer server = MediaLabServer.create(
                config,
                scenario,
                SystemMonotonicClock.INSTANCE,
                new SystemSleeper(SystemMonotonicClock.INSTANCE))) {
            server.start();

            URI uri = URI.create(
                    "http://127.0.0.1:" + server.dataPort() + "/fixtures/F0/sample.bin");
            HttpResponse<byte[]> response = client.send(
                    HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(200, response.statusCode());
            assertArrayEquals(source, response.body());
            assertEquals(
                    scenario.scenarioHash(),
                    response.headers().firstValue("X-Sponge-Lab-Scenario").orElseThrow());
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        // Deliberately loose: prove real waiting exists without turning CI scheduler jitter
        // into a benchmark threshold.
        assertTrue(elapsedMs >= 40, "expected real impairment wait, elapsed=" + elapsedMs);

        String calibration = Files.readString(config.calibrationPath());
        assertTrue(calibration.contains("\"configuredRateBps\":1638400"));
        assertTrue(calibration.contains("\"observedRateBps\":"));
        assertTrue(calibration.contains("\"configuredFirstBodyDelayMs\":30"));

        String requestTrace = Files.readString(config.tracePath());
        assertTrue(requestTrace.contains("\"configuredRateBps\":1638400"));
        assertTrue(requestTrace.contains("\"scenarioHash\":\"" + scenario.scenarioHash() + "\""));
    }

    @Test
    void n4BlocksMediaWhileIndependentControlPlaneRemainsResponsive() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("n4-fixtures"));
        Path fixture = Files.createDirectory(fixtureRoot.resolve("F0"));
        byte[] source = new byte[64 * 1024];
        Files.write(fixture.resolve("sample.bin"), source);

        MediaLabConfig config = new MediaLabConfig(
                fixtureRoot,
                temp.resolve("n4-trace.jsonl"),
                "n4-socket",
                MediaLabProfile.N4,
                0,
                2,
                0,
                1,
                null,
                0L,
                1024);

        ResolvedScenario scenario = ResolvedScenario.testScenario(
                "n4-socket-test",
                MediaLabProfile.N4,
                0,
                null,
                1024,
                0L,
                500L);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        try (MediaLabServer server = MediaLabServer.create(
                config,
                scenario,
                SystemMonotonicClock.INSTANCE,
                new SystemSleeper(SystemMonotonicClock.INSTANCE))) {
            server.start();

            URI dataUri = URI.create(
                    "http://127.0.0.1:" + server.dataPort() + "/fixtures/F0/sample.bin");
            URI healthUri = URI.create(
                    "http://127.0.0.1:" + server.controlPort() + "/__lab/health");

            HttpResponse<InputStream> media = client.send(
                    HttpRequest.newBuilder(dataUri).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, media.statusCode());

            try (InputStream body = media.body()) {
                byte[] firstQuantum = body.readNBytes(1024);
                assertEquals(1024, firstQuantum.length);

                awaitEvent(config.sessionTracePath(), "NO_PROGRESS_WINDOW_ENTERED");

                HttpResponse<String> health = client.send(
                        HttpRequest.newBuilder(healthUri).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, health.statusCode());

                int immediatelyAvailable = body.available();
                byte[] postGateDrain = immediatelyAvailable == 0
                        ? new byte[0]
                        : body.readNBytes(immediatelyAvailable);

                // Any bytes visible here can only be data already written before the server-side
                // gate took effect. The bounded/flush-per-quantum N4 path keeps that leakage
                // within one configured write quantum. Record the measured value as test evidence.
                assertTrue(
                        postGateDrain.length <= scenario.writeQuantumBytes(),
                        "post-gate drain exceeded one write quantum: " + postGateDrain.length);
                System.out.println(
                        "media-lab postGateDrainBytes=" + postGateDrain.length
                                + " writeQuantumBytes=" + scenario.writeQuantumBytes());

                ByteArrayOutputStream received = new ByteArrayOutputStream(source.length);
                received.write(firstQuantum);
                received.write(postGateDrain);
                body.transferTo(received);

                assertArrayEquals(source, received.toByteArray());
            }
        }

        String events = Files.readString(config.sessionTracePath());
        assertTrue(events.contains("\"NO_PROGRESS_WINDOW_SCHEDULED\""));
        assertTrue(events.contains("\"NO_PROGRESS_WINDOW_ENTERED\""));
        assertTrue(events.contains("\"NO_PROGRESS_WINDOW_EXITED\""));

        String requestTrace = Files.readString(config.tracePath());
        assertTrue(requestTrace.contains("\"noProgressWaitMs\":"));
        assertTrue(requestTrace.contains("\"scenarioHash\":\"" + scenario.scenarioHash() + "\""));

        String calibration = Files.readString(config.calibrationPath());
        assertTrue(calibration.contains("\"configuredNoProgressDurationMs\":500"));
        assertTrue(calibration.contains("\"observedNoProgressDurationMs\":"));
        assertTrue(calibration.contains(
                "\"postGateDrainMeasurement\":\"client-side real-socket evidence only\""));
    }

    private static void awaitEvent(Path eventTrace, String event) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (Files.exists(eventTrace) && Files.readString(eventTrace).contains(event)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for session event: " + event);
    }
}
