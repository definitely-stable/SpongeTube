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
        if (delayNs == 0) {
            return;
        }

        long target = Math.addExact(clock.nowNanos(), delayNs);
        sleeper.sleepUntil(target);
        calibration.observeSchedulerSlip(Math.max(0L, clock.nowNanos() - target));
    }
}
