package io.github.definitelystable.spongetube.medialab;

final class FakeTime implements MonotonicClock, Sleeper {

    private long nowNanos;

    FakeTime() {
        this(0L);
    }

    FakeTime(long initialNanos) {
        this.nowNanos = initialNanos;
    }

    @Override
    public synchronized long nowNanos() {
        return nowNanos;
    }

    @Override
    public synchronized void sleepUntil(long deadlineNanos) {
        nowNanos = Math.max(nowNanos, deadlineNanos);
    }

    synchronized void advanceMillis(long millis) {
        nowNanos = Math.addExact(nowNanos, Math.multiplyExact(millis, 1_000_000L));
    }
}
