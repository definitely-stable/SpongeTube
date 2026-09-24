package io.github.definitelystable.spongetube.medialab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManualBodyProgressGateTest {

    @TempDir
    Path temp;

    @Test
    void closeBlocksBodyProgressUntilMatchingOpen() throws Exception {
        Path trace = temp.resolve("gate.jsonl");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (JsonLineTraceWriter writer = new JsonLineTraceWriter(trace)) {
            ManualBodyProgressGate gate =
                    new ManualBodyProgressGate(SystemMonotonicClock.INSTANCE, writer);

            String closed = gate.close("g1-close");
            assertTrue(closed.contains("\"state\":\"CLOSED\""));
            assertTrue(closed.contains("\"generation\":1"));

            Future<Long> wait = executor.submit(() -> gate.awaitOpen(41L));
            for (int attempt = 0; attempt < 100; attempt++) {
                if (Files.readString(trace).contains("\"REQUEST_BLOCKED\"")) {
                    break;
                }
                Thread.sleep(5);
            }
            assertTrue(Files.readString(trace).contains("\"REQUEST_BLOCKED\""));

            String opened = gate.open("g1-open");
            assertTrue(opened.contains("\"state\":\"OPEN\""));
            assertTrue(wait.get() >= 0L);
        } finally {
            executor.shutdownNow();
        }

        String events = Files.readString(trace);
        assertTrue(events.contains("\"GATE_CLOSE_ACCEPTED\""));
        assertTrue(events.contains("\"REQUEST_BLOCKED\""));
        assertTrue(events.contains("\"GATE_OPEN_ACCEPTED\""));
        assertTrue(events.contains("\"REQUEST_RELEASED\""));
        assertTrue(events.contains("\"requestId\":41"));
    }

    @Test
    void repeatedCloseIsIdempotentForGeneration() throws Exception {
        Path trace = temp.resolve("idempotent.jsonl");
        try (JsonLineTraceWriter writer = new JsonLineTraceWriter(trace)) {
            ManualBodyProgressGate gate =
                    new ManualBodyProgressGate(SystemMonotonicClock.INSTANCE, writer);
            gate.close("same-close");
            String state = gate.close("second-close");
            assertTrue(state.contains("\"generation\":1"));
            gate.open("open");
            assertEquals(true, gate.stateJson().contains("\"state\":\"OPEN\""));
        }
    }
}
