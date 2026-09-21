package io.github.definitelystable.spongetube.medialab;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

final class SessionEventRecorder {

    private final MediaLabConfig config;
    private final ResolvedScenario scenario;
    private final MonotonicClock clock;
    private final JsonLineTraceWriter writer;

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean firstProgress = new AtomicBoolean();
    private final AtomicBoolean noProgressEntered = new AtomicBoolean();
    private final AtomicBoolean noProgressExited = new AtomicBoolean();
    private final AtomicBoolean completed = new AtomicBoolean();

    SessionEventRecorder(
            MediaLabConfig config,
            ResolvedScenario scenario,
            MonotonicClock clock,
            JsonLineTraceWriter writer) {
        this.config = config;
        this.scenario = scenario;
        this.clock = clock;
        this.writer = writer;
    }

    void start() throws IOException {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        append("SESSION_STARTED", clock.nowNanos());

        if (scenario.aggregateRateBps() != null) {
            append("N1_RATE_RESOLVED", clock.nowNanos());
        }

        if (scenario.noProgressDurationMs() != null) {
            append("NO_PROGRESS_WINDOW_SCHEDULED", clock.nowNanos());
        }
    }

    void firstMediaProgress(long atNanos) throws IOException {
        if (firstProgress.compareAndSet(false, true)) {
            append("FIRST_MEDIA_PROGRESS", atNanos);
        }
    }

    void noProgressEntered(long atNanos) throws IOException {
        if (noProgressEntered.compareAndSet(false, true)) {
            append("NO_PROGRESS_WINDOW_ENTERED", atNanos);
        }
    }

    void noProgressExited(long atNanos) throws IOException {
        if (noProgressExited.compareAndSet(false, true)) {
            append("NO_PROGRESS_WINDOW_EXITED", atNanos);
        }
    }

    void complete() throws IOException {
        if (started.get() && completed.compareAndSet(false, true)) {
            append("SESSION_COMPLETED", clock.nowNanos());
        }
    }

    private void append(String event, long atNanos) throws IOException {
        writer.append(new SessionEvent(
                1,
                config.sessionId(),
                scenario.scenarioId(),
                scenario.scenarioHash(),
                event,
                atNanos,
                scenario.referencePlaybackBitrateBps(),
                scenario.aggregateRateRatio(),
                scenario.aggregateRateBps(),
                scenario.noProgressStartAfterMs(),
                scenario.noProgressDurationMs()));
    }
}
