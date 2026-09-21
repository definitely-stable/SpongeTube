package io.github.definitelystable.spongetube.measurement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaybackEventRecorderTest {

    @Test
    fun recorderOwnsSequenceAndInjectedMonotonicClock() {
        val timestamps = ArrayDeque(listOf(100L, 150L, 225L))
        val captured = mutableListOf<PlaybackEvent>()
        val recorder = PlaybackEventRecorder(
            sessionId = "run-1",
            clock = MeasurementClock { timestamps.removeFirst() },
            sink = PlaybackEventSink(captured::add),
        )

        recorder.record(PlaybackEventType.SESSION_STARTED)
        recorder.record(PlaybackEventType.PLAY_REQUESTED)
        recorder.record(
            PlaybackEventType.PLAY_INTENT_CHANGED,
            playIntent = true,
        )

        assertEquals(listOf(1L, 2L, 3L), captured.map { it.sequence })
        assertEquals(
            listOf(100L, 150L, 225L),
            captured.map { it.atElapsedRealtimeNs },
        )
        assertTrue(captured.all { it.sessionId == "run-1" })
    }

    @Test
    fun jsonLineEscapesStringsAndKeepsOptionalFieldsExplicit() {
        val event = PlaybackEvent(
            sessionId = "s\n1",
            sequence = 1,
            atElapsedRealtimeNs = 10,
            type = PlaybackEventType.PLAYBACK_ERROR,
            errorCode = "IO_\"FAIL\"",
        )

        val json = event.toJsonLine()

        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertTrue(json.contains("\\"schemaVersion\\":1"))
        assertTrue(json.contains("\\"sessionId\\":\"s\\n1\""))
        assertTrue(json.contains("\\"bufferingReason\\":null"))
        assertTrue(json.contains("\\"playIntent\\":null"))
        assertTrue(json.contains("\\"operationId\\":null"))
        assertTrue(json.contains("\\"errorCode\\":\"IO_\\\"FAIL\\\"\""))
    }
}
