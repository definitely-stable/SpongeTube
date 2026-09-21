package io.github.definitelystable.spongetube.measurement

import android.content.Context
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import com.google.common.util.concurrent.ListenableFuture
import java.io.Closeable
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

class PlaybackMeasurementSession private constructor(
    val sessionId: String,
    val player: Player,
    val artifactFile: File,
    private val recorder: PlaybackEventRecorder,
    private val sink: JsonlPlaybackEventFileSink,
    private val listener: Player.Listener,
) : Closeable {

    private val closed = AtomicBoolean(false)

    fun recordPrepareStarted() {
        recorder.record(PlaybackEventType.PREPARE_STARTED)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        player.removeListener(listener)
        recorder.record(PlaybackEventType.SESSION_ENDED)
        sink.close()
    }

    companion object {
        fun create(
            context: Context,
            delegate: Player,
            runId: String,
            generation: Long,
        ): PlaybackMeasurementSession {
            require(runId.matches(Regex("[A-Za-z0-9._-]+"))) {
                "runId contains unsupported file-name characters"
            }
            require(generation > 0) { "generation must be > 0" }

            val sessionId = "$runId-$generation"
            val root = context.getExternalFilesDir("m0-measurement")
                ?: File(context.filesDir, "m0-measurement")
            val artifactFile = File(root, "$sessionId/playback-events.jsonl")
            val sink = JsonlPlaybackEventFileSink.createNew(artifactFile)
            val recorder = PlaybackEventRecorder(
                sessionId = sessionId,
                sink = sink,
            )

            recorder.record(PlaybackEventType.SESSION_STARTED)

            val measurementState = MeasurementPlayerState(recorder)
            val measuredPlayer = MeasuredPlayer(
                delegate = delegate,
                state = measurementState,
            )
            val listener = measurementState.newListener()
            measuredPlayer.addListener(listener)

            return PlaybackMeasurementSession(
                sessionId = sessionId,
                player = measuredPlayer,
                artifactFile = artifactFile,
                recorder = recorder,
                sink = sink,
                listener = listener,
            )
        }
    }
}

private class MeasurementPlayerState(
    private val recorder: PlaybackEventRecorder,
) {
    private var firstFrameSeen = false
    private var firstReadySeen = false
    private var currentBufferingReason: BufferingReason? = null
    private var nextSeekOperationId = 1L
    private val pendingSeekCompletions = ArrayDeque<Long>()
    private var pendingFrameAfterSeekId: Long? = null
    private var playRequestedRecorded = false
    private var playbackEndedRecorded = false

    fun recordPlayIntent(playWhenReady: Boolean) {
        if (playWhenReady && !playRequestedRecorded) {
            playRequestedRecorded = true
            recorder.record(PlaybackEventType.PLAY_REQUESTED)
        }
        recorder.record(
            PlaybackEventType.PLAY_INTENT_CHANGED,
            playIntent = playWhenReady,
        )
    }

    fun recordSeekStarted(): Long {
        val operationId = nextSeekOperationId++
        pendingSeekCompletions.addLast(operationId)
        pendingFrameAfterSeekId = operationId
        recorder.record(
            PlaybackEventType.SEEK_STARTED,
            operationId = operationId,
        )
        return operationId
    }

    fun newListener(): Player.Listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    if (currentBufferingReason == null) {
                        currentBufferingReason = when {
                            !firstFrameSeen -> BufferingReason.STARTUP
                            pendingFrameAfterSeekId != null ||
                                pendingSeekCompletions.isNotEmpty() -> BufferingReason.SEEK
                            else -> BufferingReason.REBUFFER
                        }
                        recorder.record(
                            PlaybackEventType.BUFFERING_STARTED,
                            bufferingReason = currentBufferingReason,
                        )
                    }
                }

                Player.STATE_READY -> {
                    endBufferingIfOpen()
                    if (!firstReadySeen) {
                        firstReadySeen = true
                        recorder.record(PlaybackEventType.PLAYBACK_READY)
                    }
                }

                Player.STATE_ENDED -> {
                    endBufferingIfOpen()
                    if (!playbackEndedRecorded) {
                        playbackEndedRecorded = true
                        recorder.record(PlaybackEventType.PLAYBACK_ENDED)
                    }
                }

                Player.STATE_IDLE -> endBufferingIfOpen()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK) {
                return
            }

            val operationId = if (pendingSeekCompletions.isEmpty()) {
                recordSyntheticSeekStart()
            } else {
                pendingSeekCompletions.removeFirst()
            }

            recorder.record(
                PlaybackEventType.SEEK_COMPLETED,
                operationId = operationId,
            )
        }

        override fun onEvents(
            player: Player,
            events: Player.Events,
        ) {
            if (!events.contains(Player.EVENT_RENDERED_FIRST_FRAME)) {
                return
            }

            if (!firstFrameSeen) {
                firstFrameSeen = true
                recorder.record(PlaybackEventType.FIRST_FRAME)
                return
            }

            val operationId = pendingFrameAfterSeekId ?: return
            pendingFrameAfterSeekId = null
            recorder.record(
                PlaybackEventType.FIRST_FRAME_AFTER_SEEK,
                operationId = operationId,
            )
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            recorder.record(
                PlaybackEventType.PLAYBACK_ERROR,
                errorCode = error.errorCodeName,
            )
        }
    }

    private fun recordSyntheticSeekStart(): Long {
        val operationId = nextSeekOperationId++
        pendingFrameAfterSeekId = operationId
        recorder.record(
            PlaybackEventType.SEEK_STARTED,
            operationId = operationId,
        )
        return operationId
    }

    private fun endBufferingIfOpen() {
        val reason = currentBufferingReason ?: return
        currentBufferingReason = null
        recorder.record(
            PlaybackEventType.BUFFERING_ENDED,
            bufferingReason = reason,
        )
    }
}

private class MeasuredPlayer(
    delegate: Player,
    private val state: MeasurementPlayerState,
) : ForwardingSimpleBasePlayer(delegate) {

    override fun handleSetPlayWhenReady(
        playWhenReady: Boolean,
    ): ListenableFuture<*> {
        state.recordPlayIntent(playWhenReady)
        return super.handleSetPlayWhenReady(playWhenReady)
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        state.recordSeekStarted()
        return super.handleSeek(
            mediaItemIndex,
            positionMs,
            seekCommand,
        )
    }
}
