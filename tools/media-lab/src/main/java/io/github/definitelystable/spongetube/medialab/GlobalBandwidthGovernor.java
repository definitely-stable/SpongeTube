package io.github.definitelystable.spongetube.medialab;

import java.util.concurrent.locks.ReentrantLock;

final class GlobalBandwidthGovernor {

    private static final long NANOS_PER_BIT_SECOND = 1_000_000_000L;

    private final Long rateBps;
    private final MonotonicClock clock;
    private final Sleeper sleeper;
    private final SessionCalibration calibration;
    private final ReentrantLock lock = new ReentrantLock(true);

    private long nextAvailableNs;

    GlobalBandwidthGovernor(
            Long rateBps,
            MonotonicClock clock,
            Sleeper sleeper,
            SessionCalibration calibration) {
        if (rateBps != null && rateBps <= 0) {
            throw new IllegalArgumentException("rateBps must be > 0");
        }
        this.rateBps = rateBps;
        this.clock = clock;
        this.sleeper = sleeper;
        this.calibration = calibration;
    }

    void reserve(int bytes) throws InterruptedException {
        if (bytes <= 0 || rateBps == null) {
            return;
        }

        long slotStart;
        lock.lock();
        try {
            long now = clock.nowNanos();
            slotStart = Math.max(now, nextAvailableNs);
            long slotDuration = slotDurationNs(bytes, rateBps);
            nextAvailableNs = Math.addExact(slotStart, slotDuration);
        } finally {
            lock.unlock();
        }

        sleeper.sleepUntil(slotStart);
        calibration.observeSchedulerSlip(Math.max(0L, clock.nowNanos() - slotStart));
    }

    static long slotDurationNs(int bytes, long rateBps) {
        if (bytes <= 0) {
            return 0;
        }
        if (rateBps <= 0) {
            throw new IllegalArgumentException("rateBps must be > 0");
        }

        long bitNanos = Math.multiplyExact((long) bytes, 8L * NANOS_PER_BIT_SECOND);
        return Math.floorDiv(bitNanos + rateBps - 1, rateBps);
    }
}
