package io.github.definitelystable.spongetube.core.engine.route

import io.github.definitelystable.spongetube.core.engine.HttpUrlConnectionRouteExecutionBinding
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryChainId
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouteAwareRecoveryAttemptGateTest {
    @Test
    fun unavailableWaitsEventDrivenThenPermitsTheExactRoute() = runTest {
        val platform = FakePlatform(bootstrapRef = null)
        val monitor = open(platform)
        val guard = SessionRouteGuard()
        val gate = RouteAwareRecoveryAttemptGate(monitor, guard)
        val binding = FakeBinding("direct-a")
        platform.bind(A, binding)

        val pending = async { gate.awaitPermit(RecoveryChainId("chain-1")) }
        runCurrent()
        assertFalse(pending.isCompleted)

        platform.deliver(RouteSignal.Available(A))
        platform.deliver(RouteSignal.CapabilitiesChanged(A, DIRECT))
        runCurrent()

        val permit = pending.await()
        assertEquals(1L, permit.routeEpoch)
        assertEquals("ROUTE_READY", permit.reason.value)
        assertSame(binding, permit.routeBinding)
        monitor.shutdown()
    }

    @Test
    fun vpnRequiredSessionPausesOnDirectReplacementUntilVpnReturns() = runTest {
        val platform = FakePlatform(bootstrapRef = null)
        val monitor = open(platform)
        val guard = SessionRouteGuard()
        val gate = RouteAwareRecoveryAttemptGate(monitor, guard)
        val vpnA = FakeBinding("vpn-a")
        platform.bind(A, vpnA)

        platform.deliver(RouteSignal.Available(A))
        platform.deliver(RouteSignal.CapabilitiesChanged(A, VPN))
        runCurrent()
        val first = gate.awaitPermit(RecoveryChainId("chain-vpn-1"))
        assertEquals(1L, first.routeEpoch)
        assertSame(vpnA, first.routeBinding)

        val directB = FakeBinding("direct-b")
        platform.bind(B, directB)
        platform.deliver(RouteSignal.Lost(A))
        platform.deliver(RouteSignal.Available(B))
        platform.deliver(RouteSignal.CapabilitiesChanged(B, DIRECT))
        runCurrent()

        val waiting = async { gate.awaitPermit(RecoveryChainId("chain-vpn-2")) }
        runCurrent()
        assertFalse(waiting.isCompleted)

        val vpnC = FakeBinding("vpn-c")
        platform.bind(C, vpnC)
        platform.deliver(RouteSignal.Available(C))
        platform.deliver(RouteSignal.CapabilitiesChanged(C, VPN))
        runCurrent()

        val restored = waiting.await()
        assertEquals(3L, restored.routeEpoch)
        assertSame(vpnC, restored.routeBinding)
        monitor.shutdown()
    }

    @Test
    fun explicitOverrideLiftsOnlyVpnContinuityAndCarriesDirectBinding() = runTest {
        val platform = FakePlatform(bootstrapRef = null)
        val monitor = open(platform)
        val guard = SessionRouteGuard()
        val gate = RouteAwareRecoveryAttemptGate(monitor, guard)
        platform.bind(A, FakeBinding("vpn-a"))
        platform.deliver(RouteSignal.Available(A))
        platform.deliver(RouteSignal.CapabilitiesChanged(A, VPN))
        runCurrent()
        gate.awaitPermit(RecoveryChainId("chain-override-1"))

        guard.explicitDirectOverride = true
        val directB = FakeBinding("direct-b")
        platform.bind(B, directB)
        platform.deliver(RouteSignal.Lost(A))
        platform.deliver(RouteSignal.Available(B))
        platform.deliver(RouteSignal.CapabilitiesChanged(B, DIRECT))
        runCurrent()

        val permit = gate.awaitPermit(RecoveryChainId("chain-override-2"))
        assertEquals(2L, permit.routeEpoch)
        assertEquals("EXPLICIT_DIRECT_OVERRIDE", permit.reason.value)
        assertSame(directB, permit.routeBinding)
        monitor.shutdown()
    }

    private fun TestScope.open(platform: FakePlatform): DefaultRouteMonitor {
        var now = 0L
        return DefaultRouteMonitor.open(
            scope = backgroundScope,
            platform = platform,
            clock = { ++now },
        )
    }

    private class FakeBinding(
        val id: String,
    ) : HttpUrlConnectionRouteExecutionBinding {
        override fun openConnection(url: URL): HttpURLConnection =
            error("host route-gate test never opens transport: $id")
    }

    private class FakePlatform(
        private val bootstrapRef: PlatformRouteRef?,
    ) : RouteSignalPlatform {
        private var sink: RouteSignalSink? = null
        private val bindings = mutableMapOf<PlatformRouteRef, FakeBinding>()

        override fun register(sink: RouteSignalSink) {
            this.sink = sink
        }

        override fun bootstrap(sink: RouteSignalSink) {
            sink.offer(RouteSignal.BootstrapSnapshot(bootstrapRef))
        }

        override fun executionBinding(
            routeRef: PlatformRouteRef,
        ): HttpUrlConnectionRouteExecutionBinding? =
            bindings[routeRef]

        override fun unregister() {
            sink = null
        }

        fun bind(routeRef: PlatformRouteRef, binding: FakeBinding) {
            bindings[routeRef] = binding
        }

        fun deliver(signal: RouteSignal) {
            checkNotNull(sink).offer(signal)
        }
    }

    private companion object {
        val A = PlatformRouteRef("p1")
        val B = PlatformRouteRef("p2")
        val C = PlatformRouteRef("p3")

        val DIRECT = ObservedRouteCapabilities(
            internet = ObservedBoolean.TRUE,
            validated = ObservedBoolean.TRUE,
            vpn = ObservedBoolean.FALSE,
            metered = ObservedBoolean.FALSE,
            restricted = ObservedBoolean.FALSE,
            suspended = ObservedBoolean.FALSE,
        )
        val VPN = DIRECT.copy(vpn = ObservedBoolean.TRUE)
    }
}
