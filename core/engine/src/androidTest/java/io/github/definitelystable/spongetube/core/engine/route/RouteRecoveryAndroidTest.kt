package io.github.definitelystable.spongetube.core.engine.route

import android.app.Instrumentation
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import io.github.definitelystable.spongetube.core.engine.FetchAttemptDisposition
import io.github.definitelystable.spongetube.core.engine.FetchBroker
import io.github.definitelystable.spongetube.core.engine.FetchEvent
import io.github.definitelystable.spongetube.core.engine.FetchEventKind
import io.github.definitelystable.spongetube.core.engine.FetchEventListener
import io.github.definitelystable.spongetube.core.engine.FetchKey
import io.github.definitelystable.spongetube.core.engine.FetchNetworkChunk
import io.github.definitelystable.spongetube.core.engine.FetchPriority
import io.github.definitelystable.spongetube.core.engine.FetchRequest
import io.github.definitelystable.spongetube.core.engine.HttpRangeFetchExecutor
import io.github.definitelystable.spongetube.core.engine.HttpRangeTarget
import io.github.definitelystable.spongetube.core.engine.RouteBoundFetchAttemptExecutor
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingSnapshot
import io.github.definitelystable.spongetube.core.engine.recovery.FailureClassification
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryActionKind
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryAttemptGate
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryAttemptPermit
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryBudgetDimension
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryBudgetEventKind
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumer
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumerId
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumerKind
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryCoordinator
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryEvidenceRecorder
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryJitterSource
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryPolicy
import io.github.definitelystable.spongetube.core.engine.recovery.RecoverySleeper
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryTerminalReason
import io.github.definitelystable.spongetube.core.engine.recovery.RouteAwareRecoveryAttemptGate
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentSpec
import io.github.definitelystable.spongetube.core.storage.ExtentStore
import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import io.github.definitelystable.spongetube.core.storage.Sha256Digest
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M2-F2 acceptance slice.
 *
 * Proves one real RecoveryChain across an actual Android default-route loss
 * and replacement. Owner 1 is admitted on the initial exact Network, then a
 * test-only barrier stops immediately before connect(). The device loses its
 * actual default route, owner 1 fails on the now-dead exact binding, recovery
 * reaches the route-aware gate and remains there without a second owner or
 * charge. Restoring connectivity produces a new routeEpoch and owner 2 uses
 * that replacement binding to complete the real Media Lab range request.
 */
@RunWith(AndroidJUnit4::class)
class RouteRecoveryAndroidTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        cleanStoreRoot()
    }

    @After
    fun tearDown() {
        cleanStoreRoot()
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun oneRecoveryChainSurvivesActualDefaultRouteLossAndReplacement() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT == 36)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originBaseUrl = InstrumentationRegistry.getArguments()
            .getString(ORIGIN_ARGUMENT)
            ?.trimEnd('/')
        assumeTrue("M2-F2 requires $ORIGIN_ARGUMENT", !originBaseUrl.isNullOrBlank())
        val origin = checkNotNull(originBaseUrl)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val routeRecorder = RouteEvidenceRecorder(
            runId = RUN_ID,
            sessionId = SESSION_ID,
            androidApi = Build.VERSION.SDK_INT,
        )
        val recoveryRecorder = RecoveryEvidenceRecorder(
            runId = RUN_ID,
            sessionId = SESSION_ID,
        )
        val monitor = AndroidDefaultRouteMonitor.open(
            instrumentation.context,
            scope,
            routeRecorder,
        )
        val guard = SessionRouteGuard()
        val exactGate = RouteAwareRecoveryAttemptGate(
            observations = monitor.observations,
            guard = guard,
            evidence = routeRecorder,
        )
        val gateCalls = AtomicInteger()
        val secondGateEntered = CompletableDeferred<Unit>()
        val gate = RecoveryAttemptGate { chainId ->
            val ordinal = gateCalls.incrementAndGet()
            if (ordinal == 2) {
                secondGateEntered.complete(Unit)
            }
            exactGate.awaitPermit(chainId)
        }

        val firstAdmitted = CountDownLatch(1)
        val releaseFirstConnect = CountDownLatch(1)
        val fetchEvents = CopyOnWriteArrayList<FetchEvent>()
        val store = ExtentStore.open(context)
        val request = request()
        var monitorStopped = false
        var coordinator: RecoveryCoordinator? = null
        var broker: FetchBroker? = null
        var persistedBefore = emptyList<String>()
        var persistedAfter = emptyList<String>()
        var chainId = ""
        var initialEpoch = 0L
        var restoredEpoch = 0L
        var chargesBeforeRestore = 0
        var attemptsBeforeRestore = 0

        try {
            seedSentinel(store)
            persistedBefore = committedExtentIds(store)
            assertEquals(listOf(SENTINEL_EXTENT_ID), persistedBefore)

            val initial = awaitDirectObservation(monitor)
            initialEpoch = (initial.state as DefaultRouteState.Available).routeEpoch
            assertTrue(initial.executionBinding != null)

            val delegate = HttpRangeFetchExecutor(
                targetFor = {
                    HttpRangeTarget(
                        url = URL("$origin$MEDIA_PATH"),
                        resourceLength = RESOURCE_LENGTH,
                    )
                },
                connectTimeoutMs = HTTP_TIMEOUT_MS,
                readTimeoutMs = HTTP_TIMEOUT_MS,
            )
            val synchronizedExecutor = FirstAdmissionBarrierExecutor(
                delegate = delegate,
                firstAdmitted = firstAdmitted,
                releaseFirstConnect = releaseFirstConnect,
            )
            broker = FetchBroker(
                extentStore = store,
                executor = synchronizedExecutor,
                sessionId = SESSION_ID,
                eventListener = FetchEventListener { fetchEvents += it },
            )
            coordinator = RecoveryCoordinator(
                broker = broker,
                sessionId = SESSION_ID,
                policy = RecoveryPolicy.DEFAULT,
                attemptGate = gate,
                jitter = RecoveryJitterSource { 0L },
                sleeper = RecoverySleeper { },
                evidence = recoveryRecorder,
            )

            val handle = coordinator.acquire(
                request,
                RecoveryConsumer(
                    RecoveryConsumerId("playback-m2f2"),
                    RecoveryConsumerKind.PLAYBACK,
                ),
            )
            chainId = handle.recoveryChainId.value
            val outcomeDeferred = async { handle.await() }

            assertTrue(
                "owner 1 was not admitted",
                withContext(Dispatchers.IO) {
                    firstAdmitted.await(BARRIER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                },
            )
            assertEquals(1, recoveryRecorder.chargeEvents())
            assertEquals(1, fetchEvents.count { it.event == FetchEventKind.ATTEMPT_STARTED })

            setConnectivityEnabled(instrumentation, enabled = false)
            withTimeout(ROUTE_TIMEOUT_MS) {
                monitor.observations.first { it.state == DefaultRouteState.Unavailable }
            }

            releaseFirstConnect.countDown()
            withTimeout(ROUTE_TIMEOUT_MS) { secondGateEntered.await() }
            assertEquals(DefaultRouteState.Unavailable, monitor.observations.value.state)

            withTimeout(ROUTE_TIMEOUT_MS) {
                while (
                    routeRecorder.policyEvaluations().none {
                        it.decision.reason == ExternalFetchRouteReason.NO_USABLE_DEFAULT
                    }
                ) {
                    yield()
                }
            }

            chargesBeforeRestore = recoveryRecorder.chargeEvents()
            attemptsBeforeRestore =
                fetchEvents.count { it.event == FetchEventKind.ATTEMPT_STARTED }
            assertEquals(1, chargesBeforeRestore)
            assertEquals(1, attemptsBeforeRestore)
            assertEquals(2, gateCalls.get())

            setConnectivityEnabled(instrumentation, enabled = true)
            val restored = awaitDirectObservation(
                monitor = monitor,
                minimumEpochExclusive = initialEpoch,
            )
            restoredEpoch = (restored.state as DefaultRouteState.Available).routeEpoch
            assertTrue(restoredEpoch > initialEpoch)
            assertNotEquals(initial.executionBinding, restored.executionBinding)

            val outcome = withTimeout(OUTCOME_TIMEOUT_MS) { outcomeDeferred.await() }
            persistedAfter = committedExtentIds(store)

            assertEquals(RecoveryTerminalReason.SUCCESS, outcome.terminalReason)
            assertEquals(chainId, outcome.recoveryChainId.value)
            assertEquals(request.fetchKey, outcome.fetchKey)
            assertEquals(2, recoveryRecorder.chargeEvents())
            assertEquals(
                listOf(1, 2),
                recoveryRecorder.budgetEvents()
                    .filter { it.kind == RecoveryBudgetEventKind.CHARGE }
                    .map {
                        it.spent.getValue(RecoveryBudgetDimension.REMOTE_ATTEMPT)
                    },
            )
            assertEquals(
                listOf(initialEpoch, restoredEpoch),
                recoveryRecorder.budgetEvents()
                    .filter { it.kind == RecoveryBudgetEventKind.ATTEMPT_PERMIT_GRANTED }
                    .map { checkNotNull(it.permit?.routeEpoch) },
            )
            assertEquals(
                2,
                fetchEvents.count { it.event == FetchEventKind.ATTEMPT_STARTED },
            )
            val failure = recoveryRecorder.failures().single()
            assertEquals(FailureClassification.TRANSIENT_TRANSPORT, failure.classification)
            assertEquals(RecoveryActionKind.SCHEDULE_BACKOFF, failure.action.kind)
            assertEquals(initialEpoch, failure.routeEpoch)
            assertTrue(persistedAfter.contains(SENTINEL_EXTENT_ID))
            assertTrue(persistedAfter.contains(TARGET_EXTENT_ID))

            monitor.shutdown()
            monitorStopped = true
            assertTrue(monitor.isClosed)

            writeEvidence(
                routeRecorder = routeRecorder,
                recoveryRecorder = recoveryRecorder,
                fetchEvents = fetchEvents,
                case = linkedMapOf(
                    "schemaVersion" to 1,
                    "phase" to "M2-F2",
                    "deviceApi" to Build.VERSION.SDK_INT,
                    "status" to "PASS",
                    "recoveryChainId" to chainId,
                    "initialRouteEpoch" to initialEpoch,
                    "restoredRouteEpoch" to restoredEpoch,
                    "gateCalls" to gateCalls.get(),
                    "chargesBeforeRestore" to chargesBeforeRestore,
                    "attemptsBeforeRestore" to attemptsBeforeRestore,
                    "persistedExtentIdsBefore" to persistedBefore,
                    "persistedExtentIdsAfter" to persistedAfter,
                    "sentinelExtentId" to SENTINEL_EXTENT_ID,
                    "targetExtentId" to TARGET_EXTENT_ID,
                    "mediaPathUsesAdbReverse" to false,
                    "monitorStopped" to true,
                ),
            )
        } finally {
            releaseFirstConnect.countDown()
            runCatching { setConnectivityEnabled(instrumentation, enabled = true) }
            runCatching { coordinator?.shutdown() }
            runCatching { broker?.shutdown() }
            if (!monitorStopped) {
                runCatching { monitor.shutdown() }
            }
            runCatching { store.close() }
            scope.cancel()
        }
    }

    private suspend fun awaitDirectObservation(
        monitor: DefaultRouteMonitor,
        minimumEpochExclusive: Long = 0L,
    ): RouteObservation =
        withTimeout(ROUTE_TIMEOUT_MS) {
            monitor.observations.first { observation ->
                val state = observation.state as? DefaultRouteState.Available
                state != null &&
                    state.capabilitiesReceived &&
                    state.routeEpoch > minimumEpochExclusive &&
                    state.capabilities.vpn == ObservedBoolean.FALSE &&
                    state.capabilities.internet != ObservedBoolean.FALSE &&
                    observation.executionBinding != null
            }
        }

    private suspend fun setConnectivityEnabled(
        instrumentation: Instrumentation,
        enabled: Boolean,
    ) {
        val commands = if (enabled) {
            listOf(
                "cmd connectivity airplane-mode disable",
                "svc data enable",
                "svc wifi enable",
            )
        } else {
            listOf(
                "svc wifi disable",
                "svc data disable",
                "cmd connectivity airplane-mode enable",
            )
        }
        for (command in commands) {
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command),
            ).bufferedReader().use { it.readText() }
        }
    }

    private suspend fun seedSentinel(store: ExtentStore) {
        val spec = ExtentSpec(
            mediaAssetId = MediaAssetId("fixture:M2F2-sentinel"),
            extentId = ExtentId(SENTINEL_EXTENT_ID),
            trackId = "sentinel-track",
            representationId = "sentinel-r1",
            mediaStartUs = 0,
            mediaEndUs = 1,
            byteStart = 0,
            byteEndExclusive = SENTINEL_BYTES.size.toLong(),
            expectedLength = SENTINEL_BYTES.size.toLong(),
            expectedSha256 = Sha256Digest(SENTINEL_SHA256),
        )
        store.writeExtent(spec) {
            write(SENTINEL_BYTES)
        }
    }

    private suspend fun committedExtentIds(store: ExtentStore): List<String> =
        store.committedExtents().map { it.extentId.value }.sorted()

    private fun request(): FetchRequest =
        FetchRequest(
            fetchKey = FetchKey(FETCH_KEY),
            extentSpec = ExtentSpec(
                mediaAssetId = MediaAssetId("fixture:F1"),
                extentId = ExtentId(TARGET_EXTENT_ID),
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

    private fun RecoveryEvidenceRecorder.chargeEvents(): Int =
        budgetEvents().count { it.kind == RecoveryBudgetEventKind.CHARGE }

    private fun writeEvidence(
        routeRecorder: RouteEvidenceRecorder,
        recoveryRecorder: RecoveryEvidenceRecorder,
        fetchEvents: List<FetchEvent>,
        case: Map<String, Any?>,
    ) {
        val output = PlatformTestStorageRegistry.getInstance()
        val policyId = RecoveryPolicy.DEFAULT.policyId

        fun write(name: String, value: String) {
            output.openOutputFile("$EVIDENCE_DIR/$name")
                .bufferedWriter()
                .use { it.write(value) }
        }

        write("case.json", json(case) + "\n")
        write("route-events.json", json(routeRecorder.toArtifactMap()) + "\n")
        write(
            "recovery-budget-events.json",
            json(recoveryRecorder.budgetArtifact(policyId)) + "\n",
        )
        write(
            "failure-decision-events.json",
            json(recoveryRecorder.failureArtifact(policyId)) + "\n",
        )
        write(
            "fetch-events.jsonl",
            fetchEvents.sortedBy(FetchEvent::eventSequence)
                .joinToString(separator = "\n", postfix = "\n") {
                    json(it.toArtifactMap())
                },
        )
    }

    private fun cleanStoreRoot() {
        File(context.filesDir, "sponge").deleteRecursively()
    }

    private class FirstAdmissionBarrierExecutor(
        private val delegate: HttpRangeFetchExecutor,
        private val firstAdmitted: CountDownLatch,
        private val releaseFirstConnect: CountDownLatch,
    ) : RouteBoundFetchAttemptExecutor {
        private val routeExecutions = AtomicInteger()

        override suspend fun execute(
            request: FetchRequest,
            attempt: Int,
            priority: StateFlow<FetchPriority>,
            emitChunk: suspend (FetchNetworkChunk) -> Unit,
        ): FetchAttemptDisposition =
            error("M2-F2 requires an exact route-bound execution")

        override suspend fun executeCorrelated(
            request: FetchRequest,
            attempt: Int,
            priority: StateFlow<FetchPriority>,
            onTransportCorrelation: suspend (String) -> Unit,
            emitChunk: suspend (FetchNetworkChunk) -> Unit,
        ): FetchAttemptDisposition =
            error("M2-F2 requires an exact route-bound execution")

        override suspend fun executeWithRouteBinding(
            request: FetchRequest,
            attempt: Int,
            priority: StateFlow<FetchPriority>,
            routeBinding: RouteExecutionBinding,
            deliveryBinding: DeliveryBindingSnapshot?,
            onPhysicalAttemptStart: () -> Unit,
            onTransportCorrelation: suspend (String) -> Unit,
            emitChunk: suspend (FetchNetworkChunk) -> Unit,
        ): FetchAttemptDisposition {
            val ordinal = routeExecutions.incrementAndGet()
            return delegate.executeWithRouteBinding(
                request = request,
                attempt = attempt,
                priority = priority,
                routeBinding = routeBinding,
                deliveryBinding = deliveryBinding,
                onPhysicalAttemptStart = {
                    onPhysicalAttemptStart()
                    if (ordinal == 1) {
                        firstAdmitted.countDown()
                        check(
                            releaseFirstConnect.await(
                                BARRIER_TIMEOUT_MS,
                                TimeUnit.MILLISECONDS,
                            ),
                        ) {
                            "M2-F2 first owner barrier timed out"
                        }
                    }
                },
                onTransportCorrelation = onTransportCorrelation,
                emitChunk = emitChunk,
            )
        }
    }

    private companion object {
        const val ORIGIN_ARGUMENT = "spongetube.m2f2.originBaseUrl"
        const val RUN_ID = "m2-f2-api36-route-recovery"
        const val SESSION_ID = "m2-f2-route-recovery"
        const val EVIDENCE_DIR = "m2-f2-route-recovery"

        const val MEDIA_PATH = "/fixtures/F1/segment-1-00001.m4s"
        const val FETCH_KEY = "fixture:F1/audio-main/f1-audio-1/segment-1-00001"
        const val TARGET_EXTENT_ID = "m2f2:f1:audio:1:1"
        const val RESOURCE_LENGTH = 81_811L
        const val RESOURCE_SHA256 =
            "08ac93538dcb3f5eece5996b0abab1e4e7677afbc7b21cc3292a63c776ef4943"

        const val SENTINEL_EXTENT_ID = "m2f2:sentinel"
        val SENTINEL_BYTES = byteArrayOf(11, 22, 33, 44)
        const val SENTINEL_SHA256 =
            "bdbdce312f5762571faa17f3f4780235ed2c54f966c0f60dace1cddd2634f4f3"

        const val HTTP_TIMEOUT_MS = 7_500
        const val BARRIER_TIMEOUT_MS = 15_000L
        const val ROUTE_TIMEOUT_MS = 30_000L
        const val OUTCOME_TIMEOUT_MS = 45_000L
        const val TEST_TIMEOUT_MS = 120_000L

        fun json(value: Any?): String = when (value) {
            null -> "null"
            is String -> buildString {
                append('"')
                value.forEach { char ->
                    when (char) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        else -> append(char)
                    }
                }
                append('"')
            }
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") {
                (key, item) -> json(key.toString()) + ":" + json(item)
            }
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { json(it) }
            else -> error("unsupported JSON value: $value")
        }
    }
}
