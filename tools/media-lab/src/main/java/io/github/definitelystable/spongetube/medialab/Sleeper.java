package io.github.definitelystable.spongetube.medialab;

interface Sleeper {
    void sleepUntil(long deadlineNanos) throws InterruptedException;
}
