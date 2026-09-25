package io.github.definitelystable.spongetube.core.engine.recovery

internal enum class RecoveryBudgetEventKind {
    CHAIN_STARTED,
    CONSUMER_JOINED,
    CONSUMER_RELEASED,
    ATTEMPT_PERMIT_WAIT,
    ATTEMPT_PERMIT_GRANTED,
    CHARGE,
    OWNER_STARTED,
    OWNER_FINISHED,
    BACKOFF_SCHEDULED,
    BACKOFF_COMPLETED,
    PRIORITY_RAISED,
    CHAIN_TERMINATED,
}

internal data class RecoveryBackoffRecord(
    val retryOrdinal: Int,
    val windowMs: Long,
    val delayMs: Long,
)

/**
 * One `recovery-budget-events-v1` row. Every row carries the chain's policy
 * identity, its limits and the ledger snapshot after the event, so the
 * verifier can prove the ledger never resets. Only [FetchKey] and `extentId`
 * identify the work; the ExtentSpec itself is not retained.
 */
internal data class RecoveryBudgetEvent(
    val sequence: Long,
    val elapsedRealtimeNs: Long,
    val sessionId: String,
    val recoveryChainId: RecoveryChainId,
    val kind: RecoveryBudgetEventKind,
    val fetchKey: String,
    val extentId: String,
    val policyId: String,
    val limits: Map<RecoveryBudgetDimension, Int>,
    val spent: Map<RecoveryBudgetDimension, Int>,
    val effectivePriority: String,
    val consumerId: String? = null,
    val consumerKind: RecoveryConsumerKind? = null,
    val priorityBefore: String? = null,
    val charge: RecoveryBudgetCharge? = null,
    val ownerOrdinal: Int? = null,
    val fetchId: String? = null,
    val attemptCorrelationId: String? = null,
    val ownerOutcome: String? = null,
    val permit: RecoveryAttemptPermit? = null,
    val backoff: RecoveryBackoffRecord? = null,
    val failureId: String? = null,
    val terminalReason: RecoveryTerminalReason? = null,
) {
    fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
        "sequence" to sequence,
        "elapsedRealtimeNs" to elapsedRealtimeNs,
        "sessionId" to sessionId,
        "recoveryChainId" to recoveryChainId.value,
        "kind" to kind.name,
        "fetchKey" to fetchKey,
        "extentId" to extentId,
        "policyId" to policyId,
        "limits" to limits.dimensionMap(),
        "spent" to spent.dimensionMap(),
        "effectivePriority" to effectivePriority,
        "consumerId" to consumerId,
        "consumerKind" to consumerKind?.name,
        "priorityBefore" to priorityBefore,
        "charge" to charge?.let {
            linkedMapOf(
                "dimension" to it.dimension.value,
                "amount" to it.amount,
                "spentBefore" to it.spentBefore,
                "spentAfter" to it.spentAfter,
                "limit" to it.limit,
            )
        },
        "ownerOrdinal" to ownerOrdinal,
        "fetchId" to fetchId,
        "attemptCorrelationId" to attemptCorrelationId,
        "ownerOutcome" to ownerOutcome,
        "permit" to permit?.let {
            linkedMapOf(
                "routeEpoch" to it.routeEpoch,
                "reason" to it.reason.value,
            )
        },
        "backoff" to backoff?.let {
            linkedMapOf(
                "retryOrdinal" to it.retryOrdinal,
                "windowMs" to it.windowMs,
                "delayMs" to it.delayMs,
            )
        },
        "failureId" to failureId,
        "terminalReason" to terminalReason?.name,
    )
}

internal data class RecoveryActionRecord(
    val kind: RecoveryActionKind,
    val delayMs: Long? = null,
    val retryOrdinal: Int? = null,
    val reconciliation: LocalReconciliation? = null,
)

/**
 * One `failure-decision-events-v1` row: observation, classification,
 * decision and executed action as four separate layers joined by
 * [failureId]. Never contains exception text, stack traces, URLs or headers.
 */
internal data class FailureDecisionEvent(
    val sequence: Long,
    val elapsedRealtimeNs: Long,
    val sessionId: String,
    val recoveryChainId: RecoveryChainId,
    val failureId: String,
    val fetchKey: String,
    val fetchId: String?,
    val attemptCorrelationId: String?,
    val routeEpoch: Long?,
    val observation: FailureObservation,
    val classification: FailureClassification,
    val decision: RecoveryDecision,
    val context: RecoveryDecisionContext,
    val action: RecoveryActionRecord,
) {
    fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
        "sequence" to sequence,
        "elapsedRealtimeNs" to elapsedRealtimeNs,
        "sessionId" to sessionId,
        "recoveryChainId" to recoveryChainId.value,
        "failureId" to failureId,
        "fetchKey" to fetchKey,
        "fetchId" to fetchId,
        "attemptCorrelationId" to attemptCorrelationId,
        "routeEpoch" to routeEpoch,
        "observation" to observation.toArtifactMap(),
        "classification" to classification.name,
        "decision" to linkedMapOf(
            "kind" to decision.kind.name,
            "reason" to decision.reason.name,
        ),
        "context" to linkedMapOf(
            "demandPresent" to context.demandPresent,
            "sessionClosing" to context.sessionClosing,
            "remoteAttemptsRemaining" to context.remoteAttemptsRemaining,
        ),
        "action" to linkedMapOf(
            "kind" to action.kind.name,
            "delayMs" to action.delayMs,
            "retryOrdinal" to action.retryOrdinal,
            "reconciliation" to action.reconciliation?.name,
        ),
    )
}

internal interface RecoveryEvidenceListener {
    fun onBudgetEvent(event: RecoveryBudgetEvent)

    fun onFailureDecision(event: FailureDecisionEvent)
}

/**
 * Bounded producer of `recovery-budget-events-v1` and
 * `failure-decision-events-v1`. Exceeding [capacity] marks the artifacts
 * unusable instead of buffering without bound.
 */
internal class RecoveryEvidenceRecorder(
    private val runId: String,
    private val sessionId: String,
    private val capacity: Int = DEFAULT_CAPACITY,
) : RecoveryEvidenceListener {
    private val lock = Any()
    private val budgetEvents = ArrayList<RecoveryBudgetEvent>()
    private val failures = ArrayList<FailureDecisionEvent>()
    private var overflowed = false

    init {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(capacity > 0) { "capacity must be > 0" }
    }

    override fun onBudgetEvent(event: RecoveryBudgetEvent) {
        synchronized(lock) {
            if (budgetEvents.size + failures.size >= capacity) {
                overflowed = true
            } else {
                budgetEvents += event
            }
        }
    }

    override fun onFailureDecision(event: FailureDecisionEvent) {
        synchronized(lock) {
            if (budgetEvents.size + failures.size >= capacity) {
                overflowed = true
            } else {
                failures += event
            }
        }
    }

    fun budgetEvents(): List<RecoveryBudgetEvent> =
        synchronized(lock) { budgetEvents.toList() }

    fun failures(): List<FailureDecisionEvent> =
        synchronized(lock) { failures.toList() }

    fun budgetArtifact(policyId: String): Map<String, Any?> = synchronized(lock) {
        check(!overflowed) { "recovery evidence exceeded capacity $capacity" }
        linkedMapOf(
            "schemaVersion" to SCHEMA_VERSION,
            "runId" to runId,
            "sessionId" to sessionId,
            "policyId" to policyId,
            "clockDomain" to CLOCK_DOMAIN,
            "events" to budgetEvents.map(RecoveryBudgetEvent::toArtifactMap),
        )
    }

    fun failureArtifact(policyId: String): Map<String, Any?> = synchronized(lock) {
        check(!overflowed) { "recovery evidence exceeded capacity $capacity" }
        linkedMapOf(
            "schemaVersion" to SCHEMA_VERSION,
            "runId" to runId,
            "sessionId" to sessionId,
            "policyId" to policyId,
            "clockDomain" to CLOCK_DOMAIN,
            "failures" to failures.map(FailureDecisionEvent::toArtifactMap),
        )
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val CLOCK_DOMAIN = "ANDROID_MONOTONIC"
        const val DEFAULT_CAPACITY = 8_192
    }
}

private fun Map<RecoveryBudgetDimension, Int>.dimensionMap(): Map<String, Int> =
    entries.associateTo(linkedMapOf()) { (dimension, value) -> dimension.value to value }
