package io.github.definitelystable.spongetube.core.engine

/**
 * Marks the narrow engine-side seam used by the Media3 PlaybackBridge
 * adapter (`:playback:bridge`).
 *
 * FetchBroker/FetchRequest/executor contracts stay `internal` until the
 * YouTube delivery path (#50) settles; this seam exposes only resource/byte
 * reads over published coverage plus bridge evidence. Opting in outside the
 * bridge adapter, its tests, and the isolated debug-only M4 laboratory is a
 * review blocker. The debug laboratory does not ship in release or benchmark.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "PlaybackBridge engine seam: only the bridge, tests and debug M4 lab may use it " +
        "before the Sponge Core API is stabilized (#50).",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
annotation class SpongeBridgeApi
