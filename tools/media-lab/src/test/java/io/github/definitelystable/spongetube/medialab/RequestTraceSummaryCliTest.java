package io.github.definitelystable.spongetube.medialab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RequestTraceSummaryCliTest {

    @TempDir
    Path temp;

    @Test
    void summarizesSchemaV2JsonlAndExcludesControlPlane() throws Exception {
        Path trace = temp.resolve("requests.jsonl");
        Path output = temp.resolve("network-summary.json");

        Files.writeString(
                trace,
                dataTrace(1, "scenario-hash", 0, 100, 100).toJsonLine()
                        + System.lineSeparator()
                        + dataTrace(2, "scenario-hash", 50, 150, 100).toJsonLine()
                        + System.lineSeparator()
                        + controlTrace(3).toJsonLine()
                        + System.lineSeparator(),
                StandardCharsets.UTF_8);

        RequestTraceSummaryCli.summarize(trace, output);

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) MiniJsonParser.parse(
                Files.readString(output, StandardCharsets.UTF_8));

        assertEquals(1L, summary.get("schemaVersion"));
        assertEquals(2L, summary.get("requestTraceSchemaVersion"));
        assertEquals("session-1", summary.get("sessionId"));
        assertEquals("N0", summary.get("scenarioId"));
        assertEquals("scenario-hash", summary.get("scenarioHash"));
        assertEquals(2L, summary.get("requestCount"));
        assertEquals(200L, summary.get("networkBytes"));
        assertEquals(150L, summary.get("uniqueRangeBytes"));
        assertEquals(50L, summary.get("duplicateRangeBytes"));
        assertEquals(0L, summary.get("httpErrorCount"));
    }


    @Test
    void summarizesZeroNetworkWarmCaseWithExplicitIdentity() throws Exception {
        Path trace = temp.resolve("warm.jsonl");
        Path output = temp.resolve("warm-summary.json");

        Files.writeString(
                trace,
                controlTrace(1).toJsonLine() + System.lineSeparator(),
                StandardCharsets.UTF_8);

        RequestTraceSummaryCli.summarize(
                trace,
                output,
                "session-1",
                "N0",
                "scenario-hash");

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) MiniJsonParser.parse(
                Files.readString(output, StandardCharsets.UTF_8));

        assertEquals("session-1", summary.get("sessionId"));
        assertEquals("N0", summary.get("scenarioId"));
        assertEquals("scenario-hash", summary.get("scenarioHash"));
        assertEquals(0L, summary.get("requestCount"));
        assertEquals(0L, summary.get("networkBytes"));
        assertEquals(0L, summary.get("uniqueRangeBytes"));
        assertEquals(0L, summary.get("duplicateRangeBytes"));
        assertEquals(0L, summary.get("httpErrorCount"));
    }

    @Test
    void rejectsExplicitIdentityThatDisagreesWithObservedTrace() throws Exception {
        Path trace = temp.resolve("identity-mismatch.jsonl");
        Path output = temp.resolve("identity-mismatch-summary.json");

        Files.writeString(
                trace,
                dataTrace(1, "scenario-hash", 0, 100, 100).toJsonLine()
                        + System.lineSeparator(),
                StandardCharsets.UTF_8);

        assertThrows(
                IllegalArgumentException.class,
                () -> RequestTraceSummaryCli.summarize(
                        trace,
                        output,
                        "different-session",
                        "N0",
                        "scenario-hash"));
    }

    @Test
    void rejectsMixedScenarioIdentity() throws Exception {
        Path trace = temp.resolve("mixed.jsonl");
        Path output = temp.resolve("summary.json");

        Files.writeString(
                trace,
                dataTrace(1, "hash-a", 0, 100, 100).toJsonLine()
                        + System.lineSeparator()
                        + dataTrace(2, "hash-b", 100, 200, 100).toJsonLine()
                        + System.lineSeparator(),
                StandardCharsets.UTF_8);

        assertThrows(
                IllegalArgumentException.class,
                () -> RequestTraceSummaryCli.summarize(trace, output));
    }

    private static RequestTrace dataTrace(
            long requestId,
            String scenarioHash,
            long start,
            long end,
            long bodyBytes) {
        return new RequestTrace(
                2,
                "session-1",
                requestId,
                "data",
                "F1",
                "video",
                "N0",
                "N0",
                scenarioHash,
                "GET",
                "/fixtures/F1/video.m4s",
                null,
                start,
                end,
                206,
                end - start,
                bodyBytes,
                1,
                2L,
                3,
                0L,
                0,
                null,
                0,
                TraceOutcome.SUCCESS);
    }

    private static RequestTrace controlTrace(long requestId) {
        return new RequestTrace(
                2,
                "session-1",
                requestId,
                "control",
                null,
                null,
                "N0",
                "N0",
                "scenario-hash",
                "GET",
                "/__lab/health",
                null,
                null,
                null,
                200,
                2,
                2,
                1,
                2L,
                3,
                0L,
                0,
                null,
                0,
                TraceOutcome.SUCCESS);
    }
}
