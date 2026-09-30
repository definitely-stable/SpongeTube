package io.github.definitelystable.spongetube.core.engine.route

import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import io.github.definitelystable.spongetube.core.engine.FetchBroker
import io.github.definitelystable.spongetube.core.engine.FetchEvent
import io.github.definitelystable.spongetube.core.engine.FetchEventKind
import io.github.definitelystable.spongetube.core.engine.FetchEventListener
import io.github.definitelystable.spongetube.core.engine.FetchKey
import io.github.definitelystable.spongetube.core.engine.FetchRequest
import io.github.definitelystable.spongetube.core.engine.HttpRangeFetchExecutor
import io.github.definitelystable.spongetube.core.engine.HttpRangeTarget
import io.github.definitelystable.spongetube.core.engine.PlatformHttpEnginePool
import io.github.definitelystable.spongetube.core.engine.PlatformHttpRangeFetchExecutor
import io.github.definitelystable.spongetube.core.engine.PlatformHttpTerminal
import io.github.definitelystable.spongetube.core.engine.TransportEvaluationBackend
import io.github.definitelystable.spongetube.core.engine.TransportEvaluationEligibility
import io.github.definitelystable.spongetube.core.engine.TransportEvaluationSelector
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryAttemptGate
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumer
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumerId
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumerKind
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryCoordinator
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryPolicy
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryTerminalReason
import io.github.definitelystable.spongetube.core.engine.recovery.RouteAwareRecoveryAttemptGate
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentSpec
import io.github.definitelystable.spongetube.core.storage.ExtentStore
import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import io.github.definitelystable.spongetube.core.storage.Sha256Digest
import java.io.File
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import io.github.definitelystable.spongetube.core.engine.FetchPriority
import io.github.definitelystable.spongetube.core.engine.FetchAttemptDisposition
import io.github.definitelystable.spongetube.core.engine.recovery.FailureObservation
import io.github.definitelystable.spongetube.core.engine.recovery.TransportIoKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G1 correctness proof on an API 36 default-route Media Lab namespace path.
 * These trials are sequential correctness checks, NOT G2 counterbalanced
 * performance measurements or physical-device performance evidence.
 */
@RunWith(AndroidJUnit4::class)
class PlatformHttpTransportAndroidTest {
    @Test(timeout = 120_000L)
    fun bothBackendsPublishExpectedCoverageThroughTheSameRouteGate() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT == 36)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context: Context = instrumentation.targetContext
        val origin = InstrumentationRegistry.getArguments()
            .getString("spongetube.m2g1.originBaseUrl")?.trimEnd('/')
        assumeTrue("G1 requires its isolated Media Lab origin", !origin.isNullOrBlank())
        val uri = URL(checkNotNull(origin) + "/fixtures/F1/segment-1-00001.m4s")
        File(context.filesDir, "sponge").deleteRecursively()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val monitor = AndroidDefaultRouteMonitor.open(instrumentation.context, scope)
        val store = ExtentStore.open(context)
        val pool = PlatformHttpEnginePool.createIfAvailable(context)
        val evidence = JSONArray()
        try {
            assertNotNull("API 36 must expose platform HttpEngine for G1", pool)
            val engine = checkNotNull(pool)
            val initial = withTimeout(30_000) {
                monitor.observations.first { observation ->
                    val state = observation.state as? DefaultRouteState.Available
                    state != null && state.capabilitiesReceived &&
                        state.capabilities.vpn == ObservedBoolean.FALSE &&
                        state.capabilities.internet != ObservedBoolean.FALSE &&
                        observation.executionBinding is PlatformHttpEngineRouteBinding
                }
            }
            val epoch = (initial.state as DefaultRouteState.Available).routeEpoch
            val control = HttpRangeFetchExecutor(
                targetFor = { HttpRangeTarget(uri, RESOURCE_LENGTH) },
                connectTimeoutMs = 12_000,
                readTimeoutMs = 12_000,
            )
            val candidateProtocol = AtomicReference("UNKNOWN")
            val platformTerminals = CopyOnWriteArrayList<PlatformHttpTerminal>()
            val candidate = PlatformHttpRangeFetchExecutor(
                pool = engine,
                targetFor = { HttpRangeTarget(uri, RESOURCE_LENGTH) },
                firstResponseTimeoutMs = 20_000,
                readTimeoutMs = 12_000,
                onProtocolObserved = candidateProtocol::set,
                onTerminalObserved = { platformTerminals += it },
            )
            val selector = TransportEvaluationSelector(control, candidate, engine.backendVersion)
            for (backend in listOf(
                TransportEvaluationBackend.HTTP_URL_CONNECTION_ROUTE_BOUND,
                TransportEvaluationBackend.PLATFORM_HTTP_ENGINE,
            )) {
                val selected = selector.select(backend)
                assertEquals(TransportEvaluationEligibility.ELIGIBLE, selected.eligibility)
                val events = CopyOnWriteArrayList<FetchEvent>()
                val sessionId = "m2-g1-" + backend.name.lowercase()
                val broker = FetchBroker(
                    extentStore = store,
                    executor = checkNotNull(selected.executor),
                    sessionId = sessionId,
                    eventListener = FetchEventListener { events += it },
                )
                val routeGate = RouteAwareRecoveryAttemptGate(
                    observations = monitor.observations,
                    guard = SessionRouteGuard(),
                )
                val coordinator = RecoveryCoordinator(
                    broker = broker,
                    sessionId = sessionId,
                    policy = RecoveryPolicy.DEFAULT,
                    attemptGate = RecoveryAttemptGate { chainId ->
                        routeGate.awaitPermit(chainId)
                    },
                )
                val id = "m2g1:" + backend.name.lowercase()
                try {
                    val handle = coordinator.acquire(
                        request(id),
                        RecoveryConsumer(
                            RecoveryConsumerId("playback-" + backend.name.lowercase()),
                            RecoveryConsumerKind.PLAYBACK,
                        ),
                    )
                    val outcome = withTimeout(45_000) { handle.await() }
                    handle.close()
                    assertEquals(RecoveryTerminalReason.SUCCESS, outcome.terminalReason)
                    assertEquals(1, events.count { it.event == FetchEventKind.ATTEMPT_STARTED })
                    assertEquals(1, events.count { it.event == FetchEventKind.ATTEMPT_COMPLETED })
                    val committed = store.committedExtents().single {
                        it.extentId.value == id && it.length == RESOURCE_LENGTH
                    }
                    assertEquals(RESOURCE_SHA256, committed.sha256.hex)
                    val correlation = events.single {
                        it.event == FetchEventKind.ATTEMPT_CORRELATED
                    }
                    val physicalOriginId = checkNotNull(
                        correlation.transportCorrelationId?.toLongOrNull(),
                    )
                    assertTrue(physicalOriginId > 0)
                    val observedEpoch = (monitor.observations.value.state as? DefaultRouteState.Available)
                        ?.routeEpoch
                    assertEquals("comparison changed permitted route", epoch, observedEpoch)
                    evidence.put(JSONObject().apply {
                        put("backend", backend.name)
                        put("eligibility", selected.eligibility.name)
                        put("result", "SUCCESS")
                        put("attempts", 1)
                        put("committedBytes", RESOURCE_LENGTH)
                        put("routeEpoch", epoch)
                        put("sha256", committed.sha256.hex)
                        put("originRequestId", physicalOriginId)
                        put("backendVersion", checkNotNull(selected.backendVersion))
                        put("implementationId", checkNotNull(selected.implementationId))
                        put("negotiatedProtocol", if (
                            backend == TransportEvaluationBackend.PLATFORM_HTTP_ENGINE
                        ) candidateProtocol.get() else "UNKNOWN")
                    })
                } finally {
                    coordinator.shutdown()
                    broker.shutdown()
                }
            }

            // Unbound candidate may never use the ambient/default network
            // or charge a physical attempt, even if HttpEngine is available.
            val unboundCharges = AtomicInteger()
            val unbound = candidate.executeCorrelatedWithAdmission(
                request("m2g1:unbound"), 1, MutableStateFlow(FetchPriority.PLAYBACK),
                onPhysicalAttemptStart = { unboundCharges.incrementAndGet() },
                onTransportCorrelation = { error("unbound candidate emitted correlation") },
                emitChunk = { error("unbound candidate emitted bytes") },
            )
            assertTrue(unbound is FetchAttemptDisposition.Failure)
            assertEquals(
                FailureObservation.TransportIo(TransportIoKind.TARGET_UNRESOLVED),
                (unbound as FetchAttemptDisposition.Failure).observation,
            )
            assertEquals(0, unboundCharges.get())

            // A real response is deliberately paused during emitChunk.
            // Cancellation must cancel UrlRequest and await its terminal
            // onCanceled callback before releasing session engine ownership.
            val firstChunk = CompletableDeferred<Unit>()
            val keepEmitterPaused = CompletableDeferred<Unit>()
            val cancelCharges = AtomicInteger()
            val cancelledOriginCorrelation = AtomicReference<String?>(null)
            val previousTerminalCount = platformTerminals.size
            val cancelTask = async {
                candidate.executeWithRouteBinding(
                    request = request("m2g1:cancellation"),
                    attempt = 1,
                    priority = MutableStateFlow(FetchPriority.PLAYBACK),
                    routeBinding = checkNotNull(initial.executionBinding),
                    deliveryBinding = null,
                    onPhysicalAttemptStart = { cancelCharges.incrementAndGet() },
                    onTransportCorrelation = { cancelledOriginCorrelation.set(it) },
                    emitChunk = {
                        firstChunk.complete(Unit)
                        keepEmitterPaused.await()
                    },
                )
            }
            withTimeout(30_000) { firstChunk.await() }
            assertEquals(1, cancelCharges.get())
            cancelTask.cancelAndJoin()
            assertEquals(
                "Only an actual onCanceled callback proves cancellation",
                listOf(PlatformHttpTerminal.CANCELED),
                platformTerminals.drop(previousTerminalCount),
            )
            val cancelledOriginId = checkNotNull(
                cancelledOriginCorrelation.get()?.toLongOrNull(),
            )
            assertTrue(cancelledOriginId > 0)
            assertEquals(
                "UrlRequest never acknowledged cancellation",
                0, engine.activeRequestCount,
            )

            PlatformTestStorageRegistry.getInstance().openOutputFile(
                "m2-g1-platform/case.json",
            ).bufferedWriter().use { writer ->
                writer.write(JSONObject().apply {
                    put("schemaVersion", 1)
                    put("phase", "M2-G1")
                    put("api", 36)
                    put("deviceClass", "ANDROID_EMULATOR")
                    put("mediaPath", "ANDROID_DEFAULT_NETWORK")
                    put("trials", evidence)
                    put("unboundCharges", unboundCharges.get())
                    put("cancelledRequestCharges", cancelCharges.get())
                    put("cancelledOriginRequestId", cancelledOriginId)
                    put("cancelTerminal", platformTerminals.last().name)
                    put("cancelledRequestAcknowledged", engine.activeRequestCount == 0)
                }.toString() + "\n")
            }
        } finally {
            monitor.shutdown()
            scope.cancel()
            pool?.shutdown()
            store.close()
            File(context.filesDir, "sponge").deleteRecursively()
        }
    }

    private fun request(id: String): FetchRequest = FetchRequest(
        fetchKey = FetchKey("fixture:F1/audio-main/f1-audio-1/" + id),
        extentSpec = ExtentSpec(
            mediaAssetId = MediaAssetId("fixture:F1"),
            extentId = ExtentId(id),
            trackId = "audio-main",
            representationId = "f1-audio-1",
            mediaStartUs = 0,
            mediaEndUs = 9_941_333,
            byteStart = 0,
            byteEndExclusive = RESOURCE_LENGTH,
            expectedLength = RESOURCE_LENGTH,
            expectedSha256 = Sha256Digest(RESOURCE_SHA256),
        ),
    )

    private companion object {
        const val RESOURCE_LENGTH = 81_811L
        const val RESOURCE_SHA256 =
            "08ac93538dcb3f5eece5996b0abab1e4e7677afbc7b21cc3292a63c776ef4943"
    }
}
