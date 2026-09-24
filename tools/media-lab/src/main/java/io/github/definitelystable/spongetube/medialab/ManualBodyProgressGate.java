package io.github.definitelystable.spongetube.medialab;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Session-wide media-body gate for M1 N4 recovery verification.
 *
 * The gate is deliberately below HTTP request admission: requests can receive
 * headers and then stop making body progress. This models the N4 delivery
 * plane without pretending that request creation itself failed.
 */
final class ManualBodyProgressGate {

    private static final Pattern COMMAND_ID =
            Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final Object lock = new Object();
    private final MonotonicClock clock;
    private final JsonLineTraceWriter writer;
    private final AtomicLong eventSequence = new AtomicLong();

    private boolean closed;
    private long generation;
    private String activeCloseCommandId;
    private String lastOpenCommandId;
    private int activeBlockedRequests;

    ManualBodyProgressGate(
            MonotonicClock clock,
            JsonLineTraceWriter writer) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    String close(String commandId) throws IOException {
        validateCommandId(commandId);
        synchronized (lock) {
            if (!closed) {
                closed = true;
                generation = Math.addExact(generation, 1L);
                activeCloseCommandId = commandId;
            }
            appendLocked("GATE_CLOSE_ACCEPTED", commandId, null);
            return stateJsonLocked();
        }
    }

    String open(String commandId) throws IOException {
        validateCommandId(commandId);
        synchronized (lock) {
            lastOpenCommandId = commandId;
            appendLocked("GATE_OPEN_ACCEPTED", commandId, null);
            if (closed) {
                closed = false;
                lock.notifyAll();
            }
            return stateJsonLocked();
        }
    }

    String stateJson() {
        synchronized (lock) {
            return stateJsonLocked();
        }
    }

    long awaitOpen(long requestId) throws IOException {
        long started;
        long blockedGeneration;
        String blockedBy;
        synchronized (lock) {
            if (!closed) {
                return 0L;
            }
            started = clock.nowNanos();
            blockedGeneration = generation;
            blockedBy = activeCloseCommandId;
            appendLocked("REQUEST_BLOCKED", blockedBy, requestId);
            activeBlockedRequests += 1;
            try {
                while (closed && generation == blockedGeneration) {
                    try {
                        lock.wait();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new NoProgressCancelledException(
                                "manual media-body gate wait interrupted",
                                interrupted);
                    }
                }
                appendLocked(
                        "REQUEST_RELEASED",
                        lastOpenCommandId,
                        requestId);
            } finally {
                activeBlockedRequests -= 1;
            }
            return Math.max(0L, clock.nowNanos() - started);
        }
    }

    private String stateJsonLocked() {
        return "{"
                + Json.quote("schemaVersion") + ":1,"
                + Json.quote("state") + ":"
                + Json.quote(closed ? "CLOSED" : "OPEN") + ","
                + Json.quote("generation") + ":" + generation + ","
                + Json.quote("activeCloseCommandId") + ":"
                + nullable(activeCloseCommandId) + ","
                + Json.quote("lastOpenCommandId") + ":"
                + nullable(lastOpenCommandId) + ","
                + Json.quote("activeBlockedRequests") + ":"
                + activeBlockedRequests
                + "}";
    }

    private void appendLocked(
            String event,
            String commandId,
            Long requestId) throws IOException {
        writer.append(
                new OriginGateEvent(
                        1,
                        eventSequence.getAndIncrement(),
                        clock.nowNanos(),
                        event,
                        generation,
                        commandId,
                        requestId));
    }

    private static void validateCommandId(String commandId) {
        if (commandId == null || !COMMAND_ID.matcher(commandId).matches()) {
            throw new IllegalArgumentException(
                    "gate command id must match " + COMMAND_ID.pattern());
        }
    }

    private static String nullable(String value) {
        return value == null ? "null" : Json.quote(value);
    }
}
