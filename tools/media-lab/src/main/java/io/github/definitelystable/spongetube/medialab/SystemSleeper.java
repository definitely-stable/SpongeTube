package io.github.definitelystable.spongetube.medialab;

import java.util.concurrent.locks.LockSupport;

final class SystemSleeper implements Sleeper {

    private final MonotonicClock clock;

    SystemSleeper(MonotonicClock clock) {
        this.clock = clock;
    }

    @Override
    public void sleepUntil(long deadlineNanos) throws InterruptedException {
        while (true) {
            if (Thread.interrupted()) {
                throw new InterruptedException("Media Lab sleep interrupted");
            }

            long remaining = deadlineNanos - clock.nowNanos();
            if (remaining <= 0) {
                return;
            }

            LockSupport.parkNanos(remaining);
        }
    }
}
