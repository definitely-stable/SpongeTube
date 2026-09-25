package io.github.definitelystable.spongetube.core.engine.route

import android.net.NetworkCapabilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DefaultRouteReducerTest {
    private val events = mutableListOf<RouteEvent>()
    private val reducer = DefaultRouteReducer { events += it }
    private var clock = 0L

    private fun start() = reducer.start(tick())

    private fun reduce(signal: RouteSignal) = reducer.reduce(signal, tick())

    private fun tick(): Long {
        clock += 1_000
        return clock
    }

    @Test
    fun m2bCase01InitialStateIsInitializingNotUnavailable() {
        val observation = start()

        assertEquals(DefaultRouteState.Initializing, observation.state)
        assertEquals(1L, observation.sequence)
        assertEquals(RouteSignalKind.MONITOR_STARTED, events.single().signal)
        assertNotEquals(DefaultRouteState.Unavailable, observation.state)
    }

    @Test
    fun m2bCase02FirstAvailableNetworkStartsEpoch1WithUnknownCapabilities() {
        start()
        val state = reduce(RouteSignal.Available(A)).state

        assertEquals(pending(1), state)
        assertEquals(null, events.last().routeEpochBefore)
        assertEquals(1L, events.last().routeEpochAfter)
    }

    @Test
    fun m2bCase03CapabilityChangeOnTheSameNetworkKeepsTheEpoch() {
        start()
        reduce(RouteSignal.Available(A))
        reduce(RouteSignal.CapabilitiesChanged(A, DIRECT))
        val state = reduce(RouteSignal.CapabilitiesChanged(A, DIRECT.copy(validated = ObservedBoolean.FALSE))).state

        val available = state as DefaultRouteState.Available
        assertEquals(1L, available.routeEpoch)
        assertTrue(available.capabilitiesReceived)
        assertEquals(ObservedBoolean.FALSE, available.capabilities.validated)
        assertEquals(ObservedBoolean.FALSE, available.capabilities.vpn)
    }

    @Test
    fun m2bCase04NewNetworkIncrementsEpochAndClearsOldCapabilities() {
        start()
        reduce(RouteSignal.Available(A))
        reduce(RouteSignal.CapabilitiesChanged(A, VPN))
        reduce(RouteSignal.BlockedStatusChanged(A, blocked = true))

        val state = reduce(RouteSignal.Available(B)).state

        // No capability of A survives into B, not even blocked.
        assertEquals(pending(2), state)
        assertEquals(RouteEventDisposition.APPLIED, events.last().disposition)
        assertEquals(1L, events.last().routeEpochBefore)
        assertEquals(2L, events.last().routeEpochAfter)
    }

    @Test
    fun m2bCase05LateEventsForTheOldNetworkDoNotMutateState() {
        start()
        reduce(RouteSignal.Available(A))
        reduce(RouteSignal.Available(B))
        reduce(RouteSignal.CapabilitiesChanged(B, DIRECT))
        val before = reducer.current.state

        for (stale in listOf(
            RouteSignal.CapabilitiesChanged(A, VPN),
            RouteSignal.BlockedStatusChanged(A, blocked = true),
            RouteSignal.LinkPropertiesChanged(A),
            RouteSignal.Lost(A),
        )) {
            assertEquals(before, reduce(stale).state, stale.toString())
            assertEquals(RouteEventDisposition.STALE_IGNORED, events.last().disposition)
            assertEquals(2L, events.last().routeEpochAfter)
        }
    }

    @Test
    fun m2bCase06LosingTheCurrentNetworkMakesTheRouteUnavailable() {
        start()
        reduce(RouteSignal.Available(A))
        reduce(RouteSignal.CapabilitiesChanged(A, DIRECT))

        assertEquals(DefaultRouteState.Unavailable, reduce(RouteSignal.Lost(A)).state)
        assertEquals(1L, events.last().routeEpochBefore)
        assertNull(events.last().routeEpochAfter)
    }

    @Test
    fun m2bCase07NetworkReappearingAfterUnavailableStartsANewEpoch() {
        start()
        reduce(RouteSignal.Available(A))
        reduce(RouteSignal.Available(B))
        reduce(RouteSignal.CapabilitiesChanged(B, VPN))
        reduce(RouteSignal.Lost(B))

        // Same platform network again: still a new epoch, nothing carried over.
        assertEquals(pending(3), reduce(RouteSignal.Available(B)).state)
    }

    @Test
    fun m2bCase08LinkPropertiesChangeInjectsNoState() {
        start()
        reduce(RouteSignal.Available(A))
        val before = reduce(RouteSignal.CapabilitiesChanged(A, DIRECT)).state

        assertEquals(before, reduce(RouteSignal.LinkPropertiesChanged(A)).state)
        val event = events.last()
        assertEquals(RouteSignalKind.LINK_PROPERTIES_CHANGED, event.signal)
        assertEquals(RouteEventDisposition.UNCHANGED, event.disposition)
        assertNull(event.observedCapabilities)
        assertNull(event.observedBlocked)
    }

    @Test
    fun duplicateOnAvailableForTheCurrentNetworkKeepsEpochAndCapabilities() {
        start()
        reduce(RouteSignal.Available(A))
        val observed = reduce(RouteSignal.CapabilitiesChanged(A, VPN)).state

        assertEquals(observed, reduce(RouteSignal.Available(A)).state)
        assertEquals(RouteEventDisposition.UNCHANGED, events.last().disposition)
    }

    @Test
    fun blockedAppliesToTheCurrentRouteOnlyAndIsKeptAcrossCapabilityUpdates() {
        start()
        reduce(RouteSignal.Available(A))
        reduce(RouteSignal.BlockedStatusChanged(A, blocked = true))
        val pendingBlocked = reducer.current.state as DefaultRouteState.Available
        assertEquals(false, pendingBlocked.capabilitiesReceived)
        assertEquals(ObservedBoolean.TRUE, pendingBlocked.capabilities.blocked)

        val state = reduce(RouteSignal.CapabilitiesChanged(A, DIRECT)).state as DefaultRouteState.Available
        assertEquals(ObservedBoolean.TRUE, state.capabilities.blocked)
        assertEquals(ObservedBoolean.TRUE, events.first { it.signal == RouteSignalKind.BLOCKED_CHANGED }.observedBlocked)
    }

    @Test
    fun api24BootstrapResolvesInitializingAndTheMatchingCallbackDoesNotOpenASecondEpoch() {
        start()
        assertEquals(pending(1), reduce(RouteSignal.BootstrapSnapshot(A)).state)
        assertEquals(pending(1), reduce(RouteSignal.Available(A)).state)
        assertEquals(RouteEventDisposition.UNCHANGED, events.last().disposition)
    }

    @Test
    fun api24BootstrapAfterACallbackIsSuperseded() {
        start()
        reduce(RouteSignal.Available(A))
        val observed = reduce(RouteSignal.CapabilitiesChanged(A, DIRECT)).state

        assertEquals(observed, reduce(RouteSignal.BootstrapSnapshot(B)).state)
        assertEquals(observed, reduce(RouteSignal.BootstrapSnapshot(null)).state)
        assertTrue(events.takeLast(2).all { it.disposition == RouteEventDisposition.UNCHANGED })
    }

    @Test
    fun api24BootstrapWithoutActiveNetworkIsUnavailableNotAGuessedCause() {
        start()
        assertEquals(DefaultRouteState.Unavailable, reduce(RouteSignal.BootstrapSnapshot(null)).state)
    }

    @Test
    fun api23SnapshotsFollowTheSameEpochRules() {
        start()
        assertEquals(
            DefaultRouteState.Unavailable,
            reduce(RouteSignal.LegacySnapshot(null, null)).state,
        )
        assertEquals(pending(1), reduce(RouteSignal.LegacySnapshot(A, null)).state)
        val observed = reduce(RouteSignal.LegacySnapshot(A, LEGACY_DIRECT)).state as DefaultRouteState.Available
        assertEquals(1L, observed.routeEpoch)
        assertEquals(ObservedBoolean.UNKNOWN, observed.capabilities.suspended)
        assertEquals(ObservedBoolean.UNKNOWN, observed.capabilities.blocked)

        // Same network without capabilities: nothing is carried forward.
        assertEquals(pending(1), reduce(RouteSignal.LegacySnapshot(A, null)).state)

        val replaced = reduce(RouteSignal.LegacySnapshot(B, LEGACY_DIRECT)).state as DefaultRouteState.Available
        assertEquals(2L, replaced.routeEpoch)
        assertTrue(replaced.capabilitiesReceived)
    }

    @Test
    fun sequenceIsContiguousAndStopIsRecordedLast() {
        start()
        reduce(RouteSignal.Available(A))
        reduce(RouteSignal.Lost(A))
        reducer.stop(tick())

        assertEquals((1L..4L).toList(), events.map { it.sequence })
        assertEquals(RouteSignalKind.MONITOR_STOPPED, events.last().signal)
        assertThrows<IllegalStateException> { reduce(RouteSignal.Available(A)) }
        assertThrows<IllegalStateException> { reducer.stop(tick()) }
    }

    @Test
    fun reducerRefusesSignalsBeforeStart() {
        assertThrows<IllegalStateException> { reduce(RouteSignal.Available(A)) }
    }

    @Test
    fun capabilityMappingFollowsPlatformCapabilitiesAndApiFloors() {
        val all = setOf(
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_VALIDATED,
            NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
            NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
            NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED,
            NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED,
        )
        assertEquals(
            ObservedRouteCapabilities(
                internet = ObservedBoolean.TRUE,
                validated = ObservedBoolean.TRUE,
                vpn = ObservedBoolean.FALSE,
                metered = ObservedBoolean.FALSE,
                restricted = ObservedBoolean.FALSE,
                suspended = ObservedBoolean.FALSE,
            ),
            mapRouteCapabilities(34) { it in all },
        )
        assertEquals(
            ObservedRouteCapabilities(
                internet = ObservedBoolean.FALSE,
                validated = ObservedBoolean.FALSE,
                vpn = ObservedBoolean.TRUE,
                metered = ObservedBoolean.TRUE,
                restricted = ObservedBoolean.TRUE,
                suspended = ObservedBoolean.TRUE,
            ),
            mapRouteCapabilities(28) { false },
        )
        // Below API 28 NOT_SUSPENDED does not exist: UNKNOWN, never FALSE.
        for (api in 23..27) {
            assertEquals(ObservedBoolean.UNKNOWN, mapRouteCapabilities(api) { it in all }.suspended)
            assertEquals(ObservedBoolean.UNKNOWN, mapRouteCapabilities(api) { false }.suspended)
        }
        assertNotEquals(ObservedBoolean.FALSE, ObservedBoolean.UNKNOWN)
    }

    @Test
    fun vpnComesOnlyFromNotVpn() {
        // A VPN default network also reports its underlying transport; only
        // the absence of NOT_VPN makes it a VPN.
        val vpnOverWifi = setOf(
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED,
        )
        assertEquals(ObservedBoolean.TRUE, mapRouteCapabilities(34) { it in vpnOverWifi }.vpn)
    }

    @Test
    fun platformRouteRefsAreOpaqueOrdinalsOnly() {
        assertEquals("p7", PlatformRouteRef.ofOrdinal(7).value)
        for (invalid in listOf("", "100", "p0", "Network 100", "wlan0")) {
            assertThrows<IllegalArgumentException> { PlatformRouteRef(invalid) }
        }
    }

    private fun pending(epoch: Long) = DefaultRouteState.Available(
        routeEpoch = epoch,
        capabilitiesReceived = false,
        capabilities = RouteCapabilities.UNKNOWN,
    )

    internal companion object {
        val A = PlatformRouteRef("p1")
        val B = PlatformRouteRef("p2")

        val DIRECT = ObservedRouteCapabilities(
            internet = ObservedBoolean.TRUE,
            validated = ObservedBoolean.TRUE,
            vpn = ObservedBoolean.FALSE,
            metered = ObservedBoolean.FALSE,
            restricted = ObservedBoolean.FALSE,
            suspended = ObservedBoolean.FALSE,
        )
        val VPN = DIRECT.copy(vpn = ObservedBoolean.TRUE)
        val LEGACY_DIRECT = DIRECT.copy(suspended = ObservedBoolean.UNKNOWN)
    }
}
