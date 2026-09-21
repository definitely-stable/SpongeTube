package io.github.definitelystable.spongetube

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.definitelystable.spongetube.playback.baseline.BaselineCacheState
import io.github.definitelystable.spongetube.playback.baseline.BaselineMode
import io.github.definitelystable.spongetube.playback.baseline.BaselinePlayback
import io.github.definitelystable.spongetube.playback.baseline.BaselinePlaybackSession
import io.github.definitelystable.spongetube.playback.baseline.BaselinePlaybackSpec
import io.github.definitelystable.spongetube.playback.baseline.BaselineTransport
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
class M0CSmokeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val prepareExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "m0-c-smoke-prepare").apply {
            isDaemon = true
        }
    }
    private val completed = AtomicBoolean(false)

    private var phase: SmokePhase = SmokePhase.DIRECT
    private var session: BaselinePlaybackSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        phase = runCatching {
            SmokePhase.valueOf(
                intent.getStringExtra(EXTRA_PHASE)
                    ?.uppercase()
                    ?: error("Missing --es phase"),
            )
        }.getOrElse { throwable ->
            fail("invalid_phase", throwable)
            return
        }

        handler.postDelayed(
            { fail("timeout", IllegalStateException("Smoke phase timed out")) },
            PHASE_TIMEOUT_MS,
        )

        startPhase()
    }

    private fun startPhase() {
        val spec = when (phase) {
            SmokePhase.DIRECT -> BaselinePlaybackSpec(
                mediaUri = F1_URI,
                mode = BaselineMode.DIRECT,
                cacheState = BaselineCacheState.NONE,
                transport = BaselineTransport.RECOMMENDED_PLATFORM,
            )

            SmokePhase.CACHE_COLD -> BaselinePlaybackSpec(
                mediaUri = F1_URI,
                mode = BaselineMode.STANDARD_CACHE,
                cacheState = BaselineCacheState.COLD,
                transport = BaselineTransport.DEFAULT_HTTP,
            )

            SmokePhase.CACHE_WARM -> BaselinePlaybackSpec(
                mediaUri = F1_URI,
                mode = BaselineMode.STANDARD_CACHE,
                cacheState = BaselineCacheState.WARM,
                transport = BaselineTransport.DEFAULT_HTTP,
            )

            SmokePhase.OFFLINE_ERROR -> BaselinePlaybackSpec(
                mediaUri = F1_URI,
                mode = BaselineMode.DIRECT,
                cacheState = BaselineCacheState.NONE,
                transport = BaselineTransport.DEFAULT_HTTP,
            )
        }

        prepareExecutor.execute {
            val result = runCatching {
                BaselinePlayback.prepare(applicationContext, spec)
            }

            runOnUiThread {
                result.fold(
                    onSuccess = { prepared ->
                        try {
                            val created = BaselinePlayback.createSession(
                                context = this,
                                prepared = prepared,
                            )
                            session = created
                            attachListener(created)
                            created.player.volume = 0f
                            created.player.prepare()
                        } catch (throwable: Throwable) {
                            prepared.close()
                            fail("create_session", throwable)
                        }
                    },
                    onFailure = { throwable ->
                        fail("prepare", throwable)
                    },
                )
            }
        }
    }

    private fun attachListener(current: BaselinePlaybackSession) {
        current.player.addListener(object : Player.Listener {
            private var started = false

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_READY || started || completed.get()) {
                    return
                }
                started = true

                when (phase) {
                    SmokePhase.DIRECT -> exerciseDirect(current)
                    SmokePhase.CACHE_COLD -> exerciseColdCache(current)
                    SmokePhase.CACHE_WARM -> exerciseWarmCache(current)
                    SmokePhase.OFFLINE_ERROR -> fail(
                        "unexpected_ready",
                        IllegalStateException("DIRECT became READY with origin offline"),
                    )
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (completed.get()) {
                    return
                }

                if (phase == SmokePhase.OFFLINE_ERROR) {
                    pass(
                        "errorCode=${error.errorCodeName} " +
                            "effective=${current.identity.effectiveTransport}",
                    )
                } else {
                    fail("player_error_${error.errorCodeName}", error)
                }
            }
        })
    }

    private fun exerciseDirect(current: BaselinePlaybackSession) {
        current.player.play()

        handler.postDelayed({
            if (completed.get()) return@postDelayed

            current.player.pause()
            val beforeForwardSeek = current.player.currentPosition
            current.player.seekTo(5_000)
            current.player.play()

            handler.postDelayed({
                if (completed.get()) return@postDelayed

                val afterForwardSeek = current.player.currentPosition
                current.player.seekTo(1_000)
                current.player.play()

                handler.postDelayed({
                    if (completed.get()) return@postDelayed

                    val afterBackwardSeek = current.player.currentPosition
                    current.player.pause()

                    if (afterForwardSeek < 4_000) {
                        fail(
                            "forward_seek_not_observed",
                            IllegalStateException("position=$afterForwardSeek"),
                        )
                        return@postDelayed
                    }
                    if (afterBackwardSeek > 3_500) {
                        fail(
                            "backward_seek_not_observed",
                            IllegalStateException("position=$afterBackwardSeek"),
                        )
                        return@postDelayed
                    }

                    pass(
                        "effective=${current.identity.effectiveTransport} " +
                            "beforeForward=$beforeForwardSeek " +
                            "afterForward=$afterForwardSeek " +
                            "afterBackward=$afterBackwardSeek",
                    )
                }, DIRECT_SETTLE_MS)
            }, DIRECT_SETTLE_MS)
        }, DIRECT_SETTLE_MS)
    }

    private fun exerciseColdCache(current: BaselinePlaybackSession) {
        if (current.cacheBytesAtPreparation != 0L) {
            fail(
                "cold_cache_not_empty",
                IllegalStateException(
                    "bytesAtPreparation=${current.cacheBytesAtPreparation}",
                ),
            )
            return
        }

        current.player.play()

        handler.postDelayed({
            if (completed.get()) return@postDelayed

            current.player.pause()
            val bytes = current.cacheBytesNow()
            val position = current.player.currentPosition

            if (bytes < MIN_RETAINED_CACHE_BYTES) {
                fail(
                    "cold_cache_too_small",
                    IllegalStateException("cacheBytes=$bytes"),
                )
                return@postDelayed
            }

            pass("cacheBytes=$bytes positionMs=$position")
        }, CACHE_FILL_MS)
    }

    private fun exerciseWarmCache(current: BaselinePlaybackSession) {
        if (current.cacheBytesAtPreparation < MIN_RETAINED_CACHE_BYTES) {
            fail(
                "warm_cache_missing_coverage",
                IllegalStateException(
                    "bytesAtPreparation=${current.cacheBytesAtPreparation}",
                ),
            )
            return
        }

        current.player.play()

        handler.postDelayed({
            if (completed.get()) return@postDelayed

            val position = current.player.currentPosition
            current.player.pause()

            if (position < MIN_WARM_PLAYBACK_MS) {
                fail(
                    "warm_cache_did_not_advance",
                    IllegalStateException("positionMs=$position"),
                )
                return@postDelayed
            }

            pass(
                "cacheBytesAtPreparation=${current.cacheBytesAtPreparation} " +
                    "positionMs=$position",
            )
        }, WARM_PLAY_MS)
    }

    private fun pass(details: String) {
        complete("PASS", details)
    }

    private fun fail(reason: String, throwable: Throwable) {
        complete(
            "FAIL",
            "reason=$reason type=${throwable::class.java.simpleName} " +
                "message=${throwable.message ?: "none"}",
        )
    }

    private fun complete(status: String, details: String) {
        if (!completed.compareAndSet(false, true)) {
            return
        }

        handler.removeCallbacksAndMessages(null)
        Log.i(
            TAG,
            "M0C_SMOKE_RESULT phase=${phase.name} status=$status $details",
        )

        session?.close()
        session = null
        prepareExecutor.shutdownNow()

        setResult(if (status == "PASS") RESULT_OK else RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        if (!completed.get()) {
            completed.set(true)
            handler.removeCallbacksAndMessages(null)
            session?.close()
            session = null
            prepareExecutor.shutdownNow()
        }
        super.onDestroy()
    }

    private enum class SmokePhase {
        DIRECT,
        CACHE_COLD,
        CACHE_WARM,
        OFFLINE_ERROR,
    }

    private companion object {
        const val TAG = "SpongeM0C"
        const val EXTRA_PHASE = "phase"
        const val F1_URI = "http://localhost:18080/fixtures/F1/manifest.mpd"

        const val PHASE_TIMEOUT_MS = 35_000L
        const val DIRECT_SETTLE_MS = 2_000L
        const val CACHE_FILL_MS = 6_000L
        const val WARM_PLAY_MS = 3_000L

        const val MIN_RETAINED_CACHE_BYTES = 128L * 1024L
        const val MIN_WARM_PLAYBACK_MS = 1_000L
    }
}
