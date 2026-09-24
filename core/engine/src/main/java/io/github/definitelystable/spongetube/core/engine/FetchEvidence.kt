package io.github.definitelystable.spongetube.core.engine

internal enum class FetchEventKind {
    OWNER_REGISTERED,
    CONSUMER_JOINED,
    PRIORITY_RAISED,
    ATTEMPT_STARTED,
    ATTEMPT_CORRELATED,
    ATTEMPT_PROGRESS,
    ATTEMPT_COMPLETED,
    ATTEMPT_FAILED,
    CONSUMER_RELEASED,
    OWNER_COMPLETED,
    OWNER_FAILED,
    OWNER_CANCELLED,
}

internal data class FetchEvent(
    val eventSequence: Long,
    val eventElapsedRealtimeNs: Long,
    val sessionId: String,
    val fetchId: FetchId,
    val fetchKey: FetchKey,
    val attempt: Int?,
    val attemptCorrelationId: String?,
    val transportCorrelationId: String?,
    val event: FetchEventKind,
    val consumerIds: List<String>,
    val effectivePriority: FetchPriority,
    val requestedByteStart: Long?,
    val requestedByteEndExclusive: Long?,
    val chunkByteStart: Long?,
    val chunkByteEndExclusive: Long?,
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
        require((chunkByteStart == null) == (chunkByteEndExclusive == null))
        if (chunkByteStart != null && chunkByteEndExclusive != null) {
            require(chunkByteStart >= 0)
            require(chunkByteEndExclusive > chunkByteStart)
            require(attempt != null) { "chunk evidence requires an attempt" }
        }
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
        "schemaVersion" to 3,
        "eventSequence" to eventSequence,
        "eventElapsedRealtimeNs" to eventElapsedRealtimeNs,
        "sessionId" to sessionId,
        "fetchId" to fetchId.value,
        "fetchKey" to fetchKey.value,
        "attempt" to attempt,
        "attemptCorrelationId" to attemptCorrelationId,
        "transportCorrelationId" to transportCorrelationId,
        "event" to event.name,
        "consumerIds" to consumerIds,
        "effectivePriority" to effectivePriority.name,
        "requestedByteStart" to requestedByteStart,
        "requestedByteEndExclusive" to requestedByteEndExclusive,
        "chunkByteStart" to chunkByteStart,
        "chunkByteEndExclusive" to chunkByteEndExclusive,
        "networkBytes" to networkBytes,
        "uniqueRangeBytes" to uniqueRangeBytes,
        "duplicateRangeBytes" to duplicateRangeBytes,
        "rejectedOrUnmappedBytes" to rejectedOrUnmappedBytes,
        "singleFlightJoined" to singleFlightJoined,
        "outcome" to outcome?.name,
    )
}

internal fun interface FetchEventListener {
    fun onEvent(event: FetchEvent)
}
