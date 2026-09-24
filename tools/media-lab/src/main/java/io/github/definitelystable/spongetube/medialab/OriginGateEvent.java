package io.github.definitelystable.spongetube.medialab;

record OriginGateEvent(
        int schemaVersion,
        long serverEventSequence,
        long serverMonotonicNs,
        String event,
        long gateGeneration,
        String commandId,
        Long requestId) {

    String toJsonLine() {
        return "{"
                + Json.quote("schemaVersion") + ":" + schemaVersion + ","
                + Json.quote("serverEventSequence") + ":" + serverEventSequence + ","
                + Json.quote("serverMonotonicNs") + ":" + serverMonotonicNs + ","
                + Json.quote("event") + ":" + Json.quote(event) + ","
                + Json.quote("gateGeneration") + ":" + gateGeneration + ","
                + Json.quote("commandId") + ":"
                + (commandId == null ? "null" : Json.quote(commandId)) + ","
                + Json.quote("requestId") + ":"
                + (requestId == null ? "null" : requestId)
                + "}";
    }
}
