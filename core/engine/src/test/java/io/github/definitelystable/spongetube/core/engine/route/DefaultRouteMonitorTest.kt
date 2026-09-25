package io.github.definitelystable.spongetube.core.engine.route

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultRouteMonitorTest {
    @Test
    fun openRegistersOnceAndBootstrapsOnce() = runTest {
        val platform = FakePlatform(bootstrapRef = A)
        val monitor = open(platform)

        assertEquals(1, platform.registrations)
        assertEquals(1, platform.bootstraps)
        assertEquals(0, platform.unregistrations)

        runCurrent()
        assertEquals(1L, (monitor.observations.value.state as DefaultRouteState.Available).routeEpoch)
        monitor.shutdown()
    }

    @Test
    fun callbackSignalsAreReducedInOrderBySingleReducer() = runTest {
        val platform = FakePlatform(bootstrapRef = null)
        val events = mutableListOf<RouteEvent>()
        val monitor = open(platform) { events += it }

        platform.deliver(RouteSignal.Available(A))
        platform.deliver(RouteSignal.CapabilitiesChanged(A, DIRECT))
        platform.deliver(RouteSignal.Available(B))
        platform.deliver(RouteSignal.CapabilitiesChanged(A, VPN))
        runCurrent()

        val state = monitor.observations.value.state as DefaultRouteState.Available
        assertEquals(2L, state.routeEpoch)
        assertFalse(state.capabilitiesReceived)
        assertEquals(RouteCapabilities.UNKNOWN, state.capabilities)
        assertEquals(6L, monitor.observations.value.sequence)
        assertEquals(
            listOf(
                RouteSignalKind.MONITOR_STARTED,
                RouteSignalKind.BOOTSTRAP_SNAPSHOT,
                RouteSignalKind.AVAILABLE,
                RouteSignalKind.CAPABILITIES_CHANGED,
                RouteSignalKind.AVAILABLE,
                RouteSignalKind.CAPABILITIES_CHANGED,
            ),
            events.map { it.signal },
        )
        assertEquals(RouteEventDisposition.STALE_IGNORED, events.last().disposition)
        monitor.shutdown()
    }

    @Test
    fun initialObservationIsInitializingBeforeReducerRuns() = runTest {
        val monitor = open(FakePlatform(bootstrapRef = null))

        assertEquals(RouteObservation(1, DefaultRouteState.Initializing), monitor.observations.value)
        runCurrent()
        assertEquals(DefaultRouteState.Unavailable, monitor.observations.value.state)
        monitor.shutdown()
    }

    @Test
    fun shutdownUnregistersOnceDrainsAndRecordsStop() = runTest {
        val platform = FakePlatform(bootstrapRef = null)
        val events = mutableListOf<RouteEvent>()
        val monitor = open(platform) { events += it }

        // Queued before shutdown: still reduced before MONITOR_STOPPED.
        platform.deliver(RouteSignal.Available(A))
        monitor.shutdown()

        assertEquals(1, platform.unregistrations)
        assertTrue(monitor.isClosed)
        assertEquals(RouteSignalKind.AVAILABLE, events[events.size - 2].signal)
        assertEquals(RouteSignalKind.MONITOR_STOPPED, events.last().signal)
        assertEquals(events.last().sequence, monitor.observations.value.sequence)
    }

    @Test
    fun doubleShutdownIsSafeAndDoesNotUnregisterAgain() = runTest {
        val platform = FakePlatform(bootstrapRef = A)
        val events = mutableListOf<RouteEvent>()
        val monitor = open(platform) { events += it }

        monitor.shutdown()
        monitor.shutdown()

        assertEquals(1, platform.registrations)
        assertEquals(1, platform.unregistrations)
        assertEquals(1, events.count { it.signal == RouteSignalKind.MONITOR_STOPPED })
    }

    @Test
    fun callbackAfterShutdownIsIgnored() = runTest {
        val platform = FakePlatform(bootstrapRef = null)
        val events = mutableListOf<RouteEvent>()
        val monitor = open(platform) { events += it }
        monitor.shutdown()
        val stopped = monitor.observations.value

        platform.retainedSink!!.offer(RouteSignal.Available(A))
        runCurrent()

        assertSame(stopped, monitor.observations.value)
        assertEquals(RouteSignalKind.MONITOR_STOPPED, events.last().signal)
    }

    @Test
    fun registrationFailureLeavesNothingAlive() = runTest {
        val platform = FakePlatform(bootstrapRef = null, failRegister = true)
        val events = mutableListOf<RouteEvent>()

        assertThrows<IllegalStateException> { open(platform) { events += it } }
        runCurrent()

        assertEquals(0, platform.bootstraps)
        assertEquals(0, platform.unregistrations)
        assertEquals(0, platform.liveRegistrations)
        // The reducer coroutine was cancelled: nothing beyond MONITOR_STARTED.
        assertEquals(listOf(RouteSignalKind.MONITOR_STARTED), events.map { it.signal })
    }

    @Test
    fun bootstrapFailureUnregistersTheCallback() = runTest {
        val platform = FakePlatform(bootstrapRef = null, failBootstrap = true)

        assertThrows<IllegalStateException> { open(platform) }
        runCurrent()

        assertEquals(1, platform.registrations)
        assertEquals(1, platform.unregistrations)
        assertEquals(0, platform.liveRegistrations)
    }

    @Test
    fun unregisterFailureStillClosesTheMonitor() = runTest {
        val platform = FakePlatform(bootstrapRef = null, failUnregister = true)
        val events = mutableListOf<RouteEvent>()
        val monitor = open(platform) { events += it }

        assertThrows<IllegalStateException> { monitor.shutdown() }

        assertTrue(monitor.isClosed)
        assertEquals(RouteSignalKind.MONITOR_STOPPED, events.last().signal)
        monitor.shutdown()
        assertEquals(1, platform.unregistrations)
    }

    private fun TestScope.open(
        platform: FakePlatform,
        listener: RouteEventListener? = null,
    ): DefaultRouteMonitor {
        var now = 0L
        return DefaultRouteMonitor.open(
            scope = backgroundScope,
            platform = platform,
            clock = { ++now },
            listener = listener,
        )
    }

    private class FakePlatform(
        private val bootstrapRef: PlatformRouteRef?,
        private val failRegister: Boolean = false,
        private val failBootstrap: Boolean = false,
        private val failUnregister: Boolean = false,
    ) : RouteSignalPlatform {
        var registrations = 0
        var unregistrations = 0
        var bootstraps = 0
        var liveRegistrations = 0
        var retainedSink: RouteSignalSink? = null
        private var activeSink: RouteSignalSink? = null

        override fun register(sink: RouteSignalSink) {
            registrations += 1
            check(!failRegister) { "registration refused" }
            liveRegistrations += 1
            activeSink = sink
            retainedSink = sink
        }

        override fun bootstrap(sink: RouteSignalSink) {
            bootstraps += 1
            check(!failBootstrap) { "bootstrap failed" }
            sink.offer(RouteSignal.BootstrapSnapshot(bootstrapRef))
        }

        override fun unregister() {
            unregistrations += 1
            liveRegistrations -= 1
            activeSink = null
            check(!failUnregister) { "unregister failed" }
        }

        fun deliver(signal: RouteSignal) {
            checkNotNull(activeSink) { "not registered" }.offer(signal)
        }
    }

    private companion object {
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
    }
}
