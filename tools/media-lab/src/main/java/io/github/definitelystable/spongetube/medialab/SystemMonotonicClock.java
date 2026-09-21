package io.github.definitelystable.spongetube.medialab;

enum SystemMonotonicClock implements MonotonicClock {
    INSTANCE;

    @Override
    public long nowNanos() {
        return System.nanoTime();
    }
}
