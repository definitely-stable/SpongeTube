package io.github.definitelystable.spongetube.core.engine

/**
 * Marks the narrow engine-side seam used by the Media3 PlaybackBridge
 * adapter (`:playback:bridge`).
 *
 * FetchBroker/FetchRequest/executor contracts stay `internal` until the
 * YouTube delivery path (#50) settles; this seam exposes only resource/byte
 * reads over published coverage plus bridge evidence. Opting in outside the
 * bridge adapter and its tests is a review blocker.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "PlaybackBridge engine seam: only :playback:bridge may use it " +
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
