package io.github.definitelystable.spongetube.core.engine.route

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M2-B platform proof on a real emulator (API 23 legacy source, API 34/36
 * default-network callback). It observes whatever default network the
 * emulator actually has and never fabricates connectivity: without a usable
 * default network the artifact honestly shows INITIALIZING/UNAVAILABLE and the
 * test still checks lifecycle and API floors. No VPN or route switch is
 * induced (that is M2-F).
 */
@RunWith(AndroidJUnit4::class)
class AndroidDefaultRouteMonitorTest {
    private lateinit var context: Context
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun accessNetworkStateIsGrantedWithoutLocationPermission() {
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE),
        )
        assertNotEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION),
        )
    }

    @Test
    fun observesActualDefaultRouteAndProducesRouteEvidence() = runBlocking<Unit> {
        val api = Build.VERSION.SDK_INT
        val recorder = RouteEvidenceRecorder(
            runId = "m2-b-android-api$api",
            sessionId = "m2-b-android-api$api-session",
            androidApi = api,
        )
        val guard = SessionRouteGuard()
        val monitor = AndroidDefaultRouteMonitor.open(context, scope, recorder)

        recorder.evaluate(guard, monitor.observations.value)

        val settled = withTimeoutOrNull(ROUTE_TIMEOUT_MS) {
            monitor.observations.first { it.state != DefaultRouteState.Initializing }
        }
        if (settled != null) {
            recorder.evaluate(guard, settled)
        }
        val available = monitor.observations.value.state is DefaultRouteState.Available
        if (available) {
            val observed = withTimeoutOrNull(ROUTE_TIMEOUT_MS) {
                monitor.observations.first { observation ->
                    val state = observation.state as? DefaultRouteState.Available
                    state != null && state.capabilitiesReceived &&
                        (api < BLOCKED_API_FLOOR || state.capabilities.blocked != ObservedBoolean.UNKNOWN)
                }
            }
            if (observed != null) {
                recorder.evaluate(guard, observed)
            }
        }
        recorder.evaluate(guard, monitor.observations.value)

        monitor.shutdown()
        assertTrue(monitor.isClosed)
        monitor.shutdown()

        val events = recorder.events()
        assertEquals(RouteSignalKind.MONITOR_STARTED, events.first().signal)
        assertEquals(RouteSignalKind.MONITOR_STOPPED, events.last().signal)
        assertEquals(1, events.count { it.signal == RouteSignalKind.MONITOR_STOPPED })
        assertEquals((1L..events.size).toList(), events.map { it.sequence })
        assertTrue(events.zipWithNext().all { (a, b) -> a.elapsedRealtimeNs <= b.elapsedRealtimeNs })
        assertEquals(DefaultRouteState.Initializing, events.first().stateAfter)
        if (api >= Build.VERSION_CODES.N) {
            // The API 24+ bootstrap always resolves INITIALIZING. On API 23 a
            // snapshot that raced a route change twice is discarded instead.
            assertNotEquals(DefaultRouteState.Initializing, events.last().stateAfter)
        }

        assertSourcesMatchApi(api, events, available)
        assertApiFloors(api, events)
        assertTrue(recorder.policyEvaluations().isNotEmpty())

        val finalState = events.last().stateAfter
        if (finalState is DefaultRouteState.Available && finalState.capabilitiesReceived) {
            assertTrue("route epoch created", finalState.routeEpoch >= 1)
            assertTrue(
                "capability event processed",
                events.any {
                    it.observedCapabilities != null &&
                        it.disposition == RouteEventDisposition.APPLIED
                },
            )
            if (api >= SUSPENDED_API_FLOOR) {
                assertNotEquals(ObservedBoolean.UNKNOWN, finalState.capabilities.suspended)
            }
            if (api >= BLOCKED_API_FLOOR) {
                assertNotEquals(ObservedBoolean.UNKNOWN, finalState.capabilities.blocked)
            }
        }

        if (api >= EVIDENCE_EXPORT_API) {
            writeEvidence(api, recorder.toArtifactMap())
        } else {
            // API 23 cannot use the modern additional-test-output staging path;
            // the artifact must still render (bounded, complete).
            recorder.toArtifactMap()
        }
    }

    @Test
    fun repeatedOpenAndShutdownLeaksNoPlatformRegistration() = runBlocking<Unit> {
        // Android limits outstanding network callbacks per UID (~100). A leak
        // of one registration per cycle would fail well before the loop ends.
        repeat(LEAK_CYCLES) {
            val monitor = AndroidDefaultRouteMonitor.open(context, scope)
            // ConnectivityService releases a callback asynchronously on its
            // handler. Waiting for this cycle's first observed capabilities
            // (delivered from that same handler) lets the previous release
            // drain, so only a real leak can reach the per-UID limit.
            withTimeoutOrNull(LEAK_CYCLE_SETTLE_MS) {
                monitor.observations.first { observation ->
                    (observation.state as? DefaultRouteState.Available)?.capabilitiesReceived == true
                }
            }
            monitor.shutdown()
            assertTrue(monitor.isClosed)
        }
        val final = AndroidDefaultRouteMonitor.open(context, scope)
        final.shutdown()
    }

    @Test
    fun callbacksAfterShutdownDoNotChangeState() = runBlocking<Unit> {
        val recorder = RouteEvidenceRecorder("m2-b-shutdown", "m2-b-shutdown", Build.VERSION.SDK_INT)
        val monitor = AndroidDefaultRouteMonitor.open(context, scope, recorder)
        monitor.shutdown()
        val stopped = monitor.observations.value
        val eventCount = recorder.events().size

        Thread.sleep(POST_SHUTDOWN_WAIT_MS)

        assertEquals(stopped, monitor.observations.value)
        assertEquals(eventCount, recorder.events().size)
        assertEquals(RouteSignalKind.MONITOR_STOPPED, recorder.events().last().signal)
    }

    private fun assertSourcesMatchApi(
        api: Int,
        events: List<RouteEvent>,
        available: Boolean,
    ) {
        val sources = events.map { it.source }.toSet()
        if (api < Build.VERSION_CODES.N) {
            assertFalse(RouteSignalSource.DEFAULT_NETWORK_CALLBACK in sources)
            assertFalse(RouteSignalSource.BOOTSTRAP_ACTIVE_NETWORK in sources)
            assertTrue(RouteSignalSource.API23_ACTIVE_NETWORK_SNAPSHOT in sources)
        } else {
            assertFalse(RouteSignalSource.API23_ACTIVE_NETWORK_SNAPSHOT in sources)
            assertTrue(RouteSignalSource.BOOTSTRAP_ACTIVE_NETWORK in sources)
            if (available) {
                assertTrue(RouteSignalSource.DEFAULT_NETWORK_CALLBACK in sources)
            }
        }
    }

    private fun assertApiFloors(api: Int, events: List<RouteEvent>) {
        for (event in events) {
            val capabilities = (event.stateAfter as? DefaultRouteState.Available)?.capabilities
                ?: continue
            if (api < SUSPENDED_API_FLOOR) {
                assertEquals(ObservedBoolean.UNKNOWN, capabilities.suspended)
                assertEquals(ObservedBoolean.UNKNOWN, event.observedCapabilities?.suspended ?: ObservedBoolean.UNKNOWN)
            }
            if (api < BLOCKED_API_FLOOR) {
                assertEquals(ObservedBoolean.UNKNOWN, capabilities.blocked)
                assertNotEquals(RouteSignalKind.BLOCKED_CHANGED, event.signal)
            }
        }
    }

    private fun writeEvidence(api: Int, artifact: Map<String, Any?>) {
        val output = PlatformTestStorageRegistry.getInstance()
        output.openOutputFile("m2-b-route-evidence/route-events.json")
            .bufferedWriter()
            .use { writer ->
                writer.write(JSONObject(artifact).toString(2))
                writer.newLine()
            }
        output.openOutputFile("m2-b-route-evidence/android-api.txt")
            .bufferedWriter()
            .use { writer -> writer.write("$api\n") }
    }

    private companion object {
        const val ROUTE_TIMEOUT_MS = 10_000L
        const val POST_SHUTDOWN_WAIT_MS = 500L
        const val LEAK_CYCLES = 128
        const val LEAK_CYCLE_SETTLE_MS = 1_000L
        const val BLOCKED_API_FLOOR = 29
        const val EVIDENCE_EXPORT_API = 34
    }
}
