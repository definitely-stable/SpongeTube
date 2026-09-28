package io.github.definitelystable.spongetube.core.engine.route

import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryAttemptGate
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryAttemptPermit
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryChainId
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryPermitReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull

/**
 * M2-F1 bridge from the session route policy to RecoveryCoordinator.
 *
 * Every evaluation is driven by DefaultRouteMonitor's StateFlow. PAUSE states
 * suspend without polling, budget mutation or FetchBroker ownership. ALLOW is
 * valid only when the exact platform route can be resolved to an opaque
 * execution binding.
 */
internal class RouteAwareRecoveryAttemptGate(
    private val monitor: DefaultRouteMonitor,
    private val guard: SessionRouteGuard,
    private val evidence: RouteEvidenceRecorder? = null,
) : RecoveryAttemptGate {
    override suspend fun awaitPermit(chainId: RecoveryChainId): RecoveryAttemptPermit =
        monitor.observations
            .mapNotNull { observation ->
                val decision = evidence?.evaluate(guard, observation)
                    ?: guard.evaluate(observation.state)
                if (decision.action == ExternalFetchAction.PAUSE) {
                    null
                } else {
                    permit(observation, decision)
                }
            }
            .first()

    private fun permit(
        observation: RouteObservation,
        decision: ExternalFetchRouteDecision,
    ): RecoveryAttemptPermit {
        val available = observation.state as? DefaultRouteState.Available
            ?: error("ALLOW requires an available default route")
        val binding = monitor.executionBindingFor(observation)
            ?: throw RouteExecutionBindingUnavailableException(
                "allowed route epoch ${available.routeEpoch} has no execution binding",
            )
        val reason = when (decision.reason) {
            ExternalFetchRouteReason.ROUTE_READY ->
                RecoveryPermitReason("ROUTE_READY")
            ExternalFetchRouteReason.EXPLICIT_DIRECT_OVERRIDE ->
                RecoveryPermitReason("EXPLICIT_DIRECT_OVERRIDE")
            else -> error("PAUSE reason cannot produce a recovery permit: ${decision.reason}")
        }
        return RecoveryAttemptPermit(
            routeEpoch = available.routeEpoch,
            reason = reason,
            routeBinding = binding,
        )
    }
}

internal class RouteExecutionBindingUnavailableException(
    message: String,
) : IllegalStateException(message)
