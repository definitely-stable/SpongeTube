package io.github.definitelystable.spongetube.core.engine.route

/** One recorded session-policy evaluation (`route-events-v1` policyEvaluations[]). */
internal data class RoutePolicyEvaluation(
    val sequence: Long,
    val routeEventSequenceWatermark: Long,
    val routeEpoch: Long?,
    val decision: ExternalFetchRouteDecision,
)

/**
 * Bounded `route-events-v1` producer (.work/milestones/M2.md 16).
 *
 * Route events and policy evaluations are kept apart so observation and
 * decision never merge into one row (F-05). Retains no Network object,
 * netId, address, DNS, interface, proxy, SSID or BSSID: only the reduced
 * domain values. Exceeding [capacity] marks the artifact unusable instead
 * of buffering without bound.
 */
internal class RouteEvidenceRecorder(
    private val runId: String,
    private val sessionId: String,
    private val androidApi: Int,
    private val capacity: Int = DEFAULT_CAPACITY,
) : RouteEventListener {
    private val lock = Any()
    private val events = ArrayList<RouteEvent>()
    private val policyEvaluations = ArrayList<RoutePolicyEvaluation>()
    private var overflowed = false

    init {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(androidApi >= MIN_API) { "androidApi must be >= $MIN_API" }
        require(capacity > 0) { "capacity must be > 0" }
    }

    override fun onRouteEvent(event: RouteEvent) {
        synchronized(lock) {
            if (events.size + policyEvaluations.size >= capacity) {
                overflowed = true
            } else {
                events += event
            }
        }
    }

    /** Evaluates [guard] against [observation] and records the decision. */
    fun evaluate(
        guard: SessionRouteGuard,
        observation: RouteObservation,
    ): ExternalFetchRouteDecision {
        val decision = guard.evaluate(observation.state)
        synchronized(lock) {
            if (events.size + policyEvaluations.size >= capacity) {
                overflowed = true
            } else {
                policyEvaluations += RoutePolicyEvaluation(
                    sequence = policyEvaluations.size + 1L,
                    routeEventSequenceWatermark = observation.sequence,
                    routeEpoch = (observation.state as? DefaultRouteState.Available)?.routeEpoch,
                    decision = decision,
                )
            }
        }
        return decision
    }

    fun events(): List<RouteEvent> = synchronized(lock) { events.toList() }

    fun policyEvaluations(): List<RoutePolicyEvaluation> =
        synchronized(lock) { policyEvaluations.toList() }

    fun toArtifactMap(): Map<String, Any?> = synchronized(lock) {
        check(!overflowed) { "route evidence exceeded capacity $capacity" }
        linkedMapOf(
            "schemaVersion" to SCHEMA_VERSION,
            "runId" to runId,
            "sessionId" to sessionId,
            "androidApi" to androidApi,
            "clockDomain" to CLOCK_DOMAIN,
            "events" to events.map(RouteEvent::toArtifactMap),
            "policyEvaluations" to policyEvaluations.map(RoutePolicyEvaluation::toArtifactMap),
        )
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val CLOCK_DOMAIN = "ANDROID_MONOTONIC"
        const val DEFAULT_CAPACITY = 4_096
        private const val MIN_API = 23
    }
}

internal fun RouteEvent.toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
        "sequence" to sequence,
        "elapsedRealtimeNs" to elapsedRealtimeNs,
        "source" to source.name,
        "signal" to signal.name,
        "disposition" to disposition.name,
        "platformRouteRef" to platformRouteRef?.value,
        "routeEpochBefore" to routeEpochBefore,
        "routeEpochAfter" to routeEpochAfter,
        "observedCapabilities" to observedCapabilities?.toArtifactMap(),
        "observedBlocked" to observedBlocked?.name,
        "runtimeStateAfter" to stateAfter.toArtifactMap(),
    )

internal fun RoutePolicyEvaluation.toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
        "sequence" to sequence,
        "routeEventSequenceWatermark" to routeEventSequenceWatermark,
        "routeEpoch" to routeEpoch,
        "guardBefore" to decision.guardBefore.name,
        "guardAfter" to decision.guardAfter.name,
        "explicitDirectOverride" to decision.explicitDirectOverride,
        "decision" to decision.action.name,
        "reason" to decision.reason.name,
    )

internal fun ObservedRouteCapabilities.toArtifactMap(): Map<String, String> =
    linkedMapOf(
        "internet" to internet.name,
        "validated" to validated.name,
        "vpn" to vpn.name,
        "metered" to metered.name,
        "restricted" to restricted.name,
        "suspended" to suspended.name,
    )

internal fun DefaultRouteState.toArtifactMap(): Map<String, Any?> {
    val available = this as? DefaultRouteState.Available
    val capabilities = available?.capabilities ?: RouteCapabilities.UNKNOWN
    return linkedMapOf(
        "state" to when (this) {
            DefaultRouteState.Initializing -> "INITIALIZING"
            DefaultRouteState.Unavailable -> "UNAVAILABLE"
            is DefaultRouteState.Available -> "AVAILABLE"
        },
        "routeEpoch" to available?.routeEpoch,
        "capabilitiesReceived" to (available?.capabilitiesReceived ?: false),
        "internet" to capabilities.internet.name,
        "validated" to capabilities.validated.name,
        "vpn" to capabilities.vpn.name,
        "metered" to capabilities.metered.name,
        "restricted" to capabilities.restricted.name,
        "blocked" to capabilities.blocked.name,
        "suspended" to capabilities.suspended.name,
    )
}
