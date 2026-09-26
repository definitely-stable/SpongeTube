package io.github.definitelystable.spongetube.core.engine.delivery

/**
 * PROVIDER_WALL_CLOCK source (M2.md 13): provider expiry, HTTP-date and the
 * provider virtual wall clock.
 *
 * Values of this domain are never compared with, ordered against, or
 * subtracted from Android elapsed realtime or any other clock domain. The
 * injectable seam keeps tests deterministic and lets the host lab expose its
 * virtual provider wall clock.
 */
internal fun interface ProviderWallClock {
    fun nowUtcEpochMs(): Long

    companion object {
        val SYSTEM: ProviderWallClock = ProviderWallClock { System.currentTimeMillis() }
    }
}
