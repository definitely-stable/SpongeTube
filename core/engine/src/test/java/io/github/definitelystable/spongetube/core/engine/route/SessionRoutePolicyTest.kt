package io.github.definitelystable.spongetube.core.engine.route

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SessionRoutePolicyTest {
    @Test
    fun m2bCase09SessionStartingOnVpnRequiresVpnContinuity() {
        val guard = SessionRouteGuard()

        val decision = guard.evaluate(route(vpn = ObservedBoolean.TRUE))

        assertEquals(SessionRouteGuardState.VPN_CONTINUITY_REQUIRED, guard.state)
        assertEquals(SessionRouteGuardState.UNRESOLVED, decision.guardBefore)
        assertEquals(ExternalFetchRouteReason.ROUTE_READY, decision.reason)
        assertEquals(ExternalFetchAction.ALLOW, decision.action)
    }

    @Test
    fun m2bCase10VpnRequiredSessionPausesOnDirectRoute() {
        val guard = vpnSession()

        val decision = guard.evaluate(route(epoch = 2, vpn = ObservedBoolean.FALSE))

        assertEquals(ExternalFetchAction.PAUSE, decision.action)
        assertEquals(ExternalFetchRouteReason.VPN_CONTINUITY_REQUIRED, decision.reason)
        assertEquals(SessionRouteGuardState.VPN_CONTINUITY_REQUIRED, guard.state)
    }

    @Test
    fun m2bCase11VpnRequiredSessionAllowsAnotherVpn() {
        val guard = vpnSession()

        val decision = guard.evaluate(route(epoch = 2, vpn = ObservedBoolean.TRUE))

        assertEquals(ExternalFetchRouteReason.ROUTE_READY, decision.reason)
        // Another VPN satisfies the requirement but never clears it.
        assertEquals(SessionRouteGuardState.VPN_CONTINUITY_REQUIRED, guard.state)
        assertEquals(
            ExternalFetchRouteReason.VPN_CONTINUITY_REQUIRED,
            guard.evaluate(route(epoch = 3, vpn = ObservedBoolean.FALSE)).reason,
        )
    }

    @Test
    fun m2bCase12VpnRequiredSessionPausesWhileCapabilitiesPending() {
        val guard = vpnSession()

        val decision = guard.evaluate(pending(epoch = 2))

        assertEquals(ExternalFetchRouteReason.CAPABILITIES_PENDING, decision.reason)
        assertEquals(
            ExternalFetchRouteReason.VPN_CONTINUITY_REQUIRED,
            guard.evaluate(route(epoch = 2, vpn = ObservedBoolean.UNKNOWN)).reason,
        )
    }

    @Test
    fun m2bCase13ExplicitOverrideLiftsOnlyVpnContinuity() {
        val guard = vpnSession()
        guard.explicitDirectOverride = true

        val direct = guard.evaluate(route(epoch = 2, vpn = ObservedBoolean.FALSE))
        assertEquals(ExternalFetchAction.ALLOW, direct.action)
        assertEquals(ExternalFetchRouteReason.EXPLICIT_DIRECT_OVERRIDE, direct.reason)
        assertEquals(SessionRouteGuardState.VPN_CONTINUITY_REQUIRED, guard.state)

        val stillClosed = mapOf(
            DefaultRouteState.Initializing to ExternalFetchRouteReason.INITIALIZING,
            DefaultRouteState.Unavailable to ExternalFetchRouteReason.NO_USABLE_DEFAULT,
            pending(epoch = 3) to ExternalFetchRouteReason.CAPABILITIES_PENDING,
            route(epoch = 3, blocked = ObservedBoolean.TRUE) to ExternalFetchRouteReason.NETWORK_BLOCKED,
            route(epoch = 3, suspended = ObservedBoolean.TRUE) to ExternalFetchRouteReason.NETWORK_SUSPENDED,
            route(epoch = 3, restricted = ObservedBoolean.TRUE) to ExternalFetchRouteReason.NETWORK_RESTRICTED,
            route(epoch = 3, internet = ObservedBoolean.FALSE) to ExternalFetchRouteReason.NO_INTERNET_CAPABILITY,
        )
        for ((state, reason) in stillClosed) {
            val decision = guard.evaluate(state)
            assertEquals(ExternalFetchAction.PAUSE, decision.action, state.toString())
            assertEquals(reason, decision.reason, state.toString())
        }

        guard.explicitDirectOverride = false
        assertEquals(
            ExternalFetchRouteReason.VPN_CONTINUITY_REQUIRED,
            guard.evaluate(route(epoch = 4, vpn = ObservedBoolean.FALSE)).reason,
        )
    }

    @Test
    fun m2bCase14DirectStartIsNotEscalatedByTransientVpn() {
        val guard = SessionRouteGuard()

        for ((epoch, vpn) in listOf(
            1L to ObservedBoolean.FALSE,
            2L to ObservedBoolean.TRUE,
            3L to ObservedBoolean.FALSE,
        )) {
            val decision = guard.evaluate(route(epoch = epoch, vpn = vpn))
            assertEquals(SessionRouteGuardState.SYSTEM_DEFAULT_ALLOWED, guard.state)
            assertEquals(ExternalFetchRouteReason.ROUTE_READY, decision.reason)
        }
    }

    @Test
    fun m2bCase15UnknownVpnAtSessionStartIsUnresolvedUntilObserved() {
        for (initialVpn in listOf(ObservedBoolean.TRUE, ObservedBoolean.FALSE)) {
            val guard = SessionRouteGuard()
            val expected = if (initialVpn == ObservedBoolean.TRUE) {
                SessionRouteGuardState.VPN_CONTINUITY_REQUIRED
            } else {
                SessionRouteGuardState.SYSTEM_DEFAULT_ALLOWED
            }

            val startup = mapOf(
                DefaultRouteState.Initializing to ExternalFetchRouteReason.INITIALIZING,
                DefaultRouteState.Unavailable to ExternalFetchRouteReason.NO_USABLE_DEFAULT,
                pending(epoch = 1) to ExternalFetchRouteReason.CAPABILITIES_PENDING,
                route(vpn = ObservedBoolean.UNKNOWN) to ExternalFetchRouteReason.SESSION_ROUTE_UNRESOLVED,
            )
            for ((state, reason) in startup) {
                val decision = guard.evaluate(state)
                assertEquals(ExternalFetchAction.PAUSE, decision.action)
                assertEquals(reason, decision.reason)
                assertEquals(SessionRouteGuardState.UNRESOLVED, guard.state)
            }

            val resolved = guard.evaluate(route(vpn = initialVpn))
            assertEquals(expected, guard.state)
            assertEquals(ExternalFetchRouteReason.ROUTE_READY, resolved.reason)
        }
    }

    @Test
    fun unresolvedGuardIsNotLiftedByOverride() {
        val guard = SessionRouteGuard(explicitDirectOverride = true)

        assertEquals(
            ExternalFetchRouteReason.SESSION_ROUTE_UNRESOLVED,
            guard.evaluate(route(vpn = ObservedBoolean.UNKNOWN)).reason,
        )
    }

    @Test
    fun meteredAndUnvalidatedRoutesAreNotPaused() {
        val guard = SessionRouteGuard()
        for (state in listOf(
            route(metered = ObservedBoolean.TRUE),
            route(validated = ObservedBoolean.FALSE),
            route(validated = ObservedBoolean.UNKNOWN),
            route(internet = ObservedBoolean.UNKNOWN),
        )) {
            assertEquals(ExternalFetchRouteReason.ROUTE_READY, guard.evaluate(state).reason, state.toString())
        }
    }

    @Test
    fun decisionOrderIsNormative() {
        val guard = vpnSession()
        // Several closed conditions at once: VPN continuity is reported first,
        // then blocked, suspended, restricted, internet.
        val everything = route(
            epoch = 2,
            vpn = ObservedBoolean.FALSE,
            blocked = ObservedBoolean.TRUE,
            suspended = ObservedBoolean.TRUE,
            restricted = ObservedBoolean.TRUE,
            internet = ObservedBoolean.FALSE,
        )
        assertEquals(ExternalFetchRouteReason.VPN_CONTINUITY_REQUIRED, guard.evaluate(everything).reason)
        guard.explicitDirectOverride = true
        assertEquals(ExternalFetchRouteReason.NETWORK_BLOCKED, guard.evaluate(everything).reason)
        assertEquals(
            ExternalFetchRouteReason.NETWORK_SUSPENDED,
            guard.evaluate(everything.withCapabilities { copy(blocked = ObservedBoolean.FALSE) }).reason,
        )
        assertEquals(
            ExternalFetchRouteReason.NETWORK_RESTRICTED,
            guard.evaluate(
                everything.withCapabilities {
                    copy(blocked = ObservedBoolean.FALSE, suspended = ObservedBoolean.UNKNOWN)
                },
            ).reason,
        )
        assertEquals(
            ExternalFetchRouteReason.NO_INTERNET_CAPABILITY,
            guard.evaluate(
                everything.withCapabilities {
                    copy(
                        blocked = ObservedBoolean.UNKNOWN,
                        suspended = ObservedBoolean.FALSE,
                        restricted = ObservedBoolean.FALSE,
                    )
                },
            ).reason,
        )
    }

    @Test
    fun reasonsCarryTheirAction() {
        val allowed = ExternalFetchRouteReason.entries.filter { it.action == ExternalFetchAction.ALLOW }
        assertEquals(
            listOf(ExternalFetchRouteReason.ROUTE_READY, ExternalFetchRouteReason.EXPLICIT_DIRECT_OVERRIDE),
            allowed,
        )
    }

    private fun vpnSession(): SessionRouteGuard =
        SessionRouteGuard().also {
            it.evaluate(route(vpn = ObservedBoolean.TRUE))
            assertEquals(SessionRouteGuardState.VPN_CONTINUITY_REQUIRED, it.state)
        }

    private fun DefaultRouteState.Available.withCapabilities(
        update: RouteCapabilities.() -> RouteCapabilities,
    ) = copy(capabilities = capabilities.update())

    internal companion object {
        fun pending(epoch: Long) = DefaultRouteState.Available(
            routeEpoch = epoch,
            capabilitiesReceived = false,
            capabilities = RouteCapabilities.UNKNOWN,
        )

        fun route(
            epoch: Long = 1,
            internet: ObservedBoolean = ObservedBoolean.TRUE,
            validated: ObservedBoolean = ObservedBoolean.TRUE,
            vpn: ObservedBoolean = ObservedBoolean.FALSE,
            metered: ObservedBoolean = ObservedBoolean.FALSE,
            restricted: ObservedBoolean = ObservedBoolean.FALSE,
            blocked: ObservedBoolean = ObservedBoolean.FALSE,
            suspended: ObservedBoolean = ObservedBoolean.FALSE,
        ) = DefaultRouteState.Available(
            routeEpoch = epoch,
            capabilitiesReceived = true,
            capabilities = RouteCapabilities(
                internet = internet,
                validated = validated,
                vpn = vpn,
                metered = metered,
                restricted = restricted,
                blocked = blocked,
                suspended = suspended,
            ),
        )
    }
}
