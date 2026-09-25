package io.github.definitelystable.spongetube.core.engine.route

/** Per-session VPN continuity state (.work/milestones/M2.md 8.6). */
internal enum class SessionRouteGuardState {
    UNRESOLVED,
    SYSTEM_DEFAULT_ALLOWED,
    VPN_CONTINUITY_REQUIRED,
}

internal enum class ExternalFetchAction {
    ALLOW,
    PAUSE,
}

/** Route-policy reason. This is not an M2-C failure classification. */
internal enum class ExternalFetchRouteReason(val action: ExternalFetchAction) {
    ROUTE_READY(ExternalFetchAction.ALLOW),
    EXPLICIT_DIRECT_OVERRIDE(ExternalFetchAction.ALLOW),
    INITIALIZING(ExternalFetchAction.PAUSE),
    NO_USABLE_DEFAULT(ExternalFetchAction.PAUSE),
    CAPABILITIES_PENDING(ExternalFetchAction.PAUSE),
    SESSION_ROUTE_UNRESOLVED(ExternalFetchAction.PAUSE),
    VPN_CONTINUITY_REQUIRED(ExternalFetchAction.PAUSE),
    NETWORK_BLOCKED(ExternalFetchAction.PAUSE),
    NETWORK_SUSPENDED(ExternalFetchAction.PAUSE),
    NETWORK_RESTRICTED(ExternalFetchAction.PAUSE),
    NO_INTERNET_CAPABILITY(ExternalFetchAction.PAUSE),
}

/** May the session open NEW external media traffic on the current route? */
internal data class ExternalFetchRouteDecision(
    val reason: ExternalFetchRouteReason,
    val guardBefore: SessionRouteGuardState,
    val guardAfter: SessionRouteGuardState,
    val explicitDirectOverride: Boolean,
) {
    val action: ExternalFetchAction
        get() = reason.action
}

/**
 * Pure decision function. Evaluation order is normative (M2.md 8.6):
 * route availability first, then session VPN continuity, then fail-closed
 * platform restrictions. `metered` and `validated` never pause by themselves.
 */
internal fun evaluateExternalFetchRoute(
    guard: SessionRouteGuardState,
    route: DefaultRouteState,
    explicitDirectOverride: Boolean,
): ExternalFetchRouteDecision {
    val guardAfter = resolveSessionRouteGuard(guard, route)

    fun decide(reason: ExternalFetchRouteReason) = ExternalFetchRouteDecision(
        reason = reason,
        guardBefore = guard,
        guardAfter = guardAfter,
        explicitDirectOverride = explicitDirectOverride,
    )

    val available = when (route) {
        DefaultRouteState.Initializing -> return decide(ExternalFetchRouteReason.INITIALIZING)
        DefaultRouteState.Unavailable -> return decide(ExternalFetchRouteReason.NO_USABLE_DEFAULT)
        is DefaultRouteState.Available -> route
    }
    if (!available.capabilitiesReceived) {
        return decide(ExternalFetchRouteReason.CAPABILITIES_PENDING)
    }
    if (guardAfter == SessionRouteGuardState.UNRESOLVED) {
        return decide(ExternalFetchRouteReason.SESSION_ROUTE_UNRESOLVED)
    }
    val capabilities = available.capabilities
    var overrideUsed = false
    if (guardAfter == SessionRouteGuardState.VPN_CONTINUITY_REQUIRED &&
        capabilities.vpn != ObservedBoolean.TRUE
    ) {
        if (!explicitDirectOverride) {
            return decide(ExternalFetchRouteReason.VPN_CONTINUITY_REQUIRED)
        }
        overrideUsed = true
    }
    // The override lifts only VPN continuity; everything below stays closed.
    return decide(
        when {
            capabilities.blocked == ObservedBoolean.TRUE -> ExternalFetchRouteReason.NETWORK_BLOCKED
            capabilities.suspended == ObservedBoolean.TRUE -> ExternalFetchRouteReason.NETWORK_SUSPENDED
            capabilities.restricted == ObservedBoolean.TRUE -> ExternalFetchRouteReason.NETWORK_RESTRICTED
            capabilities.internet == ObservedBoolean.FALSE -> ExternalFetchRouteReason.NO_INTERNET_CAPABILITY
            overrideUsed -> ExternalFetchRouteReason.EXPLICIT_DIRECT_OVERRIDE
            else -> ExternalFetchRouteReason.ROUTE_READY
        },
    )
}

/**
 * Only UNRESOLVED changes, and only from an observation with known `vpn`.
 * Resolved states are sticky: SYSTEM_DEFAULT_ALLOWED never escalates because
 * a VPN appeared later, and VPN_CONTINUITY_REQUIRED never clears by itself.
 */
internal fun resolveSessionRouteGuard(
    guard: SessionRouteGuardState,
    route: DefaultRouteState,
): SessionRouteGuardState {
    if (guard != SessionRouteGuardState.UNRESOLVED) {
        return guard
    }
    val available = route as? DefaultRouteState.Available
    if (available == null || !available.capabilitiesReceived) {
        return guard
    }
    return when (available.capabilities.vpn) {
        ObservedBoolean.TRUE -> SessionRouteGuardState.VPN_CONTINUITY_REQUIRED
        ObservedBoolean.FALSE -> SessionRouteGuardState.SYSTEM_DEFAULT_ALLOWED
        ObservedBoolean.UNKNOWN -> SessionRouteGuardState.UNRESOLVED
    }
}

/**
 * Session-scoped privacy guard. It is deliberately separate from the
 * process-global route monitor, which knows nothing about sessions.
 *
 * Create one when a playback session begins external media activity and
 * evaluate it against each route observation and before opening new external
 * media traffic. [explicitDirectOverride] is the session-level user choice to
 * allow direct continuation after VPN loss; M2-B has no UI or persistence for it.
 */
internal class SessionRouteGuard(
    explicitDirectOverride: Boolean = false,
) {
    private val lock = Any()
    private var guardState = SessionRouteGuardState.UNRESOLVED
    private var override = explicitDirectOverride

    val state: SessionRouteGuardState
        get() = synchronized(lock) { guardState }

    var explicitDirectOverride: Boolean
        get() = synchronized(lock) { override }
        set(value) = synchronized(lock) { override = value }

    fun evaluate(route: DefaultRouteState): ExternalFetchRouteDecision =
        synchronized(lock) {
            evaluateExternalFetchRoute(guardState, route, override).also {
                guardState = it.guardAfter
            }
        }
}
