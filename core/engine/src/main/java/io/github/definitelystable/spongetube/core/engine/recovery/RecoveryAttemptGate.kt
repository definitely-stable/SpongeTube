package io.github.definitelystable.spongetube.core.engine.recovery

import io.github.definitelystable.spongetube.core.engine.FetchRequest
import io.github.definitelystable.spongetube.core.engine.route.DefaultRouteState
import io.github.definitelystable.spongetube.core.engine.route.ExternalFetchAction
import io.github.definitelystable.spongetube.core.engine.route.ExternalFetchRouteReason
import io.github.definitelystable.spongetube.core.engine.route.RouteEvidenceRecorder
import io.github.definitelystable.spongetube.core.engine.route.RouteExecutionBinding
import io.github.definitelystable.spongetube.core.engine.route.RouteObservation
import io.github.definitelystable.spongetube.core.engine.route.SessionRouteGuard
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** Why an external attempt was permitted; an open, typed label. */
@JvmInline
internal value class RecoveryPermitReason(val value: String) {
    init {
        require(value.matches(Regex("^[A-Z][A-Z0-9_]*$"))) {
            "permit reason must be an upper-case token"
        }
    }

    override fun toString(): String = value

    companion object {
        val ALWAYS_PERMIT = RecoveryPermitReason("ALWAYS_PERMIT")
        val ROUTE_READY = RecoveryPermitReason("ROUTE_READY")
        val EXPLICIT_DIRECT_OVERRIDE = RecoveryPermitReason("EXPLICIT_DIRECT_OVERRIDE")
    }
}

/**
 * Permission to open the next physical attempt. Carries no Android type; a
 * route-aware gate (M2-F) may attach the `routeEpoch` it evaluated.
 */
internal data class RecoveryAttemptPermit(
    val routeEpoch: Long?,
    val reason: RecoveryPermitReason,
    val routeBinding: RouteExecutionBinding? = null,
) {
    init {
        require(routeEpoch == null || routeEpoch >= 1)
        require(routeBinding == null || routeEpoch != null) {
            "route-bound permit requires a routeEpoch"
        }
    }
}

/**
 * Seam before every physical attempt of a RecoveryChain (M2-C).
 *
 * Waiting here is not a retry: it charges no budget and must be event-driven
 * (for example a StateFlow condition), never polling. The coordinator cancels
 * the wait when the chain loses its last consumer or the session shuts down.
 * M2-C ships only [ALWAYS_PERMIT]; M2-F connects DefaultRouteMonitor and
 * SessionRouteGuard here.
 */
internal fun interface RecoveryAttemptGate {
    suspend fun awaitPermit(chainId: RecoveryChainId): RecoveryAttemptPermit

    companion object {
        val ALWAYS_PERMIT = RecoveryAttemptGate {
            RecoveryAttemptPermit(
                routeEpoch = null,
                reason = RecoveryPermitReason.ALWAYS_PERMIT,
            )
        }
    }
}

/**
 * Fail-closed signal used when policy would allow an external attempt but the
 * same route observation carries no executable binding. This is a contract
 * violation, not a retryable network observation.
 */
internal class RouteExecutionBindingUnavailableException(
    routeEpoch: Long,
) : IllegalStateException("allowed route epoch $routeEpoch has no execution binding")

/**
 * M2-F route-aware gate.
 *
 * Every loop evaluates the current StateFlow value first. PAUSE waits only for
 * a later observation sequence; there is no polling, delay or budget action.
 * ALLOW returns the exact process-local binding carried by that observation.
 */
internal class RouteAwareRecoveryAttemptGate(
    private val observations: StateFlow<RouteObservation>,
    private val guard: SessionRouteGuard,
    private val evidence: RouteEvidenceRecorder? = null,
) : RecoveryAttemptGate {
    override suspend fun awaitPermit(chainId: RecoveryChainId): RecoveryAttemptPermit {
        var observation = observations.value
        while (true) {
            val decision = evidence?.evaluate(guard, observation)
                ?: guard.evaluate(observation.state)

            if (decision.action == ExternalFetchAction.ALLOW) {
                val available = observation.state as? DefaultRouteState.Available
                    ?: error("ALLOW requires an available default route")
                val binding = observation.executionBinding
                    ?: throw RouteExecutionBindingUnavailableException(available.routeEpoch)
                val reason = when (decision.reason) {
                    ExternalFetchRouteReason.ROUTE_READY ->
                        RecoveryPermitReason.ROUTE_READY
                    ExternalFetchRouteReason.EXPLICIT_DIRECT_OVERRIDE ->
                        RecoveryPermitReason.EXPLICIT_DIRECT_OVERRIDE
                    else -> error("ALLOW returned non-permit route reason: ${decision.reason}")
                }
                return RecoveryAttemptPermit(
                    routeEpoch = available.routeEpoch,
                    reason = reason,
                    routeBinding = binding,
                )
            }

            val evaluatedSequence = observation.sequence
            val evaluatedOverride = decision.explicitDirectOverride
            observation = combine(
                observations,
                guard.explicitDirectOverrideState,
            ) { nextObservation, directOverride ->
                nextObservation to directOverride
            }.first { (nextObservation, directOverride) ->
                nextObservation.sequence > evaluatedSequence ||
                    directOverride != evaluatedOverride
            }.first
        }
    }
}

/**
 * Local reconciliation for STORAGE_CONFLICT (M1 semantics): refresh the
 * CoverageIndex and resolve the immutable extent before any network action.
 */
internal fun interface RecoveryLocalReconciler {
    suspend fun reconcile(request: FetchRequest): LocalReconciliation
}
