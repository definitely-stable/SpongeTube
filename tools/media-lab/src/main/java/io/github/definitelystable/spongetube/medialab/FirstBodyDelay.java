package io.github.definitelystable.spongetube.medialab;

final class FirstBodyDelay {

    private final long delayNs;
    private final MonotonicClock clock;
    private final Sleeper sleeper;
    private final SessionCalibration calibration;

    FirstBodyDelay(
            long delayMs,
            MonotonicClock clock,
            Sleeper sleeper,
            SessionCalibration calibration) {
        this.delayNs = Math.multiplyExact(delayMs, 1_000_000L);
        this.clock = clock;
        this.sleeper = sleeper;
        this.calibration = calibration;
    }

    void await() throws InterruptedException {
        long startedAt = clock.nowNanos();
        if (delayNs == 0) {
            calibration.observeFirstBodyDelay(0);
            return;
        }

        long target = Math.addExact(startedAt, delayNs);
        sleeper.sleepUntil(target);
        long wokeAt = clock.nowNanos();

        calibration.observeSchedulerSlip(Math.max(0L, wokeAt - target));
        calibration.observeFirstBodyDelay(
                Math.max(0L, wokeAt - startedAt) / 1_000_000L);
    }
}
