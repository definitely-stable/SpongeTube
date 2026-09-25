package io.github.definitelystable.spongetube.core.engine.recovery

import io.github.definitelystable.spongetube.core.engine.FetchId
import io.github.definitelystable.spongetube.core.engine.FetchKey
import io.github.definitelystable.spongetube.core.engine.FetchOutcomeKind
import io.github.definitelystable.spongetube.core.storage.CommittedExtent

/**
 * One logical recovery of one immutable work item (M2.md 11.1). Monotonic
 * inside one PlaybackBridgeRuntime (`recovery-1`, `recovery-2`, ...); not a
 * UUID, never persisted, never survives the session.
 */
@JvmInline
internal value class RecoveryChainId(val value: String) {
    init {
        require(value.isNotBlank()) { "recovery chain id must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
internal value class RecoveryConsumerId(val value: String) {
    init {
        require(value.isNotBlank()) { "recovery consumer id must not be blank" }
        require(value.length <= 256) {
            "recovery consumer id must be <= 256 characters"
        }
    }

    override fun toString(): String = value
}

/** Demand kinds; priority reuse is RESERVE < PLAYBACK. */
internal enum class RecoveryConsumerKind {
    RESERVE,
    PLAYBACK,
}

internal data class RecoveryConsumer(
    val id: RecoveryConsumerId,
    val kind: RecoveryConsumerKind,
)

internal enum class RecoveryChainState {
    ACTIVE,
    WAITING_BACKOFF,
    WAITING_ATTEMPT_PERMIT,
    CANCELLING,
    TERMINAL,
}

internal enum class RecoveryTerminalReason {
    SUCCESS,
    TERMINAL_FAILURE,
    BUDGET_EXHAUSTED,
    NO_REMAINING_DEMAND,
    SESSION_TERMINATION,
}

internal enum class RecoveryAcquireDisposition {
    NEW_CHAIN,
    JOINED_ACTIVE,

    /** Joined a chain whose physical owner is finishing cancellation. */
    JOINED_CANCELLING,
}

/**
 * Conservative semantic interpretation of one observation (M2-C vocabulary).
 * [UNKNOWN] always fails closed.
 */
internal enum class FailureClassification {
    TRANSIENT_TRANSPORT,
    TERMINAL_TRANSPORT,
    PROVIDER_TRANSIENT_RESPONSE,
    PROVIDER_RATE_LIMITED,
    PROVIDER_REJECTED,
    DELIVERY_BINDING_STALE,
    RANGE_REJECTED,
    CONTENT_INTEGRITY,
    STORAGE_FAILURE,

    /** Another publisher may already have committed the same immutable extent. */
    PUBLICATION_CONFLICT,
    CANCELLED,
    INTERNAL,
    UNKNOWN,
}

internal enum class RecoveryDecisionKind {
    RETRY_AFTER_BACKOFF,
    WAIT_FOR_ROUTE,
    WAIT_UNTIL_PROVIDER,
    REFRESH_DELIVERY_BINDING,
    RERESOLVE_PROVIDER,
    FAIL_TERMINAL,
    COMPLETE_NO_DEMAND,
    COMPLETE_SESSION,

    /** Demand re-appeared while the owner was being cancelled (M1 barrier). */
    CONTINUE_FOR_DEMAND,

    /** STORAGE_CONFLICT: refresh local coverage before any network action. */
    RECONCILE_LOCAL_COVERAGE,
}

/** Why the policy chose its decision; typed, never free text. */
internal enum class RecoveryDecisionReason {
    TRANSIENT_FAILURE,
    PROVIDER_TRANSIENT_STATUS,
    PROVIDER_THROTTLED,
    STALE_BINDING_SIGNAL,
    NON_RETRYABLE_CLASSIFICATION,
    UNKNOWN_FAILS_CLOSED,
    DEMAND_RELEASED,
    SESSION_SHUTDOWN,
    DEMAND_PRESENT_AFTER_CANCELLATION,
    LOCAL_PUBLICATION_CONFLICT,
}

/** The operation actually executed after a decision (M2.md 9). */
internal enum class RecoveryActionKind {
    SCHEDULE_BACKOFF,
    START_NEXT_OWNER,
    TERMINATE_FAILURE,
    TERMINATE_BUDGET_EXHAUSTED,
    TERMINATE_NO_DEMAND,
    TERMINATE_SESSION,

    /** The decided action has no handler in this runtime (e.g. M2-D). */
    FAIL_CLOSED_ACTION_UNAVAILABLE,
    LOCAL_COVERAGE_READY,
    FAIL_CLOSED_IDENTITY_CONFLICT,
}

internal data class RecoveryDecision(
    val kind: RecoveryDecisionKind,
    val reason: RecoveryDecisionReason,
)

/** Result of the local reconciliation performed for STORAGE_CONFLICT. */
internal enum class LocalReconciliation {
    /** The extent is published (READY or awaiting only dependencies). */
    COVERAGE_PRESENT,
    IDENTITY_CONFLICT,
    ABSENT,

    /** Reconciliation itself failed; fails closed. */
    FAILED,
}

/** Terminal result handed to every consumer of a chain. */
internal data class RecoveryOutcome(
    val recoveryChainId: RecoveryChainId,
    val fetchKey: FetchKey,
    val terminalReason: RecoveryTerminalReason,
    /** Last physical owner, if any owner was started. */
    val lastFetchId: FetchId?,
    /** Legacy `fetch-events-v3` outcome of the last owner (diagnostic only). */
    val lastFetchOutcome: FetchOutcomeKind?,
    val classification: FailureClassification?,
    val action: RecoveryActionKind?,
    val committedExtent: CommittedExtent?,
) {
    val isSuccess: Boolean
        get() = terminalReason == RecoveryTerminalReason.SUCCESS

    /** True when a SUCCESS came from local reconciliation, not a download. */
    val reconciledLocally: Boolean
        get() = isSuccess && committedExtent == null

    val identityConflict: Boolean
        get() = action == RecoveryActionKind.FAIL_CLOSED_IDENTITY_CONFLICT
}
