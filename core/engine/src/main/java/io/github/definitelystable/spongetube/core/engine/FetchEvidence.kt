package io.github.definitelystable.spongetube.core.engine

enum class FetchEventKind {
    OWNER_REGISTERED,
    CONSUMER_JOINED,
    PRIORITY_RAISED,
    ATTEMPT_STARTED,
    ATTEMPT_COMPLETED,
    ATTEMPT_FAILED,
    CONSUMER_RELEASED,
    OWNER_COMPLETED,
    OWNER_FAILED,
    OWNER_CANCELLED,
}

data class FetchEvent(
    val eventSequence: Long,
    val eventElapsedRealtimeNs: Long,
    val sessionId: String,
    val fetchId: FetchId,
    val fetchKey: FetchKey,
    val attempt: Int?,
    val attemptCorrelationId: String?,
    val event: FetchEventKind,
    val consumerIds: List<String>,
    val effectivePriority: FetchPriority,
    val requestedByteStart: Long?,
    val requestedByteEndExclusive: Long?,
    val networkBytes: Long,
    val uniqueRangeBytes: Long,
    val duplicateRangeBytes: Long,
    val rejectedOrUnmappedBytes: Long,
    val singleFlightJoined: Boolean,
    val outcome: FetchOutcomeKind?,
) {
    init {
        require(eventSequence >= 0)
        require(eventElapsedRealtimeNs >= 0)
        require(sessionId.isNotBlank())
        require(attempt == null || attempt >= 1)
        require((attempt == null) == (attemptCorrelationId == null))
        require(networkBytes >= 0)
        require(uniqueRangeBytes >= 0)
        require(duplicateRangeBytes >= 0)
        require(rejectedOrUnmappedBytes >= 0)
        require(
            networkBytes ==
                uniqueRangeBytes +
                duplicateRangeBytes +
                rejectedOrUnmappedBytes,
        )
    }

    fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to 2,
        "eventSequence" to eventSequence,
        "eventElapsedRealtimeNs" to eventElapsedRealtimeNs,
        "sessionId" to sessionId,
        "fetchId" to fetchId.value,
        "fetchKey" to fetchKey.value,
        "attempt" to attempt,
        "attemptCorrelationId" to attemptCorrelationId,
        "event" to event.name,
        "consumerIds" to consumerIds,
        "effectivePriority" to effectivePriority.name,
        "requestedByteStart" to requestedByteStart,
        "requestedByteEndExclusive" to requestedByteEndExclusive,
        "networkBytes" to networkBytes,
        "uniqueRangeBytes" to uniqueRangeBytes,
        "duplicateRangeBytes" to duplicateRangeBytes,
        "rejectedOrUnmappedBytes" to rejectedOrUnmappedBytes,
        "singleFlightJoined" to singleFlightJoined,
        "outcome" to outcome?.name,
    )
}

fun interface FetchEventListener {
    fun onEvent(event: FetchEvent)
}
