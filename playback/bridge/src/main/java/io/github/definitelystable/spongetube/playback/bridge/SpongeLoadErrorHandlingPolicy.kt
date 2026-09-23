package io.github.definitelystable.spongetube.playback.bridge

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import io.github.definitelystable.spongetube.core.engine.PlaybackBridgeException
import io.github.definitelystable.spongetube.core.engine.PlaybackBridgeFailure
import io.github.definitelystable.spongetube.core.engine.SpongeBridgeApi
import java.io.IOException

/**
 * Explicit, bounded Media3 retry policy for PlaybackBridge loads (M1-E D7).
 *
 * Every Media3 retry re-opens the DataSource, which acquires a fresh
 * FetchBroker owner with a fresh attempt budget. Without this policy Media3's
 * default policy would retry indefinitely and silently multiply origin
 * attempts. Here only bridge fetch failures whose cause may clear (transport
 * failures, a cancelled shared owner) are retried, at most
 * [Config.maxRetries] times with a fixed delay; everything else is fatal and
 * surfaces as a player error. No fallback/exclusion is ever selected.
 *
 * Parameters are Provisional and are recorded in evidence; M1-F proves
 * boundedness under N4. Media3 buffer constants are untouched.
 */
@UnstableApi
@OptIn(SpongeBridgeApi::class)
class SpongeLoadErrorHandlingPolicy(
    val config: Config = Config(),
) : LoadErrorHandlingPolicy {
    data class Config(
        val maxRetries: Int = 3,
        val retryDelayMs: Long = 1_000L,
    ) {
        init {
            require(maxRetries >= 0) { "maxRetries must be >= 0" }
            require(retryDelayMs > 0) { "retryDelayMs must be > 0" }
        }

        fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
            "status" to "PROVISIONAL",
            "maxRetries" to maxRetries,
            "retryDelayMs" to retryDelayMs,
            "retryableFetchOutcomes" to RETRYABLE_FETCH_OUTCOMES.sorted(),
        )
    }

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? = null

    override fun getRetryDelayMsFor(
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): Long = retryDelayMsFor(loadErrorInfo.exception, loadErrorInfo.errorCount)

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = config.maxRetries

    /**
     * [errorCount] counts errors of this load including the current one.
     * Returns [C.TIME_UNSET] when the error must be treated as fatal.
     */
    fun retryDelayMsFor(
        exception: IOException,
        errorCount: Int,
    ): Long =
        if (isRetryable(exception) && errorCount <= config.maxRetries) {
            config.retryDelayMs
        } else {
            C.TIME_UNSET
        }

    companion object {
        val RETRYABLE_FETCH_OUTCOMES: Set<String> = setOf(
            "RETRYABLE_TRANSPORT_FAILURE",
            "TERMINAL_TRANSPORT_FAILURE",
            "CANCELLED_NO_CONSUMERS",
        )

        fun isRetryable(exception: IOException): Boolean =
            exception is PlaybackBridgeException &&
                exception.failure == PlaybackBridgeFailure.FETCH_FAILED &&
                exception.fetchOutcome in RETRYABLE_FETCH_OUTCOMES
    }
}
