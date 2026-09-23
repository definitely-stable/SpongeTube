package io.github.definitelystable.spongetube.playback.baseline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BaselineContractsTest {

    @Test
    fun directRequiresNoCache() {
        val spec = BaselinePlaybackSpec(
            mediaUri = "http://localhost/f1.mpd",
            mode = BaselineMode.DIRECT,
            cacheState = BaselineCacheState.NONE,
            transport = BaselineTransport.RECOMMENDED_PLATFORM,
        )

        assertEquals(BaselineCacheState.NONE, spec.cacheState)

        assertThrows(IllegalArgumentException::class.java) {
            BaselinePlaybackSpec(
                mediaUri = "http://localhost/f1.mpd",
                mode = BaselineMode.DIRECT,
                cacheState = BaselineCacheState.COLD,
                transport = BaselineTransport.DEFAULT_HTTP,
            )
        }
    }

    @Test
    fun standardCacheRequiresColdOrWarm() {
        for (state in listOf(BaselineCacheState.COLD, BaselineCacheState.WARM)) {
            val spec = BaselinePlaybackSpec(
                mediaUri = "http://localhost/f1.mpd",
                mode = BaselineMode.STANDARD_CACHE,
                cacheState = state,
                transport = BaselineTransport.DEFAULT_HTTP,
            )
            assertEquals(state, spec.cacheState)
        }

        assertThrows(IllegalArgumentException::class.java) {
            BaselinePlaybackSpec(
                mediaUri = "http://localhost/f1.mpd",
                mode = BaselineMode.STANDARD_CACHE,
                cacheState = BaselineCacheState.NONE,
                transport = BaselineTransport.DEFAULT_HTTP,
            )
        }
    }

    @Test
    fun recommendedPlatformUsesHttpEngineOnlyWhenRuntimeSupportsIt() {
        assertEquals(
            EffectiveTransport.HTTP_ENGINE,
            BaselineTransportPolicy.resolve(
                BaselineTransport.RECOMMENDED_PLATFORM,
                sdkInt = 34,
                sExtensionVersion = 0,
            ),
        )
        assertEquals(
            EffectiveTransport.HTTP_ENGINE,
            BaselineTransportPolicy.resolve(
                BaselineTransport.RECOMMENDED_PLATFORM,
                sdkInt = 31,
                sExtensionVersion = 7,
            ),
        )
        assertEquals(
            EffectiveTransport.DEFAULT_HTTP,
            BaselineTransportPolicy.resolve(
                BaselineTransport.RECOMMENDED_PLATFORM,
                sdkInt = 33,
                sExtensionVersion = 6,
            ),
        )
        assertEquals(
            EffectiveTransport.DEFAULT_HTTP,
            BaselineTransportPolicy.resolve(
                BaselineTransport.DEFAULT_HTTP,
                sdkInt = 36,
                sExtensionVersion = 99,
            ),
        )
    }

    @Test
    fun httpEngineShutdownBarrierKeepsCallbacksAliveUntilRequestsDrain() {
        var shutdownAttempts = 0
        var callbackExecutorShutdowns = 0
        val scheduled = ArrayDeque<() -> Unit>()

        val barrier = RetryingShutdownBarrier(
            shutdown = {
                shutdownAttempts += 1
                if (shutdownAttempts < 3) {
                    throw IllegalStateException("active request")
                }
            },
            afterShutdown = {
                callbackExecutorShutdowns += 1
            },
            scheduleRetry = { retry ->
                scheduled.addLast(retry)
            },
        )

        barrier.run()

        assertEquals(1, shutdownAttempts)
        assertEquals(0, callbackExecutorShutdowns)
        assertEquals(1, scheduled.size)

        while (scheduled.isNotEmpty()) {
            scheduled.removeFirst().invoke()
        }

        assertEquals(3, shutdownAttempts)
        assertEquals(1, callbackExecutorShutdowns)

        barrier.run()
        assertEquals(3, shutdownAttempts)
        assertEquals(1, callbackExecutorShutdowns)
    }

    @Test
    fun cacheQuotaComfortablyContainsCanonicalF1() {
        val canonicalF1Bytes = 13_956_166L
        assertTrue(BaselineCacheContract.QUOTA_BYTES > canonicalF1Bytes * 4L)
    }
}
