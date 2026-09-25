package io.github.definitelystable.spongetube.core.engine.route

/**
 * Platform truth value for one route capability (.work/milestones/M2.md 8.2).
 *
 * [FALSE] is an observed platform negation; [UNKNOWN] is the absence of an
 * observation. UNKNOWN is never interpreted as TRUE or as FALSE.
 */
internal enum class ObservedBoolean {
    TRUE,
    FALSE,
    UNKNOWN,
    ;

    companion object {
        fun of(value: Boolean): ObservedBoolean = if (value) TRUE else FALSE
    }
}

/**
 * Process-local opaque name of one platform network, assigned by the Android
 * adapter (`p1`, `p2`, ...). It exists only to match, ignore and replace route
 * events. It is never a stable or persisted identity.
 */
@JvmInline
internal value class PlatformRouteRef(val value: String) {
    init {
        require(PATTERN.matches(value)) { "invalid platform route ref: $value" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("p[1-9][0-9]*")

        fun ofOrdinal(ordinal: Long): PlatformRouteRef {
            require(ordinal > 0) { "ordinal must be > 0" }
            return PlatformRouteRef("p$ordinal")
        }
    }
}

/**
 * Capabilities carried by one capabilities observation. `blocked` is not part
 * of NetworkCapabilities; it arrives through its own callback (API 29+).
 */
internal data class ObservedRouteCapabilities(
    val internet: ObservedBoolean,
    val validated: ObservedBoolean,
    val vpn: ObservedBoolean,
    val metered: ObservedBoolean,
    val restricted: ObservedBoolean,
    val suspended: ObservedBoolean,
)

internal data class RouteCapabilities(
    val internet: ObservedBoolean = ObservedBoolean.UNKNOWN,
    val validated: ObservedBoolean = ObservedBoolean.UNKNOWN,
    val vpn: ObservedBoolean = ObservedBoolean.UNKNOWN,
    val metered: ObservedBoolean = ObservedBoolean.UNKNOWN,
    val restricted: ObservedBoolean = ObservedBoolean.UNKNOWN,
    val blocked: ObservedBoolean = ObservedBoolean.UNKNOWN,
    val suspended: ObservedBoolean = ObservedBoolean.UNKNOWN,
) {
    fun withObserved(observed: ObservedRouteCapabilities): RouteCapabilities =
        copy(
            internet = observed.internet,
            validated = observed.validated,
            vpn = observed.vpn,
            metered = observed.metered,
            restricted = observed.restricted,
            suspended = observed.suspended,
        )

    companion object {
        val UNKNOWN = RouteCapabilities()
    }
}

/** What Android currently tells the app about its default route (M2.md 8.2). */
internal sealed interface DefaultRouteState {
    /** Monitor exists; no platform observation has been reduced yet. */
    data object Initializing : DefaultRouteState

    /**
     * The platform reported no usable default network. The cause is not
     * named: on API 23 a null active network may also mean "blocked".
     */
    data object Unavailable : DefaultRouteState

    data class Available(
        val routeEpoch: Long,
        val capabilitiesReceived: Boolean,
        val capabilities: RouteCapabilities,
    ) : DefaultRouteState {
        init {
            require(routeEpoch > 0) { "routeEpoch must be > 0" }
            require(
                capabilitiesReceived ||
                    capabilities.copy(blocked = ObservedBoolean.UNKNOWN) ==
                    RouteCapabilities.UNKNOWN,
            ) {
                "NetworkCapabilities fields must be UNKNOWN until received"
            }
        }
    }
}

/** Reduced state plus the route-event sequence it reflects. */
internal data class RouteObservation(
    val sequence: Long,
    val state: DefaultRouteState,
)

internal enum class RouteSignalSource {
    MONITOR_LIFECYCLE,
    DEFAULT_NETWORK_CALLBACK,
    API23_ACTIVE_NETWORK_SNAPSHOT,
    BOOTSTRAP_ACTIVE_NETWORK,
}

internal enum class RouteSignalKind {
    MONITOR_STARTED,
    AVAILABLE,
    CAPABILITIES_CHANGED,
    LINK_PROPERTIES_CHANGED,
    BLOCKED_CHANGED,
    LOST,
    LEGACY_SNAPSHOT,
    BOOTSTRAP_SNAPSHOT,
    MONITOR_STOPPED,
}

/**
 * Small immutable platform observation. The Android adapter creates these
 * from the objects delivered to its callbacks; it never reduces them.
 */
internal sealed interface RouteSignal {
    val source: RouteSignalSource
    val kind: RouteSignalKind
    val routeRef: PlatformRouteRef?

    data class Available(override val routeRef: PlatformRouteRef) : RouteSignal {
        override val source get() = RouteSignalSource.DEFAULT_NETWORK_CALLBACK
        override val kind get() = RouteSignalKind.AVAILABLE
    }

    data class CapabilitiesChanged(
        override val routeRef: PlatformRouteRef,
        val capabilities: ObservedRouteCapabilities,
    ) : RouteSignal {
        override val source get() = RouteSignalSource.DEFAULT_NETWORK_CALLBACK
        override val kind get() = RouteSignalKind.CAPABILITIES_CHANGED
    }

    /** Order/lifecycle signal only; LinkProperties content is never retained. */
    data class LinkPropertiesChanged(override val routeRef: PlatformRouteRef) : RouteSignal {
        override val source get() = RouteSignalSource.DEFAULT_NETWORK_CALLBACK
        override val kind get() = RouteSignalKind.LINK_PROPERTIES_CHANGED
    }

    data class BlockedStatusChanged(
        override val routeRef: PlatformRouteRef,
        val blocked: Boolean,
    ) : RouteSignal {
        override val source get() = RouteSignalSource.DEFAULT_NETWORK_CALLBACK
        override val kind get() = RouteSignalKind.BLOCKED_CHANGED
    }

    data class Lost(override val routeRef: PlatformRouteRef) : RouteSignal {
        override val source get() = RouteSignalSource.DEFAULT_NETWORK_CALLBACK
        override val kind get() = RouteSignalKind.LOST
    }

    /**
     * API 23 stable snapshot: null [routeRef] means no usable active network;
     * null [capabilities] means the platform returned no capabilities.
     */
    data class LegacySnapshot(
        override val routeRef: PlatformRouteRef?,
        val capabilities: ObservedRouteCapabilities?,
    ) : RouteSignal {
        init {
            require(routeRef != null || capabilities == null) {
                "capabilities require an active network"
            }
        }

        override val source get() = RouteSignalSource.API23_ACTIVE_NETWORK_SNAPSHOT
        override val kind get() = RouteSignalKind.LEGACY_SNAPSHOT
    }

    /**
     * API 24+ one-time `activeNetwork` lookup after registration, outside any
     * callback. It only resolves INITIALIZING; it carries no capabilities.
     */
    data class BootstrapSnapshot(override val routeRef: PlatformRouteRef?) : RouteSignal {
        override val source get() = RouteSignalSource.BOOTSTRAP_ACTIVE_NETWORK
        override val kind get() = RouteSignalKind.BOOTSTRAP_SNAPSHOT
    }
}
