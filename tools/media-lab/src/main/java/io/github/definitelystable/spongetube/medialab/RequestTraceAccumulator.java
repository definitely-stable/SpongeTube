package io.github.definitelystable.spongetube.medialab;

final class RequestTraceAccumulator {

    private final MediaLabConfig config;
    private final ResolvedScenario scenario;
    private final long requestId;
    private final String plane;
    private final long handlerStartedAtMonotonicNs;
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
    long noProgressWaitNs;
    TraceOutcome outcome = TraceOutcome.SERVER_IO_ERROR;

    RequestTraceAccumulator(
            MediaLabConfig config,
            ResolvedScenario scenario,
            long requestId,
            String plane,
            long handlerStartedAtMonotonicNs,
            String method,
            String path,
            String rangeHeader) {
        this.config = config;
        this.scenario = scenario;
        this.requestId = requestId;
        this.plane = plane;
        this.handlerStartedAtMonotonicNs = handlerStartedAtMonotonicNs;
        this.method = method;
        this.path = path;
        this.rangeHeader = rangeHeader;
    }

    long requestId() {
        return requestId;
    }

    void markFirstBodyWrite(long atMonotonicNs) {
        if (firstBodyWriteAtMonotonicNs == null) {
            firstBodyWriteAtMonotonicNs = atMonotonicNs;
        }
    }

    void addNoProgressWaitNanos(long waitNanos) {
        noProgressWaitNs = Math.addExact(noProgressWaitNs, Math.max(0L, waitNanos));
    }

    RequestTrace complete(long completedAtMonotonicNs) {
        Long firstWriteDelayMs = firstBodyWriteAtMonotonicNs == null
                ? null
                : Math.max(0L, (firstBodyWriteAtMonotonicNs - handlerStartedAtMonotonicNs) / 1_000_000L);
        long handlerDurationMs =
                Math.max(0L, (completedAtMonotonicNs - handlerStartedAtMonotonicNs) / 1_000_000L);

        return new RequestTrace(
                2,
                config.sessionId(),
                requestId,
                plane,
                fixtureId,
                resourceId,
                config.profile().name(),
                scenario.scenarioId(),
                scenario.scenarioHash(),
                method,
                path,
                rangeHeader,
                resolvedRangeStart,
                resolvedRangeEndExclusive,
                status,
                plannedResponseBytes,
                bodyBytesWritten,
                handlerStartedAtMonotonicNs,
                firstBodyWriteAtMonotonicNs,
                completedAtMonotonicNs,
                firstWriteDelayMs,
                handlerDurationMs,
                scenario.aggregateRateBps(),
                noProgressWaitNs / 1_000_000L,
                outcome);
    }
}
