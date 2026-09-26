package io.github.definitelystable.spongetube.core.engine.delivery

/** Chain-scoped and operation-completion kinds of `delivery-binding-events-v1`. */
internal enum class DeliveryBindingEventKind {
    BINDING_SELECTED_FOR_ATTEMPT,
    REFRESH_REQUESTED,
    REFRESH_STARTED,
    REFRESH_JOINED,
    REVISION_ALREADY_ADVANCED,
    REFRESH_NOT_ADMITTED,
    REFRESH_CLOSED,
    REFRESH_SUCCEEDED,
    REFRESH_FAILED,
    REFRESH_INCOMPATIBLE,
    REFRESH_CANCELLED,
}

/** Outcome of one actual provider refresh operation. */
internal enum class DeliveryBindingOperationOutcome {
    SUCCEEDED,
    FAILED,
    INCOMPATIBLE,
    CANCELLED,
}

/**
 * One `delivery-binding-events-v1` row. All keys are always present; the
 * revision fields are opaque ids and the row never carries material, URL,
 * header or token text.
 */
internal data class DeliveryBindingEvent(
    val sequence: Long,
    val elapsedRealtimeNs: Long,
    val recoveryChainId: String,
    val failureId: String?,
    val fetchKey: String,
    val extentId: String,
    val kind: DeliveryBindingEventKind,
    val previousRevision: DeliveryBindingRevision? = null,
    val currentRevision: DeliveryBindingRevision? = null,
    val attemptCorrelationId: String? = null,
    val refreshCorrelationId: String? = null,
    val outcome: DeliveryBindingOperationOutcome? = null,
) {
    fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
        "sequence" to sequence,
        "elapsedRealtimeNs" to elapsedRealtimeNs,
        "recoveryChainId" to recoveryChainId,
        "failureId" to failureId,
        "fetchKey" to fetchKey,
        "extentId" to extentId,
        "kind" to kind.name,
        "previousRevision" to previousRevision?.value,
        "currentRevision" to currentRevision?.value,
        "attemptCorrelationId" to attemptCorrelationId,
        "refreshCorrelationId" to refreshCorrelationId,
        "outcome" to outcome?.name,
    )
}

/**
 * Receives every delivery binding event. The coordinator invokes it under its
 * own lock inside `runCatching`, so a throwing listener never breaks a
 * refresh.
 */
internal fun interface DeliveryBindingEvidenceListener {
    fun onDeliveryBindingEvent(event: DeliveryBindingEvent)
}

/**
 * Bounded producer of `delivery-binding-events-v1`. Exceeding [capacity]
 * marks the artifact unusable instead of buffering without bound (same
 * pattern as the recovery evidence recorder).
 */
internal class DeliveryBindingEvidenceRecorder(
    private val runId: String,
    private val sessionId: String,
    private val capacity: Int = DEFAULT_CAPACITY,
) : DeliveryBindingEvidenceListener {
    private val lock = Any()
    private val events = ArrayList<DeliveryBindingEvent>()
    private var overflowed = false

    init {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(capacity > 0) { "capacity must be > 0" }
    }

    override fun onDeliveryBindingEvent(event: DeliveryBindingEvent) {
        synchronized(lock) {
            if (events.size >= capacity) {
                overflowed = true
            } else {
                events += event
            }
        }
    }

    fun events(): List<DeliveryBindingEvent> = synchronized(lock) { events.toList() }

    fun artifact(): Map<String, Any?> = synchronized(lock) {
        check(!overflowed) { "delivery binding evidence exceeded capacity $capacity" }
        linkedMapOf(
            "schemaVersion" to SCHEMA_VERSION,
            "runId" to runId,
            "sessionId" to sessionId,
            "clockDomain" to CLOCK_DOMAIN,
            "events" to events.map(DeliveryBindingEvent::toArtifactMap),
        )
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val CLOCK_DOMAIN = "ANDROID_MONOTONIC"
        const val DEFAULT_CAPACITY = 8_192
    }
}
