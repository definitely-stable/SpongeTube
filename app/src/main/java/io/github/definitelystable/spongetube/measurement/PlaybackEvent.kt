package io.github.definitelystable.spongetube.measurement

import android.os.SystemClock
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

const val PLAYBACK_EVENT_SCHEMA_VERSION: Int = 1

enum class PlaybackEventType {
    SESSION_STARTED,
    PLAY_REQUESTED,
    PLAY_INTENT_CHANGED,
    PREPARE_STARTED,
    PLAYBACK_READY,
    FIRST_FRAME,
    BUFFERING_STARTED,
    BUFFERING_ENDED,
    SEEK_STARTED,
    SEEK_COMPLETED,
    FIRST_FRAME_AFTER_SEEK,
    PLAYBACK_ENDED,
    PLAYBACK_ERROR,
    SESSION_ENDED,
}

enum class BufferingReason {
    STARTUP,
    REBUFFER,
    SEEK,
}

data class PlaybackEvent(
    val schemaVersion: Int = PLAYBACK_EVENT_SCHEMA_VERSION,
    val sessionId: String,
    val sequence: Long,
    val atElapsedRealtimeNs: Long,
    val type: PlaybackEventType,
    val bufferingReason: BufferingReason? = null,
    val playIntent: Boolean? = null,
    val operationId: Long? = null,
    val errorCode: String? = null,
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(sequence > 0) { "sequence must be > 0" }
        require(atElapsedRealtimeNs >= 0) { "timestamp must be >= 0" }

        when (type) {
            PlaybackEventType.BUFFERING_STARTED,
            PlaybackEventType.BUFFERING_ENDED,
            -> require(bufferingReason != null) {
                "$type requires bufferingReason"
            }

            PlaybackEventType.PLAY_INTENT_CHANGED -> require(playIntent != null) {
                "PLAY_INTENT_CHANGED requires playIntent"
            }

            PlaybackEventType.SEEK_STARTED,
            PlaybackEventType.SEEK_COMPLETED,
            PlaybackEventType.FIRST_FRAME_AFTER_SEEK,
            -> require(operationId != null && operationId > 0) {
                "$type requires operationId > 0"
            }

            PlaybackEventType.PLAYBACK_ERROR -> require(!errorCode.isNullOrBlank()) {
                "PLAYBACK_ERROR requires errorCode"
            }

            else -> Unit
        }
    }

    fun toJsonLine(): String = buildString(320) {
        append('{')
        field("schemaVersion", schemaVersion)
        append(',')
        field("sessionId", sessionId)
        append(',')
        field("sequence", sequence)
        append(',')
        field("atElapsedRealtimeNs", atElapsedRealtimeNs)
        append(',')
        field("type", type.name)
        append(',')
        nullableField("bufferingReason", bufferingReason?.name)
        append(',')
        nullableField("playIntent", playIntent)
        append(',')
        nullableField("operationId", operationId)
        append(',')
        nullableField("errorCode", errorCode)
        append('}')
    }
}

fun interface MeasurementClock {
    fun nowNs(): Long
}

object AndroidElapsedRealtimeClock : MeasurementClock {
    override fun nowNs(): Long = SystemClock.elapsedRealtimeNanos()
}

fun interface PlaybackEventSink {
    fun append(event: PlaybackEvent)
}

class PlaybackEventRecorder(
    private val sessionId: String,
    private val clock: MeasurementClock = AndroidElapsedRealtimeClock,
    private val sink: PlaybackEventSink,
) {
    private var nextSequence = 1L

    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
    }

    @Synchronized
    fun record(
        type: PlaybackEventType,
        bufferingReason: BufferingReason? = null,
        playIntent: Boolean? = null,
        operationId: Long? = null,
        errorCode: String? = null,
    ): PlaybackEvent {
        val event = PlaybackEvent(
            sessionId = sessionId,
            sequence = nextSequence++,
            atElapsedRealtimeNs = clock.nowNs(),
            type = type,
            bufferingReason = bufferingReason,
            playIntent = playIntent,
            operationId = operationId,
            errorCode = errorCode,
        )
        sink.append(event)
        return event
    }
}

class JsonlPlaybackEventFileSink private constructor(
    private val writer: BufferedWriter,
) : PlaybackEventSink, Closeable {

    @Synchronized
    override fun append(event: PlaybackEvent) {
        writer.write(event.toJsonLine())
        writer.newLine()
        writer.flush()
    }

    @Synchronized
    override fun close() {
        writer.close()
    }

    companion object {
        fun createNew(file: File): JsonlPlaybackEventFileSink {
            file.parentFile?.mkdirs()
            require(file.createNewFile()) {
                "Playback event artifact already exists: $file"
            }
            val writer = BufferedWriter(
                OutputStreamWriter(
                    FileOutputStream(file, false),
                    StandardCharsets.UTF_8,
                ),
            )
            return JsonlPlaybackEventFileSink(writer)
        }
    }
}

private fun StringBuilder.field(key: String, value: String) {
    appendJsonString(key)
    append(':')
    appendJsonString(value)
}

private fun StringBuilder.field(key: String, value: Long) {
    appendJsonString(key)
    append(':')
    append(value)
}

private fun StringBuilder.field(key: String, value: Int) {
    appendJsonString(key)
    append(':')
    append(value)
}

private fun StringBuilder.nullableField(key: String, value: String?) {
    appendJsonString(key)
    append(':')
    if (value == null) append("null") else appendJsonString(value)
}

private fun StringBuilder.nullableField(key: String, value: Long?) {
    appendJsonString(key)
    append(':')
    if (value == null) append("null") else append(value)
}

private fun StringBuilder.nullableField(key: String, value: Boolean?) {
    appendJsonString(key)
    append(':')
    if (value == null) append("null") else append(value)
}

private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }
    append('"')
}
