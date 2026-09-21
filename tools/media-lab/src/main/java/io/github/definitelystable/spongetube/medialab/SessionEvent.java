package io.github.definitelystable.spongetube.medialab;

record SessionEvent(
        int schemaVersion,
        String sessionId,
        String scenarioId,
        String scenarioHash,
        String event,
        long atMonotonicNs,
        Long configuredRateBps,
        Long noProgressStartAfterMs,
        Long noProgressDurationMs) {

    String toJsonLine() {
        return "{"
                + Json.quote("schemaVersion") + ":" + schemaVersion + ","
                + Json.quote("sessionId") + ":" + Json.quote(sessionId) + ","
                + Json.quote("scenarioId") + ":" + Json.quote(scenarioId) + ","
                + Json.quote("scenarioHash") + ":" + Json.quote(scenarioHash) + ","
                + Json.quote("event") + ":" + Json.quote(event) + ","
                + Json.quote("atMonotonicNs") + ":" + atMonotonicNs + ","
                + Json.quote("configuredRateBps") + ":" + nullable(configuredRateBps) + ","
                + Json.quote("noProgressStartAfterMs") + ":" + nullable(noProgressStartAfterMs) + ","
                + Json.quote("noProgressDurationMs") + ":" + nullable(noProgressDurationMs)
                + "}";
    }

    private static String nullable(Long value) {
        return value == null ? "null" : Long.toString(value);
    }
}
