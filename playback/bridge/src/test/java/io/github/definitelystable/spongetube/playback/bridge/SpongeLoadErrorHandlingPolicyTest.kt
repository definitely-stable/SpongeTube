package io.github.definitelystable.spongetube.playback.bridge

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import io.github.definitelystable.spongetube.core.engine.PlaybackBridgeException
import io.github.definitelystable.spongetube.core.engine.PlaybackBridgeFailure
import io.github.definitelystable.spongetube.core.engine.SpongeBridgeApi
import java.io.IOException
import java.io.InterruptedIOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** M2-C Media3 no-retry rule (C-21..C-24). */
@OptIn(SpongeBridgeApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
class SpongeLoadErrorHandlingPolicyTest {
    private val policy = SpongeLoadErrorHandlingPolicy()

    // C-21
    @Test
    fun recoveryTerminalFailuresAreFatalForTheLoaderOnEveryErrorCount() {
        val failures = listOf(
            fetchFailure("RETRYABLE_TRANSPORT_FAILURE", "BUDGET_EXHAUSTED"),
            fetchFailure("TERMINAL_TRANSPORT_FAILURE", "TERMINAL_FAILURE"),
            fetchFailure("CANCELLED_NO_CONSUMERS", "NO_REMAINING_DEMAND"),
            fetchFailure("RANGE_REJECTED", "TERMINAL_FAILURE"),
            fetchFailure("DESCRIPTOR_STALE", "TERMINAL_FAILURE"),
            PlaybackBridgeException(
                failure = PlaybackBridgeFailure.UNRESOLVABLE_COVERAGE,
                message = "never ready",
            ),
            SpongeUnsupportedUriException("http://x/y", "scheme"),
            InterruptedIOException("cancelled"),
            IOException("unknown"),
        )

        failures.forEach { error ->
            // errorCount is Media3 load-task local and never a budget.
            (1..10).forEach { errorCount ->
                assertEquals(
                    C.TIME_UNSET,
                    policy.getRetryDelayMsFor(loadErrorInfo(error, errorCount)),
                    "$error #$errorCount",
                )
            }
        }
    }

    // C-22
    @Test
    fun minimumLoadableRetryCountIsZeroForEveryDataType() {
        listOf(
            C.DATA_TYPE_MEDIA,
            C.DATA_TYPE_MEDIA_INITIALIZATION,
            C.DATA_TYPE_MANIFEST,
            C.DATA_TYPE_UNKNOWN,
        ).forEach { type ->
            assertEquals(0, policy.getMinimumLoadableRetryCount(type))
        }
    }

    // C-23
    @Test
    fun noFallbackIsEverSelected() {
        val options = LoadErrorHandlingPolicy.FallbackOptions(4, 1, 3, 0)

        assertNull(
            policy.getFallbackSelectionFor(
                options,
                loadErrorInfo(fetchFailure("RETRYABLE_TRANSPORT_FAILURE", "BUDGET_EXHAUSTED"), 1),
            ),
        )
    }

    // C-24
    @Test
    fun legacyMaxRetriesConfigIsGone() {
        val nested = SpongeLoadErrorHandlingPolicy::class.java.declaredClasses.map { it.simpleName }
        val methods = SpongeLoadErrorHandlingPolicy::class.java.methods.map { it.name }

        assertFalse("Config" in nested, nested.toString())
        assertFalse(methods.any { it.contains("MaxRetries") || it == "getConfig" }, methods.toString())
        val artifact = policy.toArtifactMap()
        assertEquals("M2_C_NO_LOADER_RETRY", artifact["status"])
        assertEquals("RecoveryCoordinator", artifact["retryOwner"])
        assertEquals(false, artifact["loaderRetry"])
        assertFalse("maxRetries" in artifact)
        assertFalse("retryDelayMs" in artifact)
    }

    /**
     * Builds a LoadErrorInfo carrying only the exception and error count;
     * the load/media descriptors are irrelevant to this policy and would
     * need Android framework types in a host test.
     */
    private fun loadErrorInfo(
        exception: IOException,
        errorCount: Int,
    ): LoadErrorHandlingPolicy.LoadErrorInfo =
        LoadErrorHandlingPolicy.LoadErrorInfo::class.java.constructors
            .single { it.parameterCount == 4 }
            .newInstance(null, null, exception, errorCount)
            as LoadErrorHandlingPolicy.LoadErrorInfo

    private fun fetchFailure(
        outcome: String,
        terminal: String,
    ): PlaybackBridgeException =
        PlaybackBridgeException(
            failure = PlaybackBridgeFailure.FETCH_FAILED,
            fetchOutcome = outcome,
            recoveryTerminalReason = terminal,
            message = "recovery ended with $terminal",
        )
}
