package io.github.definitelystable.spongetube.playback.baseline

enum class BaselineMode {
    DIRECT,
    STANDARD_CACHE,
}

enum class BaselineCacheState {
    NONE,
    COLD,
    WARM,
}

enum class BaselineTransport {
    RECOMMENDED_PLATFORM,
    DEFAULT_HTTP,
}

enum class EffectiveTransport {
    HTTP_ENGINE,
    DEFAULT_HTTP,
}

data class BaselinePlaybackSpec(
    val mediaUri: String,
    val mode: BaselineMode,
    val cacheState: BaselineCacheState,
    val transport: BaselineTransport,
) {
    init {
        require(mediaUri.isNotBlank()) {
            "mediaUri must not be blank"
        }

        when (mode) {
            BaselineMode.DIRECT -> require(cacheState == BaselineCacheState.NONE) {
                "DIRECT requires cacheState=NONE"
            }

            BaselineMode.STANDARD_CACHE -> require(
                cacheState == BaselineCacheState.COLD ||
                    cacheState == BaselineCacheState.WARM,
            ) {
                "STANDARD_CACHE requires cacheState=COLD or WARM"
            }
        }
    }
}

data class BaselineTransportResolution(
    val requested: BaselineTransport,
    val effective: EffectiveTransport,
)

data class BaselinePlaybackIdentity(
    val mode: BaselineMode,
    val cacheState: BaselineCacheState,
    val requestedTransport: BaselineTransport,
    val effectiveTransport: EffectiveTransport,
)

object BaselineTransportPolicy {
    const val HTTP_ENGINE_MIN_API = 34
    const val HTTP_ENGINE_S_EXTENSION = 7

    fun resolve(
        requested: BaselineTransport,
        sdkInt: Int,
        sExtensionVersion: Int,
    ): EffectiveTransport {
        if (requested == BaselineTransport.DEFAULT_HTTP) {
            return EffectiveTransport.DEFAULT_HTTP
        }

        return if (
            sdkInt >= HTTP_ENGINE_MIN_API ||
            (sdkInt >= 31 && sExtensionVersion >= HTTP_ENGINE_S_EXTENSION)
        ) {
            EffectiveTransport.HTTP_ENGINE
        } else {
            EffectiveTransport.DEFAULT_HTTP
        }
    }
}

object BaselineCacheContract {
    const val DIRECTORY_NAME = "m0-standard-cache"
    const val QUOTA_BYTES = 64L * 1024L * 1024L
}
