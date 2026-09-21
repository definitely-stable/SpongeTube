package io.github.definitelystable.spongetube.medialab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonLineTraceWriterTest {

    @TempDir
    Path temp;

    @Test
    void escapesStringsAndWritesOneCompleteLine() throws Exception {
        Path trace = temp.resolve("trace.jsonl");

        try (JsonLineTraceWriter writer = new JsonLineTraceWriter(trace)) {
            writer.append(trace(1, "/fixtures/F0/a\"b\\c.bin"));
        }

        List<String> lines = Files.readAllLines(trace);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).startsWith("{"));
        assertTrue(lines.get(0).endsWith("}"));
        assertTrue(lines.get(0).contains("a\\\"b\\\\c.bin"));
        assertTrue(lines.get(0).contains("\"plane\":\"data\""));
    }

    @Test
    void refusesToAppendToExistingTraceFromAnotherSession() throws Exception {
        Path trace = Files.writeString(temp.resolve("trace.jsonl"), "existing");

        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> new JsonLineTraceWriter(trace));
    }

    @Test
    void concurrentAppendsRemainLineAtomic() throws Exception {
        Path trace = temp.resolve("trace.jsonl");
        var executor = Executors.newFixedThreadPool(4);

        try (JsonLineTraceWriter writer = new JsonLineTraceWriter(trace)) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                long id = i;
                tasks.add(() -> {
                    writer.append(trace(id, "/fixtures/F0/" + id + ".bin"));
                    return null;
                });
            }
            executor.invokeAll(tasks).forEach(future -> {
                try {
                    future.get();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
        } finally {
            executor.shutdownNow();
        }

        List<String> lines = Files.readAllLines(trace);
        assertEquals(100, lines.size());
        assertTrue(lines.stream().allMatch(line -> line.startsWith("{") && line.endsWith("}")));
    }

    private static RequestTrace trace(long requestId, String path) {
        return new RequestTrace(
                2,
                "session",
                requestId,
                "data",
                "F0",
                "sample.bin",
                "N0",
                "N0",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "GET",
                path,
                null,
                null,
                null,
                200,
                3,
                3,
                1,
                2L,
                3,
                1L,
                2,
                null,
                0,
                TraceOutcome.SUCCESS);
    }
}
