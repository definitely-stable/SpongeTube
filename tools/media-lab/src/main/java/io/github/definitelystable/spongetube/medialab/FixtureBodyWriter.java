package io.github.definitelystable.spongetube.medialab;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class FixtureBodyWriter {

    private final ResolvedScenario scenario;
    private final MonotonicClock clock;
    private final FirstBodyDelay firstBodyDelay;
    private final NoProgressGate noProgressGate;
    private final GlobalBandwidthGovernor bandwidthGovernor;
    private final SessionCalibration calibration;

    FixtureBodyWriter(
            ResolvedScenario scenario,
            MonotonicClock clock,
            Sleeper sleeper,
            SessionEventRecorder events,
            SessionCalibration calibration) {
        this.scenario = scenario;
        this.clock = clock;
        this.calibration = calibration;
        this.firstBodyDelay =
                new FirstBodyDelay(scenario.firstBodyDelayMs(), clock, sleeper, calibration);
        this.noProgressGate =
                new NoProgressGate(scenario, clock, sleeper, events, calibration);
        this.bandwidthGovernor =
                new GlobalBandwidthGovernor(scenario.aggregateRateBps(), clock, sleeper, calibration);
    }

    void write(
            InputStream input,
            OutputStream output,
            RequestTraceAccumulator trace,
            long length) throws IOException {
        byte[] buffer = new byte[scenario.writeQuantumBytes()];
        long remaining = length;
        boolean firstChunk = true;

        while (remaining > 0) {
            int requested = (int) Math.min(buffer.length, remaining);
            int read = input.read(buffer, 0, requested);
            if (read < 0) {
                throw new IOException("Unexpected EOF while serving " + trace.resourceId);
            }

            try {
                if (firstChunk) {
                    firstBodyDelay.await();
                    firstChunk = false;
                }

                long noProgressWaitNs = noProgressGate.awaitOpen();
                trace.addNoProgressWaitNanos(noProgressWaitNs);

                bandwidthGovernor.reserve(read);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new NoProgressCancelledException("Fixture body wait interrupted", interrupted);
            }

            long writeAt = clock.nowNanos();
            trace.markFirstBodyWrite(writeAt);
            output.write(buffer, 0, read);

            // N4 deliberately flushes bounded writes so the real-socket calibration can
            // characterize, rather than accidentally maximize, pre-gate buffering.
            if (scenario.noProgressDurationMs() != null) {
                output.flush();
            }

            trace.bodyBytesWritten += read;
            calibration.observeMediaWrite(writeAt, read);
            noProgressGate.onProgress(writeAt);
            remaining -= read;
        }
    }
}
