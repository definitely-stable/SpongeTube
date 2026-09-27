package io.github.definitelystable.spongetube.core.engine.route

import io.github.definitelystable.spongetube.core.engine.ExternalRouteBinding
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryChainId
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RouteAwareRecoveryAttemptGateTest {
    @Test
    fun unavailableWaitsWithoutPollingUntilAnAllowedBoundRouteArrives() = runTest {
        val firstBinding = binding("first")
        val observations = MutableStateFlow(
            RouteObservation(1, DefaultRouteState.Unavailable),
        )
        val gate = RouteAwareRecoveryAttemptGate(observations, SessionRouteGuard())

        val waiting = async { gate.awaitPermit(RecoveryChainId("recovery-1")) }
        runCurrent()
        assertFalse(waiting.isCompleted)

        observations.value = observation(2, epoch = 7, vpn = ObservedBoolean.FALSE, firstBinding)
        val permit = waiting.await()

        assertEquals(7L, permit.routeEpoch)
        assertEquals("ROUTE_READY", permit.reason.value)
        assertSame(firstBinding, permit.routeBinding)
    }

    @Test
    fun vpnRequiredSessionNeverFallsThroughToDirectReplacement() = runTest {
        val vpn1 = binding("vpn-1")
        val direct = binding("direct")
        val vpn2 = binding("vpn-2")
        val observations = MutableStateFlow(
            observation(1, epoch = 1, vpn = ObservedBoolean.TRUE, vpn1),
        )
        val guard = SessionRouteGuard()
        val gate = RouteAwareRecoveryAttemptGate(observations, guard)

        assertSame(vpn1, gate.awaitPermit(RecoveryChainId("recovery-1")).routeBinding)
        assertEquals(SessionRouteGuardState.VPN_CONTINUITY_REQUIRED, guard.state)

        observations.value = observation(2, epoch = 2, vpn = ObservedBoolean.FALSE, direct)
        val waiting = async { gate.awaitPermit(RecoveryChainId("recovery-1")) }
        runCurrent()
        assertFalse(waiting.isCompleted)

        observations.value = observation(3, epoch = 3, vpn = ObservedBoolean.TRUE, vpn2)
        val restored = waiting.await()
        assertEquals(3L, restored.routeEpoch)
        assertSame(vpn2, restored.routeBinding)
    }

    @Test
    fun explicitOverrideLiftsOnlyVpnContinuity() = runTest {
        val vpn = binding("vpn")
        val direct = binding("direct")
        val observations = MutableStateFlow(
            observation(1, epoch = 1, vpn = ObservedBoolean.TRUE, vpn),
        )
        val guard = SessionRouteGuard()
        val gate = RouteAwareRecoveryAttemptGate(observations, guard)
        gate.awaitPermit(RecoveryChainId("recovery-1"))
        guard.explicitDirectOverride = true

        observations.value = observation(2, epoch = 2, vpn = ObservedBoolean.FALSE, direct)
        val directPermit = gate.awaitPermit(RecoveryChainId("recovery-1"))
        assertEquals("EXPLICIT_DIRECT_OVERRIDE", directPermit.reason.value)
        assertSame(direct, directPermit.routeBinding)

        observations.value = observation(
            3,
            epoch = 3,
            vpn = ObservedBoolean.FALSE,
            binding = binding("blocked"),
            blocked = ObservedBoolean.TRUE,
        )
        val blocked = async { gate.awaitPermit(RecoveryChainId("recovery-1")) }
        runCurrent()
        assertFalse(blocked.isCompleted)
        blocked.cancelAndJoin()
    }

    @Test
    fun directStartDoesNotEscalateBecauseAVpnAppearsLater() = runTest {
        val direct1 = binding("direct-1")
        val vpn = binding("vpn")
        val direct2 = binding("direct-2")
        val observations = MutableStateFlow(
            observation(1, epoch = 1, vpn = ObservedBoolean.FALSE, direct1),
        )
        val guard = SessionRouteGuard()
        val gate = RouteAwareRecoveryAttemptGate(observations, guard)

        gate.awaitPermit(RecoveryChainId("recovery-1"))
        observations.value = observation(2, epoch = 2, vpn = ObservedBoolean.TRUE, vpn)
        gate.awaitPermit(RecoveryChainId("recovery-1"))
        observations.value = observation(3, epoch = 3, vpn = ObservedBoolean.FALSE, direct2)
        val permit = gate.awaitPermit(RecoveryChainId("recovery-1"))

        assertEquals(SessionRouteGuardState.SYSTEM_DEFAULT_ALLOWED, guard.state)
        assertSame(direct2, permit.routeBinding)
    }

    @Test
    fun allowedObservationWithoutExecutionBindingFailsClosed() = runTest {
        val observations = MutableStateFlow(
            RouteObservation(
                sequence = 1,
                state = route(epoch = 1, vpn = ObservedBoolean.FALSE),
                binding = null,
            ),
        )
        val gate = RouteAwareRecoveryAttemptGate(observations, SessionRouteGuard())

        assertThrows<IllegalStateException> {
            gate.awaitPermit(RecoveryChainId("recovery-1"))
        }
    }

    @Test
    fun policyEvaluationEvidenceUsesTheSameRouteEpoch() = runTest {
        val recorder = RouteEvidenceRecorder("run-1", "session-1", androidApi = 36)
        val observations = MutableStateFlow(
            observation(5, epoch = 9, vpn = ObservedBoolean.FALSE, binding("route-9")),
        )
        val gate = RouteAwareRecoveryAttemptGate(
            observations = observations,
            guard = SessionRouteGuard(),
            evidence = recorder,
        )

        val permit = gate.awaitPermit(RecoveryChainId("recovery-1"))
        val evaluation = recorder.policyEvaluations().single()

        assertEquals(9L, permit.routeEpoch)
        assertEquals(5L, evaluation.routeEventSequenceWatermark)
        assertEquals(9L, evaluation.routeEpoch)
        assertEquals(ExternalFetchAction.ALLOW, evaluation.decision.action)
    }

    private fun observation(
        sequence: Long,
        epoch: Long,
        vpn: ObservedBoolean,
        binding: ExternalRouteBinding,
        blocked: ObservedBoolean = ObservedBoolean.FALSE,
    ) = RouteObservation(
        sequence = sequence,
        state = route(epoch = epoch, vpn = vpn, blocked = blocked),
        binding = binding,
    )

    private fun route(
        epoch: Long,
        vpn: ObservedBoolean,
        blocked: ObservedBoolean = ObservedBoolean.FALSE,
    ) = DefaultRouteState.Available(
        routeEpoch = epoch,
        capabilitiesReceived = true,
        capabilities = RouteCapabilities(
            internet = ObservedBoolean.TRUE,
            validated = ObservedBoolean.TRUE,
            vpn = vpn,
            metered = ObservedBoolean.FALSE,
            restricted = ObservedBoolean.FALSE,
            blocked = blocked,
            suspended = ObservedBoolean.FALSE,
        ),
    )

    private fun binding(name: String): ExternalRouteBinding =
        NamedBinding(name)

    private class NamedBinding(
        private val name: String,
    ) : ExternalRouteBinding {
        override fun openConnection(url: URL): HttpURLConnection =
            error("unit route binding must not open: $name")

        override fun toString(): String = "NamedBinding($name)"
    }
}
