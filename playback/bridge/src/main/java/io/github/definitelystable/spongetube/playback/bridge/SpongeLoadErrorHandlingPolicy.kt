package io.github.definitelystable.spongetube.playback.bridge

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import io.github.definitelystable.spongetube.core.engine.SpongeBridgeApi
import java.io.IOException

/**
 * Media3 load-error policy for PlaybackBridge loads (ADR-0002, superseded in
 * part by ADR-0003 / M2-C).
 *
 * Retry ownership belongs only to the Sponge Core RecoveryCoordinator: a
 * PlaybackBridge fetch failure reaches Media3 only after the RecoveryChain
 * for that immutable work is terminal (bounded budget exhausted, terminal
 * classification, no demand or session end). The loader therefore never
 * retries, never reopens the DataSource to start a new RecoveryChain and
 * never selects a fallback: [getRetryDelayMsFor] always returns
 * [C.TIME_UNSET] (fatal) and [getMinimumLoadableRetryCount] is 0.
 *
 * Media3's `errorCount` counts errors of one load task; it is never used as a
 * recovery budget, and no chain state lives here (`onLoadTaskConcluded` keeps
 * the no-op default). Every load of the Sponge player goes through
 * [SpongeDataSource], so every load error is Sponge-managed. Media3 buffer
 * constants are untouched.
 */
@UnstableApi
@OptIn(SpongeBridgeApi::class)
class SpongeLoadErrorHandlingPolicy : LoadErrorHandlingPolicy {
    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? = null

    override fun getRetryDelayMsFor(
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): Long = retryDelayMsFor(loadErrorInfo.exception)

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = 0

    /** Always [C.TIME_UNSET]: Media3 treats the error as fatal. */
    @Suppress("UNUSED_PARAMETER")
    fun retryDelayMsFor(exception: IOException): Long = C.TIME_UNSET

    /** Evidence form of the policy; there are no tunable retry parameters. */
    fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
        "status" to STATUS,
        "retryOwner" to RETRY_OWNER,
        "loaderRetry" to false,
        "minimumLoadableRetryCount" to 0,
        "fallback" to false,
    )

    companion object {
        const val STATUS = "M2_C_NO_LOADER_RETRY"
        const val RETRY_OWNER = "RecoveryCoordinator"
    }
}
