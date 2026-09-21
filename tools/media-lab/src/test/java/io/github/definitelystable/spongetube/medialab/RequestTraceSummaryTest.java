package io.github.definitelystable.spongetube.medialab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class RequestTraceSummaryTest {

    @Test
    void disjointAndAdjacentRangesHaveNoDuplicateBytes() {
        MediaNetworkSummary summary = RequestTraceSummary.summarize(List.of(
                trace(1, "data", "/fixtures/F1/video.m4s", "F1", "video", 206, 0L, 100L, 100),
                trace(2, "data", "/fixtures/F1/video.m4s", "F1", "video", 206, 100L, 200L, 100)));

        assertEquals(2, summary.requestCount());
        assertEquals(200, summary.networkBytes());
        assertEquals(200, summary.uniqueRangeBytes());
        assertEquals(0, summary.duplicateRangeBytes());
        assertEquals(0, summary.httpErrorCount());
    }

    @Test
    void partiallyOverlappingRangesCountOnlyOverlapAsDuplicate() {
        MediaNetworkSummary summary = RequestTraceSummary.summarize(List.of(
                trace(1, "/fixtures/F1/video.m4s", "F1", "video", 206, 0L, 100L, 100),
                trace(2, "/fixtures/F1/video.m4s", "F1", "video", 206, 50L, 150L, 100)));

        assertEquals(200, summary.networkBytes());
        assertEquals(150, summary.uniqueRangeBytes());
        assertEquals(50, summary.duplicateRangeBytes());
    }

    @Test
    void nestedRangeCountsNestedBytesAsDuplicate() {
        MediaNetworkSummary summary = RequestTraceSummary.summarize(List.of(
                trace(1, "/fixtures/F1/video.m4s", "F1", "video", 206, 0L, 200L, 200),
                trace(2, "/fixtures/F1/video.m4s", "F1", "video", 206, 50L, 100L, 50)));

        assertEquals(250, summary.networkBytes());
        assertEquals(200, summary.uniqueRangeBytes());
        assertEquals(50, summary.duplicateRangeBytes());
    }

    @Test
    void coverageIsUnionedPerLogicalResource() {
        MediaNetworkSummary summary = RequestTraceSummary.summarize(List.of(
                trace(1, "/fixtures/F1/video.m4s", "F1", "video", 206, 0L, 100L, 100),
                trace(2, "data", "/fixtures/F1/audio.m4s", "F1", "audio", 206, 0L, 100L, 100)));

        assertEquals(200, summary.networkBytes());
        assertEquals(200, summary.uniqueRangeBytes());
        assertEquals(0, summary.duplicateRangeBytes());
    }

    @Test
    void actualWrittenBytesDefineCoverageInsteadOfPlannedRange() {
        RequestTrace partialWrite = trace(
                1,
                "data",
                "/fixtures/F1/video.m4s",
                "F1",
                "video",
                206,
                0L,
                1_000L,
                100);

        MediaNetworkSummary summary = RequestTraceSummary.summarize(List.of(partialWrite));

        assertEquals(100, summary.networkBytes());
        assertEquals(100, summary.uniqueRangeBytes());
        assertEquals(0, summary.duplicateRangeBytes());
    }

    @Test
    void controlRowsNeverContaminateMediaMetricsAndFixtureErrorsAreCounted() {
        RequestTrace control = trace(
                1,
                "control",
                "/fixtures/F1/not-a-control-resource.m4s",
                null,
                null,
                404,
                null,
                null,
                50);
        RequestTrace fixtureError = trace(
                2,
                "data",
                "/fixtures/F1/missing.m4s",
                null,
                null,
                404,
                null,
                null,
                0);

        MediaNetworkSummary summary = RequestTraceSummary.summarize(
                List.of(control, fixtureError));

        assertEquals(1, summary.requestCount());
        assertEquals(0, summary.networkBytes());
        assertEquals(0, summary.uniqueRangeBytes());
        assertEquals(0, summary.duplicateRangeBytes());
        assertEquals(1, summary.httpErrorCount());
    }

    @Test
    void rejectsTraceWhereWrittenBytesExceedResolvedCoverage() {
        RequestTrace invalid = trace(
                1,
                "data",
                "/fixtures/F1/video.m4s",
                "F1",
                "video",
                206,
                10L,
                20L,
                11);

        assertThrows(
                IllegalArgumentException.class,
                () -> RequestTraceSummary.summarize(List.of(invalid)));
    }

    private static RequestTrace trace(
            long requestId,
            String plane,
            String path,
            String fixtureId,
            String resourceId,
            int status,
            Long start,
            Long endExclusive,
            long bodyBytesWritten) {
        return new RequestTrace(
                1,
                "session-1",
                requestId,
                plane,
                fixtureId,
                resourceId,
                "N0",
                "N0",
                "scenario-hash",
                "GET",
                path,
                null,
                start,
                endExclusive,
                status,
                endExclusive == null || start == null
                        ? bodyBytesWritten
                        : endExclusive - start,
                bodyBytesWritten,
                1,
                bodyBytesWritten > 0 ? 2L : null,
                3,
                bodyBytesWritten > 0 ? 0L : null,
                0,
                null,
                0,
                TraceOutcome.SUCCESS);
    }
}
