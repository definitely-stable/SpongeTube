package io.github.definitelystable.spongetube.playback.bridge

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import io.github.definitelystable.spongetube.core.engine.PlaybackBridgeException
import io.github.definitelystable.spongetube.core.engine.PlaybackBridgeFailure
import io.github.definitelystable.spongetube.core.engine.SpongeBridgeApi
import java.io.IOException
import java.io.InterruptedIOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(SpongeBridgeApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
class SpongeLoadErrorHandlingPolicyTest {
    private val policy = SpongeLoadErrorHandlingPolicy(
        SpongeLoadErrorHandlingPolicy.Config(maxRetries = 3, retryDelayMs = 1_000),
    )

    @Test
    fun retryableFetchFailuresAreRetriedAtMostMaxRetriesTimes() {
        val error = fetchFailure("RETRYABLE_TRANSPORT_FAILURE")

        val delays = (1..10).map { errorCount -> policy.retryDelayMsFor(error, errorCount) }

        assertEquals(listOf(1_000L, 1_000L, 1_000L), delays.take(3))
        assertEquals(List(7) { C.TIME_UNSET }, delays.drop(3))
        assertEquals(3, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }

    @Test
    fun nonTransientFailuresAreFatalImmediately() {
        val fatal = listOf(
            fetchFailure("RANGE_REJECTED"),
            fetchFailure("CONTENT_INTEGRITY_REJECTED"),
            fetchFailure("STORAGE_IO"),
            fetchFailure("DESCRIPTOR_STALE"),
            PlaybackBridgeException(
                failure = PlaybackBridgeFailure.UNRESOLVABLE_COVERAGE,
                message = "never ready",
            ),
            SpongeUnsupportedUriException("http://x/y", "scheme"),
            InterruptedIOException("cancelled"),
            IOException("unknown"),
        )

        fatal.forEach { error ->
            assertEquals(C.TIME_UNSET, policy.retryDelayMsFor(error, 1), error.toString())
        }
    }

    @Test
    fun zeroRetriesMakesEveryErrorFatal() {
        val none = SpongeLoadErrorHandlingPolicy(
            SpongeLoadErrorHandlingPolicy.Config(maxRetries = 0, retryDelayMs = 500),
        )

        assertEquals(
            C.TIME_UNSET,
            none.retryDelayMsFor(fetchFailure("RETRYABLE_TRANSPORT_FAILURE"), 1),
        )
    }

    @Test
    fun configIsRecordedAsProvisionalEvidence() {
        val artifact = policy.config.toArtifactMap()

        assertEquals("PROVISIONAL", artifact["status"])
        assertEquals(3, artifact["maxRetries"])
        assertEquals(1_000L, artifact["retryDelayMs"])
    }

    private fun fetchFailure(outcome: String): PlaybackBridgeException =
        PlaybackBridgeException(
            failure = PlaybackBridgeFailure.FETCH_FAILED,
            fetchOutcome = outcome,
            message = "fetch ended with $outcome",
        )
}
