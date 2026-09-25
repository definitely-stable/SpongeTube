package io.github.definitelystable.spongetube.core.engine.recovery

import kotlin.random.Random
import kotlinx.coroutines.delay

/** Inputs of a decision besides the classification; recorded in evidence. */
internal data class RecoveryDecisionContext(
    val demandPresent: Boolean,
    val sessionClosing: Boolean,
    val remoteAttemptsRemaining: Int,
) {
    init {
        require(remoteAttemptsRemaining >= 0)
    }
}

/**
 * Exponential backoff with full jitter (M2-C):
 *
 * ```text
 * window = min(cap, base * 2^(retryOrdinal - 1))
 * delay  = uniform(0, window)
 * ```
 *
 * There is no delay before the initial request.
 */
internal data class RecoveryBackoff(
    val baseMs: Long,
    val capMs: Long,
) {
    init {
        require(baseMs > 0) { "baseMs must be > 0" }
        require(capMs >= baseMs) { "capMs must be >= baseMs" }
    }

    fun windowMs(retryOrdinal: Int): Long {
        require(retryOrdinal >= 1) { "retryOrdinal starts at 1" }
        var window = baseMs
        repeat(retryOrdinal - 1) {
            if (window >= capMs) {
                return capMs
            }
            window = if (window > capMs / 2) capMs else window * 2
        }
        return minOf(window, capMs)
    }

    fun delayMs(
        retryOrdinal: Int,
        jitter: RecoveryJitterSource,
    ): Long {
        val window = windowMs(retryOrdinal)
        val delay = jitter.uniformInclusive(window)
        check(delay in 0..window) {
            "jitter source returned $delay outside 0..$window"
        }
        return delay
    }
}

/** Uniform sample in `0..windowMs`; injectable so tests are deterministic. */
internal fun interface RecoveryJitterSource {
    fun uniformInclusive(windowMs: Long): Long

    companion object {
        val RANDOM = RecoveryJitterSource { window ->
            Random.Default.nextLong(window + 1)
        }
    }
}

/** Cancellable wait; never `Thread.sleep`. */
internal fun interface RecoverySleeper {
    suspend fun sleep(delayMs: Long)

    companion object {
        val COROUTINE_DELAY = RecoverySleeper { delayMs -> delay(delayMs) }
    }
}

/**
 * Versioned recovery policy: the only place that turns a classification into
 * a decision. It performs no action; the RecoveryCoordinator executes.
 */
internal data class RecoveryPolicy(
    val budget: RecoveryBudgetPolicy,
    val backoff: RecoveryBackoff,
) {
    init {
        require(budget.limit(RecoveryBudgetDimension.REMOTE_ATTEMPT) != null) {
            "a recovery policy must bound REMOTE_ATTEMPT"
        }
    }

    val policyId: String
        get() = budget.policyId

    fun decide(
        classification: FailureClassification,
        observation: FailureObservation,
        context: RecoveryDecisionContext,
    ): RecoveryDecision {
        if (
            context.sessionClosing ||
            observation == FailureObservation.Cancellation(CancellationKind.SESSION_SHUTDOWN)
        ) {
            return RecoveryDecision(
                RecoveryDecisionKind.COMPLETE_SESSION,
                RecoveryDecisionReason.SESSION_SHUTDOWN,
            )
        }
        return when (classification) {
            FailureClassification.TRANSIENT_TRANSPORT -> retryIfDemanded(
                context,
                RecoveryDecisionReason.TRANSIENT_FAILURE,
            )
            FailureClassification.PROVIDER_TRANSIENT_RESPONSE -> retryIfDemanded(
                context,
                RecoveryDecisionReason.PROVIDER_TRANSIENT_STATUS,
            )
            FailureClassification.PROVIDER_RATE_LIMITED -> RecoveryDecision(
                RecoveryDecisionKind.WAIT_UNTIL_PROVIDER,
                RecoveryDecisionReason.PROVIDER_THROTTLED,
            )
            FailureClassification.DELIVERY_BINDING_STALE -> RecoveryDecision(
                RecoveryDecisionKind.REFRESH_DELIVERY_BINDING,
                RecoveryDecisionReason.STALE_BINDING_SIGNAL,
            )
            FailureClassification.PUBLICATION_CONFLICT -> RecoveryDecision(
                RecoveryDecisionKind.RECONCILE_LOCAL_COVERAGE,
                RecoveryDecisionReason.LOCAL_PUBLICATION_CONFLICT,
            )
            FailureClassification.CANCELLED ->
                if (context.demandPresent) {
                    RecoveryDecision(
                        RecoveryDecisionKind.CONTINUE_FOR_DEMAND,
                        RecoveryDecisionReason.DEMAND_PRESENT_AFTER_CANCELLATION,
                    )
                } else {
                    RecoveryDecision(
                        RecoveryDecisionKind.COMPLETE_NO_DEMAND,
                        RecoveryDecisionReason.DEMAND_RELEASED,
                    )
                }
            FailureClassification.UNKNOWN -> RecoveryDecision(
                RecoveryDecisionKind.FAIL_TERMINAL,
                RecoveryDecisionReason.UNKNOWN_FAILS_CLOSED,
            )
            FailureClassification.TERMINAL_TRANSPORT,
            FailureClassification.PROVIDER_REJECTED,
            FailureClassification.RANGE_REJECTED,
            FailureClassification.CONTENT_INTEGRITY,
            FailureClassification.STORAGE_FAILURE,
            FailureClassification.INTERNAL,
            -> RecoveryDecision(
                RecoveryDecisionKind.FAIL_TERMINAL,
                RecoveryDecisionReason.NON_RETRYABLE_CLASSIFICATION,
            )
        }
    }

    private fun retryIfDemanded(
        context: RecoveryDecisionContext,
        reason: RecoveryDecisionReason,
    ): RecoveryDecision =
        if (context.demandPresent) {
            RecoveryDecision(RecoveryDecisionKind.RETRY_AFTER_BACKOFF, reason)
        } else {
            RecoveryDecision(
                RecoveryDecisionKind.COMPLETE_NO_DEMAND,
                RecoveryDecisionReason.DEMAND_RELEASED,
            )
        }

    companion object {
        const val DEFAULT_POLICY_ID = "sponge-recovery-v1"

        /** `sponge-recovery-v1`: 1 initial attempt + at most 3 retries. */
        const val DEFAULT_REMOTE_ATTEMPT_LIMIT = 4

        val DEFAULT = RecoveryPolicy(
            budget = RecoveryBudgetPolicy(
                policyId = DEFAULT_POLICY_ID,
                limits = mapOf(
                    RecoveryBudgetDimension.REMOTE_ATTEMPT to
                        DEFAULT_REMOTE_ATTEMPT_LIMIT,
                ),
            ),
            backoff = RecoveryBackoff(baseMs = 500, capMs = 5_000),
        )
    }
}
