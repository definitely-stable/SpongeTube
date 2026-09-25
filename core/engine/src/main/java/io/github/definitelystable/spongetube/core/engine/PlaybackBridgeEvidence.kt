package io.github.definitelystable.spongetube.core.engine

import java.io.IOException

@SpongeBridgeApi
enum class PlaybackBridgeEventKind {
    OPEN,
    LOCAL_SERVE,
    MISS,
    JOIN,
    WAIT_EXISTING,
    DEPENDENCY_WAIT,
    FETCH_WAIT_END,
    CLOSE,
    HARNESS_MARKER,
}

/**
 * One `bridge-events-v1` row.
 *
 * Timestamps share the Android `elapsedRealtimeNanos` domain with
 * `fetch-events-v2`, but acceptance never compares clocks: MISS/JOIN/
 * WAIT_EXISTING rows join FetchBroker evidence by (sessionId, fetchId), and
 * harness markers carry
 * the FetchBroker event-sequence watermark observed at the marker.
 */
@SpongeBridgeApi
data class PlaybackBridgeEvent(
    val eventSequence: Long,
    val eventElapsedRealtimeNs: Long,
    val sessionId: String,
    val readId: String?,
    val resourceKey: String?,
    val requestedRangeStart: Long?,
    val requestedRangeEndExclusive: Long?,
    val event: PlaybackBridgeEventKind,
    val extentId: String?,
    val dependencyExtentId: String?,
    val fetchId: String?,
    val fetchOutcome: String?,
    val bytesLocal: Long?,
    val openReadCalls: Int?,
    val coverageRefreshCalls: Int?,
    val markerName: String?,
    val fetchEventSequenceWatermark: Long?,
) {
    init {
        require(eventSequence >= 0)
        require(eventElapsedRealtimeNs >= 0)
        require(sessionId.isNotBlank())
        if (event == PlaybackBridgeEventKind.HARNESS_MARKER) {
            require(!markerName.isNullOrBlank()) { "marker requires a name" }
        } else {
            require(!readId.isNullOrBlank()) { "read events require readId" }
        }
        if (
            event == PlaybackBridgeEventKind.MISS ||
            event == PlaybackBridgeEventKind.JOIN ||
            event == PlaybackBridgeEventKind.WAIT_EXISTING ||
            event == PlaybackBridgeEventKind.FETCH_WAIT_END
        ) {
            require(!fetchId.isNullOrBlank()) { "$event requires fetchId" }
            require(!extentId.isNullOrBlank()) { "$event requires extentId" }
        }
    }

    fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to SCHEMA_VERSION,
        "eventSequence" to eventSequence,
        "eventElapsedRealtimeNs" to eventElapsedRealtimeNs,
        "sessionId" to sessionId,
        "readId" to readId,
        "resourceKey" to resourceKey,
        "requestedRangeStart" to requestedRangeStart,
        "requestedRangeEndExclusive" to requestedRangeEndExclusive,
        "event" to event.name,
        "extentId" to extentId,
        "dependencyExtentId" to dependencyExtentId,
        "fetchId" to fetchId,
        "fetchOutcome" to fetchOutcome,
        "bytesLocal" to bytesLocal,
        "openReadCalls" to openReadCalls,
        "coverageRefreshCalls" to coverageRefreshCalls,
        "markerName" to markerName,
        "fetchEventSequenceWatermark" to fetchEventSequenceWatermark,
    )

    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

/**
 * Receives bridge evidence on the reading thread. Implementations must only
 * buffer (no I/O); serialization happens off the playback path.
 */
@SpongeBridgeApi
fun interface PlaybackBridgeEventListener {
    fun onEvent(event: PlaybackBridgeEvent)
}

/** Receives `fetch-events-v2` artifact rows from the bridge-owned broker. */
@SpongeBridgeApi
fun interface PlaybackFetchEvidenceListener {
    fun onEvent(artifact: Map<String, Any?>)
}

@SpongeBridgeApi
enum class PlaybackBridgeFailure {
    UNKNOWN_RESOURCE,
    POSITION_OUT_OF_RANGE,
    FETCH_FAILED,
    FETCH_IDENTITY_CONFLICT,
    UNRESOLVABLE_COVERAGE,
    LOCAL_READ_FAILED,
    RUNTIME_CLOSED,
}

/**
 * Typed bridge failure. For [PlaybackBridgeFailure.FETCH_FAILED],
 * [fetchOutcome] carries the legacy outcome name of the last FetchBroker owner
 * and [recoveryTerminalReason] the terminal reason of the RecoveryChain.
 *
 * Since M2-C a FETCH_FAILED is always terminal: the RecoveryCoordinator has
 * already applied its bounded policy, so the Media3 loader must not retry it.
 */
@SpongeBridgeApi
class PlaybackBridgeException(
    val failure: PlaybackBridgeFailure,
    val fetchOutcome: String? = null,
    message: String,
    cause: Throwable? = null,
    val recoveryTerminalReason: String? = null,
) : IOException(message, cause)
