package io.github.definitelystable.spongetube.core.engine.recovery

import io.github.definitelystable.spongetube.core.engine.delivery.RetryAfterKind
import io.github.definitelystable.spongetube.core.engine.delivery.RetryAfterObservation
import kotlin.random.Random
import kotlinx.coroutines.delay

/** Inputs of a decision besides the classification; recorded in evidence. */
internal data class RecoveryDecisionContext(
    val demandPresent: Boolean,
    val sessionClosing: Boolean,
    val remoteAttemptsRemaining: Int,
    /**
     * Remaining DELIVERY_BINDING_REFRESH charges of the chain; 0 when the
     * policy does not declare the dimension.
     */
    val deliveryBindingRefreshesRemaining: Int,
) {
    init {
        require(remoteAttemptsRemaining >= 0)
        require(deliveryBindingRefreshesRemaining >= 0)
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
 *
 * `sponge-recovery-v2` is the only runtime table. Its precedence is:
 * session shutdown, then released demand, then `REMOTE_ATTEMPT` exhaustion,
 * then `Retry-After` validity (M2.md 20, M2-D).
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
            FailureClassification.PROVIDER_RATE_LIMITED ->
                decideRateLimited(observation, context)
            FailureClassification.DELIVERY_BINDING_STALE ->
                if (context.demandPresent) {
                    RecoveryDecision(
                        RecoveryDecisionKind.REFRESH_DELIVERY_BINDING,
                        RecoveryDecisionReason.STALE_BINDING_SIGNAL,
                    )
                } else {
                    RecoveryDecision(
                        RecoveryDecisionKind.COMPLETE_NO_DEMAND,
                        RecoveryDecisionReason.DEMAND_RELEASED,
                    )
                }
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

    /**
     * `PROVIDER_RATE_LIMITED`: released demand completes, exhaustion of the
     * remote-attempt dimension decides a provider wait that the coordinator
     * terminates as budget exhaustion, and otherwise only a valid `Retry-After`
     * may wait; ABSENT/MALFORMED fails closed. A non-HTTP observation classified
     * rate limited cannot happen and is treated as ABSENT.
     */
    private fun decideRateLimited(
        observation: FailureObservation,
        context: RecoveryDecisionContext,
    ): RecoveryDecision {
        if (!context.demandPresent) {
            return RecoveryDecision(
                RecoveryDecisionKind.COMPLETE_NO_DEMAND,
                RecoveryDecisionReason.DEMAND_RELEASED,
            )
        }
        if (context.remoteAttemptsRemaining == 0) {
            return RecoveryDecision(
                RecoveryDecisionKind.WAIT_UNTIL_PROVIDER,
                RecoveryDecisionReason.PROVIDER_THROTTLED,
            )
        }
        val retryAfter = (observation as? FailureObservation.HttpResponse)
            ?.retryAfter
            ?: RetryAfterObservation.ABSENT
        return when (retryAfter.rawKind) {
            RetryAfterKind.ABSENT -> RecoveryDecision(
                RecoveryDecisionKind.FAIL_TERMINAL,
                RecoveryDecisionReason.RETRY_AFTER_ABSENT,
            )
            RetryAfterKind.MALFORMED -> RecoveryDecision(
                RecoveryDecisionKind.FAIL_TERMINAL,
                RecoveryDecisionReason.RETRY_AFTER_MALFORMED,
            )
            RetryAfterKind.DELAY_SECONDS,
            RetryAfterKind.HTTP_DATE,
            -> RecoveryDecision(
                RecoveryDecisionKind.WAIT_UNTIL_PROVIDER,
                RecoveryDecisionReason.PROVIDER_THROTTLED,
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
        const val DEFAULT_POLICY_ID = "sponge-recovery-v2"

        /** `sponge-recovery-v2`: 1 initial attempt + at most 3 retries. */
        const val DEFAULT_REMOTE_ATTEMPT_LIMIT = 4

        /** `sponge-recovery-v2`: at most one ACTUAL provider refresh. */
        const val DEFAULT_DELIVERY_BINDING_REFRESH_LIMIT = 1

        val DEFAULT = RecoveryPolicy(
            budget = RecoveryBudgetPolicy(
                policyId = DEFAULT_POLICY_ID,
                limits = mapOf(
                    RecoveryBudgetDimension.REMOTE_ATTEMPT to
                        DEFAULT_REMOTE_ATTEMPT_LIMIT,
                    RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH to
                        DEFAULT_DELIVERY_BINDING_REFRESH_LIMIT,
                ),
            ),
            backoff = RecoveryBackoff(baseMs = 500, capMs = 5_000),
        )
    }
}
