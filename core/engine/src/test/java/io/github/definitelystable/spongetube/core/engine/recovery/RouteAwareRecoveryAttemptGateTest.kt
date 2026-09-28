package io.github.definitelystable.spongetube.core.engine.recovery

import io.github.definitelystable.spongetube.core.engine.route.DefaultRouteState
import io.github.definitelystable.spongetube.core.engine.route.ExternalFetchRouteReason
import io.github.definitelystable.spongetube.core.engine.route.ObservedBoolean
import io.github.definitelystable.spongetube.core.engine.route.RouteCapabilities
import io.github.definitelystable.spongetube.core.engine.route.RouteEvidenceRecorder
import io.github.definitelystable.spongetube.core.engine.route.RouteExecutionBinding
import io.github.definitelystable.spongetube.core.engine.route.RouteObservation
import io.github.definitelystable.spongetube.core.engine.route.SessionRouteGuard
import io.github.definitelystable.spongetube.core.engine.route.SessionRouteGuardState
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RouteAwareRecoveryAttemptGateTest {
    @Test
    fun currentAllowedRouteIsEvaluatedImmediatelyAndCarriesExactBinding() = runTest {
        val binding = binding("direct-1")
        val flow = MutableStateFlow(available(1, 7, ObservedBoolean.FALSE, binding))
        val guard = SessionRouteGuard()
        val gate = RouteAwareRecoveryAttemptGate(flow, guard)

        val permit = gate.awaitPermit(CHAIN)

        assertEquals(7L, permit.routeEpoch)
        assertEquals(RecoveryPermitReason.ROUTE_READY, permit.reason)
        assertSame(binding, permit.routeBinding)
        assertEquals(SessionRouteGuardState.SYSTEM_DEFAULT_ALLOWED, guard.state)
    }

    @Test
    fun unavailableAndPendingWaitEventDrivenUntilAllowed() = runTest {
        val recorder = RouteEvidenceRecorder("run-f1", "session-f1", androidApi = 36)
        val flow = MutableStateFlow(
            RouteObservation(
                sequence = 1,
                state = DefaultRouteState.Unavailable,
            ),
        )
        val gate = RouteAwareRecoveryAttemptGate(flow, SessionRouteGuard(), recorder)

        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            gate.awaitPermit(CHAIN)
        }
        assertFalse(waiting.isCompleted)

        flow.value = RouteObservation(
            sequence = 2,
            state = DefaultRouteState.Available(
                routeEpoch = 1,
                capabilitiesReceived = false,
                capabilities = RouteCapabilities.UNKNOWN,
            ),
            executionBinding = binding("pending"),
        )
        runCurrent()
        assertFalse(waiting.isCompleted)

        val allowed = binding("direct-allowed")
        flow.value = available(3, 1, ObservedBoolean.FALSE, allowed)
        runCurrent()

        val permit = waiting.await()
        assertSame(allowed, permit.routeBinding)
        assertEquals(
            listOf(
                ExternalFetchRouteReason.NO_USABLE_DEFAULT,
                ExternalFetchRouteReason.CAPABILITIES_PENDING,
                ExternalFetchRouteReason.ROUTE_READY,
            ),
            recorder.policyEvaluations().map { it.decision.reason },
        )
    }

    @Test
    fun vpnRequiredSessionPausesOnDirectReplacementAndResumesOnVpn() = runTest {
        val firstVpn = binding("vpn-1")
        val flow = MutableStateFlow(available(1, 1, ObservedBoolean.TRUE, firstVpn))
        val guard = SessionRouteGuard()
        val gate = RouteAwareRecoveryAttemptGate(flow, guard)

        val firstPermit = gate.awaitPermit(CHAIN)
        assertSame(firstVpn, firstPermit.routeBinding)
        assertEquals(SessionRouteGuardState.VPN_CONTINUITY_REQUIRED, guard.state)

        flow.value = available(2, 2, ObservedBoolean.FALSE, binding("direct-2"))
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            gate.awaitPermit(CHAIN)
        }
        assertFalse(waiting.isCompleted)

        val restoredVpn = binding("vpn-3")
        flow.value = available(3, 3, ObservedBoolean.TRUE, restoredVpn)
        runCurrent()

        val permit = waiting.await()
        assertEquals(3L, permit.routeEpoch)
        assertEquals(RecoveryPermitReason.ROUTE_READY, permit.reason)
        assertSame(restoredVpn, permit.routeBinding)
    }

    @Test
    fun explicitOverrideWakesAlreadyPausedGateWithoutRouteCallback() = runTest {
        val recorder = RouteEvidenceRecorder("run-f3", "session-f3", androidApi = 36)
        val vpn = binding("vpn")
        val direct = binding("direct")
        val flow = MutableStateFlow(available(1, 1, ObservedBoolean.TRUE, vpn))
        val guard = SessionRouteGuard()
        val gate = RouteAwareRecoveryAttemptGate(flow, guard, recorder)

        val first = gate.awaitPermit(CHAIN)
        assertEquals(RecoveryPermitReason.ROUTE_READY, first.reason)
        assertSame(vpn, first.routeBinding)
        assertEquals(SessionRouteGuardState.VPN_CONTINUITY_REQUIRED, guard.state)

        flow.value = available(2, 2, ObservedBoolean.FALSE, direct)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            gate.awaitPermit(CHAIN)
        }
        assertFalse(waiting.isCompleted)
        assertEquals(
            ExternalFetchRouteReason.VPN_CONTINUITY_REQUIRED,
            recorder.policyEvaluations().last().decision.reason,
        )

        // No route callback follows. The user choice itself must wake the
        // suspended gate and re-evaluate the same direct route observation.
        guard.explicitDirectOverride = true
        runCurrent()

        val permit = waiting.await()
        assertEquals(2L, permit.routeEpoch)
        assertEquals(RecoveryPermitReason.EXPLICIT_DIRECT_OVERRIDE, permit.reason)
        assertSame(direct, permit.routeBinding)
        assertEquals(2L, flow.value.sequence)

        val lastTwo = recorder.policyEvaluations().takeLast(2)
        assertEquals(
            listOf(
                ExternalFetchRouteReason.VPN_CONTINUITY_REQUIRED,
                ExternalFetchRouteReason.EXPLICIT_DIRECT_OVERRIDE,
            ),
            lastTwo.map { it.decision.reason },
        )
        assertEquals(
            listOf(2L, 2L),
            lastTwo.map { it.routeEventSequenceWatermark },
        )
        assertEquals(listOf(2L, 2L), lastTwo.map { it.routeEpoch })
    }

    @Test
    fun explicitOverrideLiftsOnlyVpnContinuity() = runTest {
        val flow = MutableStateFlow(available(1, 1, ObservedBoolean.TRUE, binding("vpn")))
        val guard = SessionRouteGuard()
        val gate = RouteAwareRecoveryAttemptGate(flow, guard)
        gate.awaitPermit(CHAIN)
        guard.explicitDirectOverride = true

        flow.value = available(
            sequence = 2,
            epoch = 2,
            vpn = ObservedBoolean.FALSE,
            binding = binding("blocked-direct"),
            blocked = ObservedBoolean.TRUE,
        )
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            gate.awaitPermit(CHAIN)
        }
        assertFalse(waiting.isCompleted)

        val direct = binding("direct-override")
        flow.value = available(
            sequence = 3,
            epoch = 2,
            vpn = ObservedBoolean.FALSE,
            binding = direct,
        )
        runCurrent()

        val permit = waiting.await()
        assertEquals(RecoveryPermitReason.EXPLICIT_DIRECT_OVERRIDE, permit.reason)
        assertSame(direct, permit.routeBinding)
    }

    @Test
    fun directStartDoesNotEscalateToVpnContinuityAfterTransientVpn() = runTest {
        val guard = SessionRouteGuard()
        val flow = MutableStateFlow(available(1, 1, ObservedBoolean.FALSE, binding("direct-1")))
        val gate = RouteAwareRecoveryAttemptGate(flow, guard)

        gate.awaitPermit(CHAIN)
        assertEquals(SessionRouteGuardState.SYSTEM_DEFAULT_ALLOWED, guard.state)

        flow.value = available(2, 2, ObservedBoolean.TRUE, binding("vpn-2"))
        gate.awaitPermit(CHAIN)
        assertEquals(SessionRouteGuardState.SYSTEM_DEFAULT_ALLOWED, guard.state)

        val direct3 = binding("direct-3")
        flow.value = available(3, 3, ObservedBoolean.FALSE, direct3)
        val permit = gate.awaitPermit(CHAIN)

        assertEquals(RecoveryPermitReason.ROUTE_READY, permit.reason)
        assertSame(direct3, permit.routeBinding)
        assertEquals(SessionRouteGuardState.SYSTEM_DEFAULT_ALLOWED, guard.state)
    }

    @Test
    fun permitKeepsExactEpochBindingAfterAmbientRouteReplacement() = runTest {
        val routeA = binding("route-A")
        val routeB = binding("route-B")
        val flow = MutableStateFlow(
            available(
                sequence = 1,
                epoch = 10,
                vpn = ObservedBoolean.FALSE,
                binding = routeA,
            ),
        )
        val gate = RouteAwareRecoveryAttemptGate(flow, SessionRouteGuard())

        val permitA = gate.awaitPermit(CHAIN)
        flow.value = available(
            sequence = 2,
            epoch = 11,
            vpn = ObservedBoolean.FALSE,
            binding = routeB,
        )

        assertEquals(10L, permitA.routeEpoch)
        assertSame(routeA, permitA.routeBinding)
        val permitB = gate.awaitPermit(CHAIN)
        assertEquals(11L, permitB.routeEpoch)
        assertSame(routeB, permitB.routeBinding)
    }

    @Test
    fun allowedStateWithoutBindingFailsClosed() {
        val flow = MutableStateFlow(
            available(
                sequence = 1,
                epoch = 1,
                vpn = ObservedBoolean.FALSE,
                binding = null,
            ),
        )
        val gate = RouteAwareRecoveryAttemptGate(flow, SessionRouteGuard())

        assertThrows(RouteExecutionBindingUnavailableException::class.java) {
            kotlinx.coroutines.runBlocking {
                gate.awaitPermit(CHAIN)
            }
        }
    }

    @Test
    fun cancellationNaturallyCancelsPausedFlowWait() = runTest {
        val flow = MutableStateFlow(
            RouteObservation(
                sequence = 1,
                state = DefaultRouteState.Unavailable,
            ),
        )
        val gate = RouteAwareRecoveryAttemptGate(flow, SessionRouteGuard())

        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            gate.awaitPermit(CHAIN)
        }
        assertFalse(waiting.isCompleted)

        waiting.cancelAndJoin()
        assertFalse(waiting.isActive)
    }

    private fun available(
        sequence: Long,
        epoch: Long,
        vpn: ObservedBoolean,
        binding: RouteExecutionBinding?,
        blocked: ObservedBoolean = ObservedBoolean.FALSE,
        suspended: ObservedBoolean = ObservedBoolean.FALSE,
        restricted: ObservedBoolean = ObservedBoolean.FALSE,
        internet: ObservedBoolean = ObservedBoolean.TRUE,
    ): RouteObservation =
        RouteObservation(
            sequence = sequence,
            state = DefaultRouteState.Available(
                routeEpoch = epoch,
                capabilitiesReceived = true,
                capabilities = RouteCapabilities(
                    internet = internet,
                    validated = ObservedBoolean.TRUE,
                    vpn = vpn,
                    metered = ObservedBoolean.FALSE,
                    restricted = restricted,
                    blocked = blocked,
                    suspended = suspended,
                ),
            ),
            executionBinding = binding,
        )

    private fun binding(name: String): RouteExecutionBinding =
        NamedBinding(name)

    private class NamedBinding(
        private val name: String,
    ) : RouteExecutionBinding {
        override fun openConnection(url: URL): HttpURLConnection =
            error("binding $name should not open a connection in gate tests")

        override fun toString(): String = name
    }

    private companion object {
        val CHAIN = RecoveryChainId("recovery-route-gate-test")
    }
}
