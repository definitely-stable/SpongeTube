package io.github.definitelystable.spongetube.measurement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaybackMetricReducerTest {

    @Test
    fun derivesTtffSingleRebufferAndSeekLatency() {
        val metrics = PlaybackMetricReducer.reduce(
            listOf(
                event(1, 0, PlaybackEventType.SESSION_STARTED),
                event(2, 100, PlaybackEventType.PLAY_REQUESTED),
                event(3, 120, PlaybackEventType.PREPARE_STARTED),
                event(
                    4,
                    200,
                    PlaybackEventType.BUFFERING_STARTED,
                    bufferingReason = BufferingReason.STARTUP,
                ),
                event(5, 500, PlaybackEventType.FIRST_FRAME),
                event(
                    6,
                    520,
                    PlaybackEventType.BUFFERING_ENDED,
                    bufferingReason = BufferingReason.STARTUP,
                ),
                event(
                    7,
                    1_000,
                    PlaybackEventType.BUFFERING_STARTED,
                    bufferingReason = BufferingReason.REBUFFER,
                ),
                event(
                    8,
                    1_250,
                    PlaybackEventType.BUFFERING_ENDED,
                    bufferingReason = BufferingReason.REBUFFER,
                ),
                event(
                    9,
                    2_000,
                    PlaybackEventType.SEEK_STARTED,
                    operationId = 7,
                ),
                event(
                    10,
                    2_050,
                    PlaybackEventType.BUFFERING_STARTED,
                    bufferingReason = BufferingReason.SEEK,
                ),
                event(
                    11,
                    2_100,
                    PlaybackEventType.SEEK_COMPLETED,
                    operationId = 7,
                ),
                event(
                    12,
                    2_240,
                    PlaybackEventType.FIRST_FRAME_AFTER_SEEK,
                    operationId = 7,
                ),
                event(
                    13,
                    2_250,
                    PlaybackEventType.BUFFERING_ENDED,
                    bufferingReason = BufferingReason.SEEK,
                ),
                event(14, 3_000, PlaybackEventType.SESSION_ENDED),
            ),
        )

        assertEquals(MetricDerivationStatus.COMPLETE, metrics.status)
        assertEquals(400L, metrics.ttffNs)
        assertEquals(1, metrics.stallCount)
        assertEquals(250L, metrics.stallTotalNs)
        assertEquals(
            listOf(SeekToFrameSample(operationId = 7, durationNs = 240L)),
            metrics.seekToFrame,
        )
        assertTrue(metrics.issues.isEmpty())
    }

    @Test
    fun startupAndSeekBufferingNeverCountAsNormalPlaybackStalls() {
        val metrics = PlaybackMetricReducer.reduce(
            listOf(
                event(1, 0, PlaybackEventType.SESSION_STARTED),
                event(2, 10, PlaybackEventType.PLAY_REQUESTED),
                event(
                    3,
                    20,
                    PlaybackEventType.BUFFERING_STARTED,
                    bufferingReason = BufferingReason.STARTUP,
                ),
                event(
                    4,
                    30,
                    PlaybackEventType.BUFFERING_ENDED,
                    bufferingReason = BufferingReason.STARTUP,
                ),
                event(5, 40, PlaybackEventType.FIRST_FRAME),
                event(
                    6,
                    50,
                    PlaybackEventType.SEEK_STARTED,
                    operationId = 1,
                ),
                event(
                    7,
                    60,
                    PlaybackEventType.BUFFERING_STARTED,
                    bufferingReason = BufferingReason.SEEK,
                ),
                event(
                    8,
                    70,
                    PlaybackEventType.SEEK_COMPLETED,
                    operationId = 1,
                ),
                event(
                    9,
                    80,
                    PlaybackEventType.FIRST_FRAME_AFTER_SEEK,
                    operationId = 1,
                ),
                event(
                    10,
                    90,
                    PlaybackEventType.BUFFERING_ENDED,
                    bufferingReason = BufferingReason.SEEK,
                ),
                event(11, 100, PlaybackEventType.SESSION_ENDED),
            ),
        )

        assertEquals(MetricDerivationStatus.COMPLETE, metrics.status)
        assertEquals(0, metrics.stallCount)
        assertEquals(0L, metrics.stallTotalNs)
    }

    @Test
    fun pausedTimeInsideOneRebufferIsExcludedWithoutSplittingStallCount() {
        val metrics = PlaybackMetricReducer.reduce(
            listOf(
                event(1, 0, PlaybackEventType.SESSION_STARTED),
                event(2, 10, PlaybackEventType.PLAY_REQUESTED),
                event(3, 20, PlaybackEventType.FIRST_FRAME),
                event(
                    4,
                    100,
                    PlaybackEventType.BUFFERING_STARTED,
                    bufferingReason = BufferingReason.REBUFFER,
                ),
                event(
                    5,
                    160,
                    PlaybackEventType.PLAY_INTENT_CHANGED,
                    playIntent = false,
                ),
                event(
                    6,
                    260,
                    PlaybackEventType.PLAY_INTENT_CHANGED,
                    playIntent = true,
                ),
                event(
                    7,
                    320,
                    PlaybackEventType.BUFFERING_ENDED,
                    bufferingReason = BufferingReason.REBUFFER,
                ),
                event(8, 400, PlaybackEventType.SESSION_ENDED),
            ),
        )

        assertEquals(MetricDerivationStatus.COMPLETE, metrics.status)
        assertEquals(1, metrics.stallCount)
        assertEquals(120L, metrics.stallTotalNs)
    }

    @Test
    fun incompleteSeekAndMissingSessionEndArePartialNotFabricated() {
        val metrics = PlaybackMetricReducer.reduce(
            listOf(
                event(1, 0, PlaybackEventType.SESSION_STARTED),
                event(2, 10, PlaybackEventType.PLAY_REQUESTED),
                event(3, 20, PlaybackEventType.FIRST_FRAME),
                event(
                    4,
                    30,
                    PlaybackEventType.SEEK_STARTED,
                    operationId = 9,
                ),
            ),
        )

        assertEquals(MetricDerivationStatus.PARTIAL, metrics.status)
        assertEquals(10L, metrics.ttffNs)
        assertTrue(metrics.seekToFrame.isEmpty())
        assertTrue(
            metrics.issues.any {
                it.code == MetricIssueCode.MISSING_FIRST_FRAME_AFTER_SEEK
            },
        )
        assertTrue(
            metrics.issues.any {
                it.code == MetricIssueCode.MISSING_SESSION_END
            },
        )
    }

    @Test
    fun malformedSequenceIsInvalidAndMetricsAreNotFabricated() {
        val metrics = PlaybackMetricReducer.reduce(
            listOf(
                event(1, 0, PlaybackEventType.SESSION_STARTED),
                event(3, 10, PlaybackEventType.SESSION_ENDED),
            ),
        )

        assertEquals(MetricDerivationStatus.INVALID, metrics.status)
        assertNull(metrics.ttffNs)
        assertEquals(0, metrics.stallCount)
        assertEquals(0L, metrics.stallTotalNs)
        assertTrue(
            metrics.issues.any {
                it.code == MetricIssueCode.NON_CONTIGUOUS_SEQUENCE
            },
        )
    }

    @Test
    fun firstFrameBeforePlayRequestIsInvalid() {
        val metrics = PlaybackMetricReducer.reduce(
            listOf(
                event(1, 0, PlaybackEventType.SESSION_STARTED),
                event(2, 10, PlaybackEventType.FIRST_FRAME),
                event(3, 20, PlaybackEventType.PLAY_REQUESTED),
                event(4, 30, PlaybackEventType.SESSION_ENDED),
            ),
        )

        assertEquals(MetricDerivationStatus.INVALID, metrics.status)
        assertNull(metrics.ttffNs)
        assertTrue(
            metrics.issues.any {
                it.code == MetricIssueCode.FIRST_FRAME_BEFORE_PLAY_REQUEST
            },
        )
    }

    private fun event(
        sequence: Long,
        atNs: Long,
        type: PlaybackEventType,
        bufferingReason: BufferingReason? = null,
        playIntent: Boolean? = null,
        operationId: Long? = null,
    ): PlaybackEvent = PlaybackEvent(
        sessionId = "s1",
        sequence = sequence,
        atElapsedRealtimeNs = atNs,
        type = type,
        bufferingReason = bufferingReason,
        playIntent = playIntent,
        operationId = operationId,
        errorCode = if (type == PlaybackEventType.PLAYBACK_ERROR) {
            "TEST"
        } else {
            null
        },
    )
}
