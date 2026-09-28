package io.github.definitelystable.spongetube.core.engine.recovery

import io.github.definitelystable.spongetube.core.engine.route.DefaultRouteState
import io.github.definitelystable.spongetube.core.engine.route.ObservedBoolean
import io.github.definitelystable.spongetube.core.engine.route.RouteCapabilities
import io.github.definitelystable.spongetube.core.engine.route.RouteExecutionBinding
import io.github.definitelystable.spongetube.core.engine.route.RouteObservation
import io.github.definitelystable.spongetube.core.engine.route.SessionRouteGuard
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(30)
class RouteAwareRecoveryIntegrationTest {
    @Test
    fun waitingChargesNothingAndFirstOwnerCarriesPermittedRouteBinding() {
        val observations = MutableStateFlow(
            RouteObservation(
                sequence = 1,
                state = DefaultRouteState.Unavailable,
            ),
        )
        val gate = RouteAwareRecoveryAttemptGate(
            observations = observations,
            guard = SessionRouteGuard(),
        )
        val harness = RecoveryHarness(gate = gate, sessionId = "m2-f1-route")
        try {
            val work = RecoveryHarness.work("route-wait")
            val handle = harness.blocking { harness.acquire(work, "playback") }

            assertNull(handle.fetchIdAtAcquire)
            assertTrue(harness.budget(RecoveryBudgetEventKind.CHARGE).isEmpty())
            assertEquals(0, harness.origin.executions(work.fetchKey))

            val route = TestRouteBinding("route-2")
            observations.value = available(
                sequence = 2,
                epoch = 2,
                vpn = ObservedBoolean.FALSE,
                binding = route,
            )

            val outcome = harness.blocking { handle.await() }

            assertTrue(outcome.isSuccess)
            assertEquals(1, harness.origin.executions(work.fetchKey))
            assertEquals(1, harness.budget(RecoveryBudgetEventKind.CHARGE).size)
            assertEquals(
                listOf(0, 1),
                harness.budgetEvents
                    .filter {
                        it.kind == RecoveryBudgetEventKind.ATTEMPT_PERMIT_WAIT ||
                            it.kind == RecoveryBudgetEventKind.CHARGE
                    }
                    .map { it.spent.getValue(RecoveryBudgetDimension.REMOTE_ATTEMPT) },
            )
            assertSame(route, harness.origin.routeBindings.single())
            val granted = harness.budget(RecoveryBudgetEventKind.ATTEMPT_PERMIT_GRANTED).single()
            assertEquals(2L, granted.permit?.routeEpoch)
            assertEquals(RecoveryPermitReason.ROUTE_READY, granted.permit?.reason)
            assertEquals(handle.recoveryChainId, outcome.recoveryChainId)
        } finally {
            harness.shutdown()
        }
    }

    private fun available(
        sequence: Long,
        epoch: Long,
        vpn: ObservedBoolean,
        binding: RouteExecutionBinding,
    ): RouteObservation =
        RouteObservation(
            sequence = sequence,
            state = DefaultRouteState.Available(
                routeEpoch = epoch,
                capabilitiesReceived = true,
                capabilities = RouteCapabilities(
                    internet = ObservedBoolean.TRUE,
                    validated = ObservedBoolean.TRUE,
                    vpn = vpn,
                    metered = ObservedBoolean.FALSE,
                    restricted = ObservedBoolean.FALSE,
                    blocked = ObservedBoolean.FALSE,
                    suspended = ObservedBoolean.FALSE,
                ),
            ),
            executionBinding = binding,
        )

    private class TestRouteBinding(
        private val name: String,
    ) : RouteExecutionBinding {
        override fun openConnection(url: URL): HttpURLConnection =
            error("$name should not open a connection in scripted integration tests")
    }
}
