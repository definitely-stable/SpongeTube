package io.github.definitelystable.spongetube.medialab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RequestTraceSummaryCli {

    private RequestTraceSummaryCli() {
    }

    static void run(String[] args) throws IOException {
        Map<String, String> options = CliOptionParser.parse(
                args,
                1,
                Set.of("trace", "output"));

        Path tracePath = Path.of(CliOptionParser.required(options, "trace"));
        Path outputPath = Path.of(CliOptionParser.required(options, "output"));

        summarize(tracePath, outputPath);
    }

    static void summarize(
            Path tracePath,
            Path outputPath) throws IOException {
        List<RequestTrace> traces = readTrace(tracePath);
        MediaNetworkSummary summary = RequestTraceSummary.summarize(traces);

        Set<String> sessionIds = new LinkedHashSet<>();
        Set<String> scenarioIds = new LinkedHashSet<>();
        Set<String> scenarioHashes = new LinkedHashSet<>();

        for (RequestTrace trace : traces) {
            if (!"data".equals(trace.plane())
                    || trace.path() == null
                    || !trace.path().startsWith("/fixtures/")) {
                continue;
            }

            sessionIds.add(trace.sessionId());
            scenarioIds.add(trace.scenarioId());
            scenarioHashes.add(trace.scenarioHash());
        }

        String sessionId = single("sessionId", sessionIds);
        String scenarioId = single("scenarioId", scenarioIds);
        String scenarioHash = single("scenarioHash", scenarioHashes);

        Path parent = outputPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String json = "{\n"
                + "  \"schemaVersion\": 1,\n"
                + "  \"requestTraceSchemaVersion\": 2,\n"
                + "  \"sessionId\": " + quote(sessionId) + ",\n"
                + "  \"scenarioId\": " + quote(scenarioId) + ",\n"
                + "  \"scenarioHash\": " + quote(scenarioHash) + ",\n"
                + "  \"requestCount\": " + summary.requestCount() + ",\n"
                + "  \"networkBytes\": " + summary.networkBytes() + ",\n"
                + "  \"uniqueRangeBytes\": " + summary.uniqueRangeBytes() + ",\n"
                + "  \"duplicateRangeBytes\": " + summary.duplicateRangeBytes() + ",\n"
                + "  \"httpErrorCount\": " + summary.httpErrorCount() + "\n"
                + "}\n";

        Files.writeString(
                outputPath,
                json,
                StandardCharsets.UTF_8);
    }

    private static List<RequestTrace> readTrace(
            Path tracePath) throws IOException {
        if (!Files.isRegularFile(tracePath)) {
            throw new IllegalArgumentException(
                    "Request trace does not exist: " + tracePath);
        }

        List<RequestTrace> traces = new ArrayList<>();
        int lineNumber = 0;

        for (String line : Files.readAllLines(tracePath, StandardCharsets.UTF_8)) {
            lineNumber += 1;
            if (line.isBlank()) {
                continue;
            }

            try {
                traces.add(parseTrace(line));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException(
                        "Invalid request trace line "
                                + lineNumber
                                + ": "
                                + invalid.getMessage(),
                        invalid);
            }
        }

        if (traces.isEmpty()) {
            throw new IllegalArgumentException(
                    "Request trace is empty: " + tracePath);
        }

        return traces;
    }

    @SuppressWarnings("unchecked")
    private static RequestTrace parseTrace(String line) {
        Object parsed = MiniJsonParser.parse(line);
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(
                    "Request trace row must be a JSON object");
        }

        Map<String, Object> row = (Map<String, Object>) raw;

        int schemaVersion = integer(row, "schemaVersion");
        if (schemaVersion != 2) {
            throw new IllegalArgumentException(
                    "Unsupported request trace schemaVersion: "
                            + schemaVersion);
        }

        return new RequestTrace(
                schemaVersion,
                string(row, "sessionId"),
                longValue(row, "requestId"),
                string(row, "plane"),
                nullableString(row, "fixtureId"),
                nullableString(row, "resourceId"),
                string(row, "profileId"),
                string(row, "scenarioId"),
                string(row, "scenarioHash"),
                string(row, "method"),
                string(row, "path"),
                nullableString(row, "rangeHeader"),
                nullableLong(row, "resolvedRangeStart"),
                nullableLong(row, "resolvedRangeEndExclusive"),
                integer(row, "status"),
                longValue(row, "plannedResponseBytes"),
                longValue(row, "bodyBytesWritten"),
                longValue(row, "handlerStartedAtMonotonicNs"),
                nullableLong(row, "firstBodyWriteAtMonotonicNs"),
                longValue(row, "completedAtMonotonicNs"),
                nullableLong(row, "serverFirstBodyWriteDelayMs"),
                longValue(row, "handlerDurationMs"),
                nullableLong(row, "configuredRateBps"),
                longValue(row, "noProgressWaitMs"),
                TraceOutcome.valueOf(string(row, "outcome")));
    }

    private static String single(
            String name,
            Set<String> values) {
        if (values.size() != 1) {
            throw new IllegalArgumentException(
                    "Expected exactly one "
                            + name
                            + " in data-plane fixture trace, got "
                            + values);
        }
        return values.iterator().next();
    }

    private static String string(
            Map<String, Object> row,
            String key) {
        Object value = row.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(
                    key + " must be a non-blank string");
        }
        return text;
    }

    private static String nullableString(
            Map<String, Object> row,
            String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(
                    key + " must be string or null");
        }
        return text;
    }

    private static long longValue(
            Map<String, Object> row,
            String key) {
        Object value = row.get(key);
        if (!(value instanceof Long number)) {
            throw new IllegalArgumentException(
                    key + " must be an integer");
        }
        return number;
    }

    private static Long nullableLong(
            Map<String, Object> row,
            String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Long number)) {
            throw new IllegalArgumentException(
                    key + " must be integer or null");
        }
        return number;
    }

    private static int integer(
            Map<String, Object> row,
            String key) {
        long value = longValue(row, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    key + " is outside Int range");
        }
        return (int) value;
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        out.append('"');
        return out.toString();
    }
}
