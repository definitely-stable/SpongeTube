package io.github.definitelystable.spongetube.medialab;

final class RequestTraceAccumulator {

    private final MediaLabConfig config;
    private final long requestId;
    private final long acceptedAtMonotonicNs;
    private final String method;
    private final String path;
    private final String rangeHeader;

    String fixtureId;
    String resourceId;
    Long resolvedRangeStart;
    Long resolvedRangeEndExclusive;
    int status = 500;
    long plannedResponseBytes;
    long bodyBytesWritten;
    Long firstBodyWriteAtMonotonicNs;
    TraceOutcome outcome = TraceOutcome.SERVER_IO_ERROR;

    RequestTraceAccumulator(
            MediaLabConfig config,
            long requestId,
            long acceptedAtMonotonicNs,
            String method,
            String path,
            String rangeHeader) {
        this.config = config;
        this.requestId = requestId;
        this.acceptedAtMonotonicNs = acceptedAtMonotonicNs;
        this.method = method;
        this.path = path;
        this.rangeHeader = rangeHeader;
    }

    void markFirstBodyWrite() {
        if (firstBodyWriteAtMonotonicNs == null) {
            firstBodyWriteAtMonotonicNs = System.nanoTime();
        }
    }

    RequestTrace complete(long completedAtMonotonicNs) {
        Long firstWriteDelayMs = firstBodyWriteAtMonotonicNs == null
                ? null
                : Math.max(0L, (firstBodyWriteAtMonotonicNs - acceptedAtMonotonicNs) / 1_000_000L);
        long handlerDurationMs =
                Math.max(0L, (completedAtMonotonicNs - acceptedAtMonotonicNs) / 1_000_000L);

        return new RequestTrace(
                1,
                config.sessionId(),
                requestId,
                fixtureId,
                resourceId,
                config.profile().name(),
                method,
                path,
                rangeHeader,
                resolvedRangeStart,
                resolvedRangeEndExclusive,
                status,
                plannedResponseBytes,
                bodyBytesWritten,
                acceptedAtMonotonicNs,
                firstBodyWriteAtMonotonicNs,
                completedAtMonotonicNs,
                firstWriteDelayMs,
                handlerDurationMs,
                null,
                0,
                outcome);
    }
}
