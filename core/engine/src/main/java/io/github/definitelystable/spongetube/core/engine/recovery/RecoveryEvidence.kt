package io.github.definitelystable.spongetube.core.engine.recovery

import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingRevision
import io.github.definitelystable.spongetube.core.engine.delivery.RetryAfterKind

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
 * One executed server-directed provider wait. [waitMs] is a duration;
 * [notBeforeUtcEpochMs] and [wallClockNowUtcEpochMs] are PROVIDER_WALL_CLOCK
 * instants present only for [RetryAfterKind.HTTP_DATE] and are never compared
 * with ANDROID_MONOTONIC.
 */
internal data class ProviderWaitRecord(
    val rawKind: RetryAfterKind,
    val waitMs: Long,
    val notBeforeUtcEpochMs: Long?,
    val wallClockNowUtcEpochMs: Long?,
) {
    init {
        require(rawKind == RetryAfterKind.DELAY_SECONDS || rawKind == RetryAfterKind.HTTP_DATE) {
            "a provider wait requires a usable Retry-After kind: $rawKind"
        }
        require(waitMs >= 0) { "waitMs must be >= 0" }
        if (rawKind == RetryAfterKind.HTTP_DATE) {
            require(notBeforeUtcEpochMs != null && wallClockNowUtcEpochMs != null) {
                "HTTP_DATE carries both PROVIDER_WALL_CLOCK instants"
            }
        } else {
            require(notBeforeUtcEpochMs == null && wallClockNowUtcEpochMs == null) {
                "DELAY_SECONDS carries no PROVIDER_WALL_CLOCK instant"
            }
        }
    }
}

/** Result of one executed delivery-binding refresh (M2-D). */
internal enum class DeliveryBindingActionResult {
    REFRESHED,
    ALREADY_ADVANCED,
    JOINED_REFRESH,
    INCOMPATIBLE,
    FAILED,
    NOT_ADMITTED,
    CLOSED,

    /** The chain lost its demand or the session closed while awaiting. */
    ABANDONED,
}

/**
 * One executed delivery-binding refresh. [charged] is true iff this chain paid
 * a DELIVERY_BINDING_REFRESH charge for an ACTUAL operation; joined,
 * already-advanced, not-admitted and closed refreshes charge nothing.
 */
internal data class DeliveryBindingActionRecord(
    val expectedRevision: DeliveryBindingRevision,
    val result: DeliveryBindingActionResult,
    /** REFRESHED / ALREADY_ADVANCED / JOINED_REFRESH only. */
    val currentRevision: DeliveryBindingRevision?,
    /** REFRESHED / JOINED_REFRESH / INCOMPATIBLE / FAILED only. */
    val refreshCorrelationId: String?,
    val charged: Boolean,
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
    /** Set iff this record terminated the chain as BUDGET_EXHAUSTED. */
    val exhaustedDimension: RecoveryBudgetDimension? = null,
    /** Executed [RecoveryActionKind.WAIT_PROVIDER] detail. */
    val providerWait: ProviderWaitRecord? = null,
    /** Executed [RecoveryActionKind.REFRESH_DELIVERY_BINDING] detail. */
    val deliveryBinding: DeliveryBindingActionRecord? = null,
)

/**
 * One `failure-decision-events-v2` row: observation, classification,
 * decision and executed action as four separate layers joined by
 * [failureId]. Never contains exception text, stack traces, URLs or headers.
 * The v1 artifact stays immutable for historical evidence.
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
        "observation" to observation.toArtifactMapV2(),
        "classification" to classification.name,
        "decision" to linkedMapOf(
            "kind" to decision.kind.name,
            "reason" to decision.reason.name,
        ),
        "context" to linkedMapOf(
            "demandPresent" to context.demandPresent,
            "sessionClosing" to context.sessionClosing,
            "remoteAttemptsRemaining" to context.remoteAttemptsRemaining,
            "deliveryBindingRefreshesRemaining" to
                context.deliveryBindingRefreshesRemaining,
        ),
        "action" to linkedMapOf(
            "kind" to action.kind.name,
            "delayMs" to action.delayMs,
            "retryOrdinal" to action.retryOrdinal,
            "reconciliation" to action.reconciliation?.name,
            "exhaustedDimension" to action.exhaustedDimension?.value,
            "providerWait" to action.providerWait?.toArtifactMap(),
            "deliveryBinding" to action.deliveryBinding?.toArtifactMap(),
        ),
    )
}

internal interface RecoveryEvidenceListener {
    fun onBudgetEvent(event: RecoveryBudgetEvent)

    fun onFailureDecision(event: FailureDecisionEvent)
}

/**
 * Bounded producer of `recovery-budget-events-v1` and
 * `failure-decision-events-v2`. Exceeding [capacity] marks the artifacts
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
            "schemaVersion" to BUDGET_SCHEMA_VERSION,
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
            "schemaVersion" to FAILURE_SCHEMA_VERSION,
            "runId" to runId,
            "sessionId" to sessionId,
            "policyId" to policyId,
            "clockDomain" to CLOCK_DOMAIN,
            "failures" to failures.map(FailureDecisionEvent::toArtifactMap),
        )
    }

    companion object {
        /** `failure-decision-events-v2`; v1 stays immutable. */
        const val FAILURE_SCHEMA_VERSION = 2

        /** `recovery-budget-events-v1` is unchanged by M2-D. */
        const val BUDGET_SCHEMA_VERSION = 1

        const val CLOCK_DOMAIN = "ANDROID_MONOTONIC"
        const val DEFAULT_CAPACITY = 8_192
    }
}

private fun ProviderWaitRecord.toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "rawKind" to rawKind.name,
    "waitMs" to waitMs,
    "notBeforeUtcEpochMs" to notBeforeUtcEpochMs,
    "wallClockNowUtcEpochMs" to wallClockNowUtcEpochMs,
    "wallClockDomain" to if (rawKind == RetryAfterKind.HTTP_DATE) {
        "PROVIDER_WALL_CLOCK"
    } else {
        null
    },
)

private fun DeliveryBindingActionRecord.toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "expectedRevision" to expectedRevision.value,
    "result" to result.name,
    "currentRevision" to currentRevision?.value,
    "refreshCorrelationId" to refreshCorrelationId,
    "charged" to charged,
)

private fun Map<RecoveryBudgetDimension, Int>.dimensionMap(): Map<String, Int> =
    entries.associateTo(linkedMapOf()) { (dimension, value) -> dimension.value to value }
