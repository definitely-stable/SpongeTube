package io.github.definitelystable.spongetube.medialab;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

record MediaNetworkSummary(
        int requestCount,
        long networkBytes,
        long uniqueRangeBytes,
        long duplicateRangeBytes,
        int httpErrorCount) {}

final class RequestTraceSummary {

    private RequestTraceSummary() {}

    static MediaNetworkSummary summarize(List<RequestTrace> traces) {
        int requestCount = 0;
        int httpErrorCount = 0;
        long networkBytes = 0;

        Map<ResourceKey, List<Interval>> intervalsByResource = new HashMap<>();

        for (RequestTrace trace : traces) {
            if (!isFixturePath(trace.path())) {
                continue;
            }

            requestCount += 1;
            if (trace.status() >= 400) {
                httpErrorCount += 1;
            }

            if (!isSuccessfulMediaBody(trace)) {
                continue;
            }

            long bodyBytes = trace.bodyBytesWritten();
            if (bodyBytes < 0) {
                throw new IllegalArgumentException("bodyBytesWritten must be >= 0");
            }
            if (bodyBytes == 0) {
                continue;
            }

            Long start = trace.resolvedRangeStart();
            Long resolvedEnd = trace.resolvedRangeEndExclusive();
            if (start == null || resolvedEnd == null) {
                throw new IllegalArgumentException(
                        "successful media body requires a resolved range: requestId="
                                + trace.requestId());
            }
            if (start < 0 || resolvedEnd < start) {
                throw new IllegalArgumentException(
                        "invalid resolved range: requestId=" + trace.requestId());
            }

            long actualEnd = Math.addExact(start, bodyBytes);
            if (actualEnd > resolvedEnd) {
                throw new IllegalArgumentException(
                        "bodyBytesWritten exceeds resolved coverage: requestId="
                                + trace.requestId());
            }

            networkBytes = Math.addExact(networkBytes, bodyBytes);

            ResourceKey key = new ResourceKey(
                    trace.fixtureId(),
                    trace.resourceId());
            intervalsByResource
                    .computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(new Interval(start, actualEnd));
        }

        long uniqueRangeBytes = 0;
        for (List<Interval> intervals : intervalsByResource.values()) {
            uniqueRangeBytes = Math.addExact(
                    uniqueRangeBytes,
                    unionLength(intervals));
        }

        long duplicateRangeBytes = networkBytes - uniqueRangeBytes;
        if (duplicateRangeBytes < 0) {
            throw new IllegalStateException(
                    "unique coverage cannot exceed actual network bytes");
        }

        return new MediaNetworkSummary(
                requestCount,
                networkBytes,
                uniqueRangeBytes,
                duplicateRangeBytes,
                httpErrorCount);
    }

    private static boolean isFixturePath(String path) {
        return path != null && path.startsWith("/fixtures/");
    }

    private static boolean isSuccessfulMediaBody(RequestTrace trace) {
        return trace.fixtureId() != null
                && trace.resourceId() != null
                && ("GET".equals(trace.method()) || "HEAD".equals(trace.method()))
                && (trace.status() == 200 || trace.status() == 206);
    }

    private static long unionLength(List<Interval> intervals) {
        if (intervals.isEmpty()) {
            return 0;
        }

        List<Interval> sorted = new ArrayList<>(intervals);
        sorted.sort(Comparator
                .comparingLong(Interval::start)
                .thenComparingLong(Interval::endExclusive));

        long total = 0;
        long currentStart = sorted.get(0).start();
        long currentEnd = sorted.get(0).endExclusive();

        for (int index = 1; index < sorted.size(); index++) {
            Interval next = sorted.get(index);

            if (next.start() <= currentEnd) {
                currentEnd = Math.max(currentEnd, next.endExclusive());
                continue;
            }

            total = Math.addExact(total, currentEnd - currentStart);
            currentStart = next.start();
            currentEnd = next.endExclusive();
        }

        return Math.addExact(total, currentEnd - currentStart);
    }

    private record ResourceKey(
            String fixtureId,
            String resourceId) {

        private ResourceKey {
            if (fixtureId == null || resourceId == null) {
                throw new IllegalArgumentException(
                        "resource identity must be complete");
            }
        }
    }

    private record Interval(
            long start,
            long endExclusive) {

        private Interval {
            if (start < 0 || endExclusive < start) {
                throw new IllegalArgumentException("invalid interval");
            }
        }
    }
}
