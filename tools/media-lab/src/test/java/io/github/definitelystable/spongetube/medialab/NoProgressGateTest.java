package io.github.definitelystable.spongetube.medialab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NoProgressGateTest {

    @TempDir
    Path temp;

    @Test
    void blocksInsideSharedWindowWithoutRealSleep() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("fixtures"));
        MediaLabConfig config = new MediaLabConfig(
                fixtureRoot,
                temp.resolve("trace.jsonl"),
                "gate",
                MediaLabProfile.N4,
                0,
                2,
                0,
                1,
                null,
                100L,
                8192);
        ResolvedScenario scenario = ResolvedScenario.testScenario(
                "gate-test",
                MediaLabProfile.N4,
                0,
                null,
                8192,
                100L,
                200L);

        FakeTime time = new FakeTime();
        SessionCalibration calibration = new SessionCalibration();

        try (JsonLineTraceWriter writer = new JsonLineTraceWriter(config.sessionTracePath())) {
            SessionEventRecorder events =
                    new SessionEventRecorder(config, scenario, time, writer);
            events.start();

            NoProgressGate gate =
                    new NoProgressGate(scenario, time, time, events, calibration);

            gate.onProgress(time.nowNanos());

            assertEquals(0L, gate.awaitOpen());

            time.advanceMillis(100);
            assertEquals(200_000_000L, gate.awaitOpen());
            assertEquals(300_000_000L, time.nowNanos());

            assertEquals(0L, gate.awaitOpen());
            events.complete();
        }

        String events = Files.readString(config.sessionTracePath());
        assertTrue(events.contains("\"NO_PROGRESS_WINDOW_ENTERED\""));
        assertTrue(events.contains("\"NO_PROGRESS_WINDOW_EXITED\""));
    }
}
