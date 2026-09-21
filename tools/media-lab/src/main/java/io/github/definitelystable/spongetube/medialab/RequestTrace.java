package io.github.definitelystable.spongetube.medialab;

record RequestTrace(
        int schemaVersion,
        String sessionId,
        long requestId,
        String fixtureId,
        String resourceId,
        String profileId,
        String scenarioId,
        String scenarioHash,
        String method,
        String path,
        String rangeHeader,
        Long resolvedRangeStart,
        Long resolvedRangeEndExclusive,
        int status,
        long plannedResponseBytes,
        long bodyBytesWritten,
        long handlerStartedAtMonotonicNs,
        Long firstBodyWriteAtMonotonicNs,
        long completedAtMonotonicNs,
        Long serverFirstBodyWriteDelayMs,
        long handlerDurationMs,
        Long configuredRateBps,
        long noProgressWaitMs,
        TraceOutcome outcome) {

    String toJsonLine() {
        StringBuilder json = new StringBuilder(640);
        json.append('{');
        field(json, "schemaVersion", schemaVersion).append(',');
        field(json, "sessionId", sessionId).append(',');
        field(json, "requestId", requestId).append(',');
        nullableField(json, "fixtureId", fixtureId).append(',');
        nullableField(json, "resourceId", resourceId).append(',');
        field(json, "profileId", profileId).append(',');
        field(json, "scenarioId", scenarioId).append(',');
        field(json, "scenarioHash", scenarioHash).append(',');
        field(json, "method", method).append(',');
        field(json, "path", path).append(',');
        nullableField(json, "rangeHeader", rangeHeader).append(',');
        nullableField(json, "resolvedRangeStart", resolvedRangeStart).append(',');
        nullableField(json, "resolvedRangeEndExclusive", resolvedRangeEndExclusive).append(',');
        field(json, "status", status).append(',');
        field(json, "plannedResponseBytes", plannedResponseBytes).append(',');
        field(json, "bodyBytesWritten", bodyBytesWritten).append(',');
        field(json, "handlerStartedAtMonotonicNs", handlerStartedAtMonotonicNs).append(',');
        nullableField(json, "firstBodyWriteAtMonotonicNs", firstBodyWriteAtMonotonicNs).append(',');
        field(json, "completedAtMonotonicNs", completedAtMonotonicNs).append(',');
        nullableField(json, "serverFirstBodyWriteDelayMs", serverFirstBodyWriteDelayMs).append(',');
        field(json, "handlerDurationMs", handlerDurationMs).append(',');
        nullableField(json, "configuredRateBps", configuredRateBps).append(',');
        field(json, "noProgressWaitMs", noProgressWaitMs).append(',');
        field(json, "outcome", outcome.name());
        return json.append('}').toString();
    }

    private static StringBuilder field(StringBuilder json, String key, String value) {
        return json.append(Json.quote(key)).append(':').append(Json.quote(value));
    }

    private static StringBuilder field(StringBuilder json, String key, long value) {
        return json.append(Json.quote(key)).append(':').append(value);
    }

    private static StringBuilder field(StringBuilder json, String key, int value) {
        return json.append(Json.quote(key)).append(':').append(value);
    }

    private static StringBuilder nullableField(StringBuilder json, String key, String value) {
        return json.append(Json.quote(key)).append(':')
                .append(value == null ? "null" : Json.quote(value));
    }

    private static StringBuilder nullableField(StringBuilder json, String key, Long value) {
        return json.append(Json.quote(key)).append(':')
                .append(value == null ? "null" : value);
    }
}
