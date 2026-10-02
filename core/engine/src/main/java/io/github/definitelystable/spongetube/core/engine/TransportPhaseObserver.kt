package io.github.definitelystable.spongetube.core.engine

/**
 * Measurement-only transport phase seam used by M2-G.
 *
 * The executor reports phase boundaries only; the caller owns the clock domain.
 * No route, URL, Network, retry policy or provider material crosses this seam.
 */
internal enum class TransportPhaseKind {
    RESPONSE_HEADERS,
    FIRST_BODY_BYTES,
    RESPONSE_BODY_COMPLETE,
}

internal data class TransportPhaseObservation(
    val fetchKey: FetchKey,
    val attempt: Int,
    val kind: TransportPhaseKind,
) {
    init {
        require(attempt >= 1)
    }
}

internal fun interface TransportPhaseObserver {
    fun onPhase(observation: TransportPhaseObservation)

    companion object {
        val NONE = TransportPhaseObserver { }
    }
}
