package io.github.definitelystable.spongetube.medialab;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

final class NoProgressGate {

    private static final long UNSET = Long.MIN_VALUE;

    private final Long startAfterNs;
    private final Long durationNs;
    private final MonotonicClock clock;
    private final Sleeper sleeper;
    private final SessionEventRecorder events;
    private final SessionCalibration calibration;
    private final AtomicLong firstProgressAtNs = new AtomicLong(UNSET);

    NoProgressGate(
            ResolvedScenario scenario,
            MonotonicClock clock,
            Sleeper sleeper,
            SessionEventRecorder events,
            SessionCalibration calibration) {
        this.startAfterNs = scenario.noProgressStartAfterMs() == null
                ? null
                : Math.multiplyExact(scenario.noProgressStartAfterMs(), 1_000_000L);
        this.durationNs = scenario.noProgressDurationMs() == null
                ? null
                : Math.multiplyExact(scenario.noProgressDurationMs(), 1_000_000L);
        this.clock = clock;
        this.sleeper = sleeper;
        this.events = events;
        this.calibration = calibration;
    }

    long awaitOpen() throws IOException {
        if (startAfterNs == null) {
            return 0;
        }

        long origin = firstProgressAtNs.get();
        if (origin == UNSET) {
            return 0;
        }

        long windowStart = Math.addExact(origin, startAfterNs);
        long windowEnd = Math.addExact(windowStart, durationNs);
        long now = clock.nowNanos();

        if (now < windowStart || now >= windowEnd) {
            return 0;
        }

        events.noProgressEntered(now);
        calibration.observeNoProgressEntered(now);

        long waitStarted = now;
        try {
            sleeper.sleepUntil(windowEnd);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new NoProgressCancelledException(
                    "No-progress wait interrupted", interrupted);
        }
        long wakeAt = clock.nowNanos();

        calibration.observeSchedulerSlip(Math.max(0L, wakeAt - windowEnd));
        calibration.observeNoProgressExited(wakeAt);
        events.noProgressExited(wakeAt);

        return Math.max(0L, wakeAt - waitStarted);
    }

    void onProgress(long atNanos) throws IOException {
        firstProgressAtNs.compareAndSet(UNSET, atNanos);
        events.firstMediaProgress(atNanos);
    }
}
