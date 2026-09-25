package io.github.definitelystable.spongetube.core.engine.route

internal enum class RouteEventDisposition {
    /** The signal was reduced into the current state. */
    APPLIED,

    /** The signal matched the current route but carries nothing to reduce. */
    UNCHANGED,

    /** The signal names a route other than the current one; state untouched. */
    STALE_IGNORED,
}

/** One reduced route signal: the unit of `route-events-v1` evidence. */
internal data class RouteEvent(
    val sequence: Long,
    val elapsedRealtimeNs: Long,
    val source: RouteSignalSource,
    val signal: RouteSignalKind,
    val disposition: RouteEventDisposition,
    val platformRouteRef: PlatformRouteRef?,
    val routeEpochBefore: Long?,
    val routeEpochAfter: Long?,
    val observedCapabilities: ObservedRouteCapabilities?,
    val observedBlocked: ObservedBoolean?,
    val stateAfter: DefaultRouteState,
)

internal fun interface RouteEventListener {
    fun onRouteEvent(event: RouteEvent)
}

/**
 * Pure default-route state machine (.work/milestones/M2.md 8.2-8.4).
 *
 * Not thread-safe by design: exactly one owner (the monitor's reducer
 * coroutine) calls it, so no lock guards route state. The reducer owns the
 * current route ref, `routeEpoch`, the event sequence and evidence ordering.
 */
internal class DefaultRouteReducer(
    private val listener: RouteEventListener? = null,
) {
    private var state: DefaultRouteState = DefaultRouteState.Initializing
    private var currentRef: PlatformRouteRef? = null
    private var lastEpoch = 0L
    private var sequence = 0L
    private var started = false
    private var stopped = false

    val current: RouteObservation
        get() = RouteObservation(sequence, state)

    fun start(elapsedRealtimeNs: Long): RouteObservation {
        check(!started) { "route reducer already started" }
        started = true
        return record(
            elapsedRealtimeNs = elapsedRealtimeNs,
            source = RouteSignalSource.MONITOR_LIFECYCLE,
            signal = RouteSignalKind.MONITOR_STARTED,
            disposition = RouteEventDisposition.APPLIED,
            routeRef = null,
            epochBefore = null,
        )
    }

    fun stop(elapsedRealtimeNs: Long): RouteObservation {
        checkRunning()
        stopped = true
        return record(
            elapsedRealtimeNs = elapsedRealtimeNs,
            source = RouteSignalSource.MONITOR_LIFECYCLE,
            signal = RouteSignalKind.MONITOR_STOPPED,
            disposition = RouteEventDisposition.APPLIED,
            routeRef = null,
            epochBefore = epochOf(state),
        )
    }

    fun reduce(signal: RouteSignal, elapsedRealtimeNs: Long): RouteObservation {
        checkRunning()
        val epochBefore = epochOf(state)
        val disposition = when (signal) {
            is RouteSignal.Available -> available(signal.routeRef)
            is RouteSignal.CapabilitiesChanged -> onCurrent(signal.routeRef) { current ->
                current.copy(
                    capabilitiesReceived = true,
                    capabilities = current.capabilities.withObserved(signal.capabilities),
                )
            }
            is RouteSignal.LinkPropertiesChanged ->
                if (isCurrent(signal.routeRef)) {
                    RouteEventDisposition.UNCHANGED
                } else {
                    RouteEventDisposition.STALE_IGNORED
                }
            is RouteSignal.BlockedStatusChanged -> onCurrent(signal.routeRef) { current ->
                current.copy(
                    capabilities = current.capabilities.copy(
                        blocked = ObservedBoolean.of(signal.blocked),
                    ),
                )
            }
            is RouteSignal.Lost ->
                if (isCurrent(signal.routeRef)) {
                    becomeUnavailable()
                    RouteEventDisposition.APPLIED
                } else {
                    RouteEventDisposition.STALE_IGNORED
                }
            is RouteSignal.LegacySnapshot -> legacySnapshot(signal)
            is RouteSignal.BootstrapSnapshot -> bootstrap(signal.routeRef)
        }
        val observed = when (signal) {
            is RouteSignal.CapabilitiesChanged -> signal.capabilities
            is RouteSignal.LegacySnapshot -> signal.capabilities
            else -> null
        }
        return record(
            elapsedRealtimeNs = elapsedRealtimeNs,
            source = signal.source,
            signal = signal.kind,
            disposition = disposition,
            routeRef = signal.routeRef,
            epochBefore = epochBefore,
            observedCapabilities = observed,
            observedBlocked = (signal as? RouteSignal.BlockedStatusChanged)
                ?.let { ObservedBoolean.of(it.blocked) },
        )
    }

    private fun available(ref: PlatformRouteRef): RouteEventDisposition {
        if (isCurrent(ref)) {
            return RouteEventDisposition.UNCHANGED
        }
        beginEpoch(ref, capabilities = null)
        return RouteEventDisposition.APPLIED
    }

    private fun legacySnapshot(signal: RouteSignal.LegacySnapshot): RouteEventDisposition {
        val ref = signal.routeRef
        if (ref == null) {
            becomeUnavailable()
        } else if (isCurrent(ref)) {
            // A snapshot replaces what the platform currently reports for the
            // same network; missing capabilities are not carried forward.
            state = (state as DefaultRouteState.Available).copy(
                capabilitiesReceived = signal.capabilities != null,
                capabilities = signal.capabilities
                    ?.let(RouteCapabilities.UNKNOWN::withObserved)
                    ?: RouteCapabilities.UNKNOWN,
            )
        } else {
            beginEpoch(ref, signal.capabilities)
        }
        return RouteEventDisposition.APPLIED
    }

    private fun bootstrap(ref: PlatformRouteRef?): RouteEventDisposition {
        // A callback reduced earlier is fresher than this lookup.
        if (state != DefaultRouteState.Initializing) {
            return RouteEventDisposition.UNCHANGED
        }
        if (ref == null) {
            becomeUnavailable()
        } else {
            beginEpoch(ref, capabilities = null)
        }
        return RouteEventDisposition.APPLIED
    }

    private inline fun onCurrent(
        ref: PlatformRouteRef,
        update: (DefaultRouteState.Available) -> DefaultRouteState.Available,
    ): RouteEventDisposition {
        if (!isCurrent(ref)) {
            return RouteEventDisposition.STALE_IGNORED
        }
        state = update(state as DefaultRouteState.Available)
        return RouteEventDisposition.APPLIED
    }

    /** New default network: new epoch, and nothing of the old epoch survives. */
    private fun beginEpoch(ref: PlatformRouteRef, capabilities: ObservedRouteCapabilities?) {
        lastEpoch += 1
        currentRef = ref
        state = DefaultRouteState.Available(
            routeEpoch = lastEpoch,
            capabilitiesReceived = capabilities != null,
            capabilities = capabilities?.let(RouteCapabilities.UNKNOWN::withObserved)
                ?: RouteCapabilities.UNKNOWN,
        )
    }

    private fun becomeUnavailable() {
        currentRef = null
        state = DefaultRouteState.Unavailable
    }

    private fun isCurrent(ref: PlatformRouteRef): Boolean =
        state is DefaultRouteState.Available && currentRef == ref

    private fun checkRunning() {
        check(started) { "route reducer not started" }
        check(!stopped) { "route reducer already stopped" }
    }

    private fun record(
        elapsedRealtimeNs: Long,
        source: RouteSignalSource,
        signal: RouteSignalKind,
        disposition: RouteEventDisposition,
        routeRef: PlatformRouteRef?,
        epochBefore: Long?,
        observedCapabilities: ObservedRouteCapabilities? = null,
        observedBlocked: ObservedBoolean? = null,
    ): RouteObservation {
        sequence += 1
        listener?.onRouteEvent(
            RouteEvent(
                sequence = sequence,
                elapsedRealtimeNs = elapsedRealtimeNs,
                source = source,
                signal = signal,
                disposition = disposition,
                platformRouteRef = routeRef,
                routeEpochBefore = epochBefore,
                routeEpochAfter = epochOf(state),
                observedCapabilities = observedCapabilities,
                observedBlocked = observedBlocked,
                stateAfter = state,
            ),
        )
        return RouteObservation(sequence, state)
    }

    private fun epochOf(state: DefaultRouteState): Long? =
        (state as? DefaultRouteState.Available)?.routeEpoch
}
