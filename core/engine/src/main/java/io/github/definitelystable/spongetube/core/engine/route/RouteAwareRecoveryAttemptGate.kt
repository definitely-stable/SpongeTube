package io.github.definitelystable.spongetube.core.engine.route

import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryAttemptGate
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryAttemptPermit
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryChainId
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryPermitReason
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Session-scoped M2-F bridge from route policy to RecoveryCoordinator.
 *
 * A permit always carries the opaque execution binding of the same route
 * observation that was evaluated. The physical executor must honor it; merely
 * observing an allowed default and later opening on the ambient default would
 * have a permit-to-socket race and could bypass VPN continuity.
 */
internal class RouteAwareRecoveryAttemptGate(
    private val observations: StateFlow<RouteObservation>,
    private val guard: SessionRouteGuard,
    private val evidence: RouteEvidenceRecorder? = null,
) : RecoveryAttemptGate {
    override suspend fun awaitPermit(chainId: RecoveryChainId): RecoveryAttemptPermit =
        observations
            .map(::evaluate)
            .filterNotNull()
            .first()

    private fun evaluate(observation: RouteObservation): RecoveryAttemptPermit? {
        val decision = evidence?.evaluate(guard, observation)
            ?: guard.evaluate(observation.state)
        if (decision.action == ExternalFetchAction.PAUSE) {
            return null
        }

        val available = observation.state as? DefaultRouteState.Available
            ?: error("ALLOW route decision requires AVAILABLE state")
        val binding = observation.binding
            ?: error("ALLOW route decision has no execution binding")
        val permitReason = when (decision.reason) {
            ExternalFetchRouteReason.ROUTE_READY,
            ExternalFetchRouteReason.EXPLICIT_DIRECT_OVERRIDE,
            -> RecoveryPermitReason(decision.reason.name)

            else -> error("unmapped ALLOW route reason: ${decision.reason}")
        }

        return RecoveryAttemptPermit(
            routeEpoch = available.routeEpoch,
            reason = permitReason,
            routeBinding = binding,
        )
    }
}
