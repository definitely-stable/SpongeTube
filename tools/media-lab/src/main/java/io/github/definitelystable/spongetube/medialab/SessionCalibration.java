package io.github.definitelystable.spongetube.medialab;

import java.util.ArrayList;
import java.util.List;

final class SessionCalibration {

    private long mediaBytesWritten;
    private long firstWriteBytes;
    private Long firstMediaWriteAtNs;
    private Long lastMediaWriteAtNs;

    private long firstBodyDelaySampleCount;
    private long firstBodyDelayTotalMs;
    private final List<Long> firstBodyDelaySamplesMs = new ArrayList<>();
    private Long firstBodyDelayMinMs;
    private Long firstBodyDelayMaxMs;

    private Long noProgressEnteredAtNs;
    private Long noProgressExitedAtNs;
    private long maxSchedulerSlipNs;
    private final List<Long> schedulerSlipSamplesMs = new ArrayList<>();

    synchronized void observeMediaWrite(long atNanos, int bytes) {
        if (firstMediaWriteAtNs == null) {
            firstMediaWriteAtNs = atNanos;
            firstWriteBytes = bytes;
        }
        lastMediaWriteAtNs = atNanos;
        mediaBytesWritten += bytes;
    }

    synchronized void observeFirstBodyDelay(long delayMs) {
        firstBodyDelaySampleCount++;
        firstBodyDelayTotalMs += delayMs;
        firstBodyDelaySamplesMs.add(delayMs);
        firstBodyDelayMinMs = firstBodyDelayMinMs == null
                ? delayMs
                : Math.min(firstBodyDelayMinMs, delayMs);
        firstBodyDelayMaxMs = firstBodyDelayMaxMs == null
                ? delayMs
                : Math.max(firstBodyDelayMaxMs, delayMs);
    }

    synchronized void observeNoProgressEntered(long atNanos) {
        if (noProgressEnteredAtNs == null) {
            noProgressEnteredAtNs = atNanos;
        }
    }

    synchronized void observeNoProgressExited(long atNanos) {
        if (noProgressExitedAtNs == null) {
            noProgressExitedAtNs = atNanos;
        }
    }

    synchronized void observeSchedulerSlip(long slipNs) {
        long normalized = Math.max(0L, slipNs);
        maxSchedulerSlipNs = Math.max(maxSchedulerSlipNs, normalized);
        schedulerSlipSamplesMs.add(nanosToMillisCeil(normalized));
    }

    synchronized String toJson(ResolvedScenario scenario) {
        Long observedRateBps = observedRateBps();
        Long observedFirstBodyDelayMs = firstBodyDelaySampleCount == 0
                ? null
                : Math.round((double) firstBodyDelayTotalMs / firstBodyDelaySampleCount);
        Long observedNoProgressDurationMs = observedNoProgressDurationMs();

        return "{"
                + Json.quote("schemaVersion") + ":1,"
                + Json.quote("scenarioId") + ":" + Json.quote(scenario.scenarioId()) + ","
                + Json.quote("scenarioHash") + ":" + Json.quote(scenario.scenarioHash()) + ","
                + Json.quote("configuredRateBps") + ":" + nullable(scenario.aggregateRateBps()) + ","
                + Json.quote("observedRateBps") + ":" + nullable(observedRateBps) + ","
                + Json.quote("rateErrorPct") + ":" + nullableDecimal(
                        percentageError(observedRateBps, scenario.aggregateRateBps())) + ","
                + Json.quote("configuredFirstBodyDelayMs") + ":" + scenario.firstBodyDelayMs() + ","
                + Json.quote("observedFirstBodyDelayMs") + ":" + nullable(observedFirstBodyDelayMs) + ","
                + Json.quote("firstBodyDelayErrorMs") + ":" + nullable(
                        difference(observedFirstBodyDelayMs, scenario.firstBodyDelayMs())) + ","
                + Json.quote("firstBodyDelaySampleCount") + ":" + firstBodyDelaySampleCount + ","
                + Json.quote("firstBodyDelayMinMs") + ":" + nullable(firstBodyDelayMinMs) + ","
                + Json.quote("firstBodyDelayMaxMs") + ":" + nullable(firstBodyDelayMaxMs) + ","
                + Json.quote("firstBodyDelaySamplesMs") + ":" + longArray(firstBodyDelaySamplesMs) + ","
                + Json.quote("configuredNoProgressDurationMs") + ":"
                + nullable(scenario.noProgressDurationMs()) + ","
                + Json.quote("observedNoProgressDurationMs") + ":"
                + nullable(observedNoProgressDurationMs) + ","
                + Json.quote("noProgressDurationErrorMs") + ":"
                + nullable(difference(observedNoProgressDurationMs, scenario.noProgressDurationMs())) + ","
                + Json.quote("maxSchedulerSlipMs") + ":"
                + nanosToMillisCeil(maxSchedulerSlipNs) + ","
                + Json.quote("schedulerSlipSamplesMs") + ":" + longArray(schedulerSlipSamplesMs) + ","
                + Json.quote("mediaBytesWritten") + ":" + mediaBytesWritten + ","
                + Json.quote("postGateDrainMeasurement") + ":"
                + Json.quote("client-side real-socket evidence only")
                + "}";
    }

    private Long observedRateBps() {
        if (firstMediaWriteAtNs == null
                || lastMediaWriteAtNs == null
                || lastMediaWriteAtNs <= firstMediaWriteAtNs
                || mediaBytesWritten <= firstWriteBytes) {
            return null;
        }

        long durationNs = lastMediaWriteAtNs - firstMediaWriteAtNs;
        long pacedBytes = mediaBytesWritten - firstWriteBytes;
        return Math.round((pacedBytes * 8.0d * 1_000_000_000.0d) / durationNs);
    }

    private Long observedNoProgressDurationMs() {
        if (noProgressEnteredAtNs == null
                || noProgressExitedAtNs == null
                || noProgressExitedAtNs < noProgressEnteredAtNs) {
            return null;
        }
        return (noProgressExitedAtNs - noProgressEnteredAtNs) / 1_000_000L;
    }

    private static Long difference(Long observed, Long configured) {
        return observed == null || configured == null ? null : observed - configured;
    }

    private static Long difference(Long observed, long configured) {
        return observed == null ? null : observed - configured;
    }

    private static Double percentageError(Long observed, Long configured) {
        if (observed == null || configured == null || configured == 0) {
            return null;
        }
        return ((observed - configured) * 100.0d) / configured;
    }

    private static String nullable(Long value) {
        return value == null ? "null" : Long.toString(value);
    }

    private static String nullableDecimal(Double value) {
        return value == null ? "null" : String.format(java.util.Locale.ROOT, "%.6f", value);
    }

    private static String longArray(List<Long> values) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(values.get(index));
        }
        return json.append(']').toString();
    }

    private static long nanosToMillisCeil(long nanos) {
        if (nanos <= 0) {
            return 0;
        }
        return (nanos + 999_999L) / 1_000_000L;
    }
}
