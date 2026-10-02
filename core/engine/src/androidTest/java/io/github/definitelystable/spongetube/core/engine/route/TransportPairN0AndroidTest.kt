package io.github.definitelystable.spongetube.core.engine.route

import android.content.Context
import android.os.Build
import android.os.Process
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
import io.github.definitelystable.spongetube.core.engine.TransportEvaluationBackend
import io.github.definitelystable.spongetube.core.engine.TransportEvaluationEligibility
import io.github.definitelystable.spongetube.core.engine.TransportEvaluationSelector
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingCoordinator
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingRefresher
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryMaterial
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryAttemptGate
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryBudgetEventKind
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumer
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumerId
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumerKind
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryCoordinator
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryEvidenceRecorder
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryJitterSource
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryPolicy
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryTerminalReason
import io.github.definitelystable.spongetube.core.engine.recovery.RouteAwareRecoveryAttemptGate
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentSpec
import io.github.definitelystable.spongetube.core.storage.ExtentStore
import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import io.github.definitelystable.spongetube.core.storage.Sha256Digest
import java.io.File
import java.math.BigInteger
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
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
 * G2-B executes exactly ONE frozen N0 trial per instrumentation process.
 *
 * CI invokes this test once for every planned row. A fresh Android test process
 * is therefore the transport-session reset barrier for COLD v1: HUC process
 * pools and the candidate HttpEngine cannot leak connection history between
 * backends or ordering blocks.
 */
@RunWith(AndroidJUnit4::class)
class TransportPairN0AndroidTest {
    @Test(timeout = 90_000L)
    fun executeOneFrozenN0Trial() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT == 36)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val origin = arguments.getString(ARG_ORIGIN)?.trimEnd('/')
        val trialId = arguments.getString(ARG_TRIAL_ID)
        val jitterSeed = arguments.getString(ARG_RECOVERY_JITTER_SEED)?.toLongOrNull()
        assumeTrue("G2-B requires an explicit origin", !origin.isNullOrBlank())
        assumeTrue("G2-B requires an explicit frozen trial id", !trialId.isNullOrBlank())
        assumeTrue("G2-B requires the frozen recovery-jitter seed", jitterSeed != null)

        val plan = instrumentation.context.assets.open(PLAN_ASSET)
            .bufferedReader()
            .use { JSONObject(it.readText()) }
        val planned = findTrial(plan, checkNotNull(trialId))
        val backend = TransportEvaluationBackend.valueOf(planned.getString("backendId"))
        val context: Context = instrumentation.targetContext
        val uri = URL(checkNotNull(origin) + RESOURCE_PATH)

        // Fresh process + empty persistent store are both retained as raw proof.
        File(context.filesDir, "sponge").deleteRecursively()
        val store = ExtentStore.open(context)
        val extentStoreInitiallyEmpty = store.committedExtents().isEmpty()
        assertTrue("G2-B COLD trial inherited persisted coverage", extentStoreInitiallyEmpty)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val monitor = AndroidDefaultRouteMonitor.open(instrumentation.context, scope)
        var pool: PlatformHttpEnginePool? = null
        try {
            val route = withTimeout(30_000) {
                monitor.observations.first { observation ->
                    val state = observation.state as? DefaultRouteState.Available
                    state != null &&
                        state.capabilitiesReceived &&
                        state.capabilities.vpn == ObservedBoolean.FALSE &&
                        state.capabilities.internet != ObservedBoolean.FALSE &&
                        observation.executionBinding != null &&
                        (
                            backend != TransportEvaluationBackend.PLATFORM_HTTP_ENGINE ||
                                observation.executionBinding is PlatformHttpEngineRouteBinding
                        )
                }
            }
            val initialEpoch = (route.state as DefaultRouteState.Available).routeEpoch
            val deliveryMaterial = object : DeliveryMaterial {}
            val bindingHits = AtomicInteger()
            val protocol = AtomicReference("UNKNOWN")
            val target = HttpRangeTarget(uri, RESOURCE_LENGTH)
            val control = HttpRangeFetchExecutor(
                targetFor = { target },
                bindingTargetFor = { _, selected ->
                    assertTrue(selected === deliveryMaterial)
                    bindingHits.incrementAndGet()
                    target
                },
                connectTimeoutMs = 12_000,
                readTimeoutMs = 12_000,
            )

            val candidate = if (backend == TransportEvaluationBackend.PLATFORM_HTTP_ENGINE) {
                pool = PlatformHttpEnginePool.createIfAvailable(context)
                val engine = pool
                assertNotNull("API36 G2-B candidate must remain available after G1", engine)
                PlatformHttpRangeFetchExecutor(
                    pool = checkNotNull(engine),
                    targetFor = { target },
                    bindingTargetFor = { _, selected ->
                        assertTrue(selected === deliveryMaterial)
                        bindingHits.incrementAndGet()
                        target
                    },
                    firstResponseTimeoutMs = 20_000,
                    readTimeoutMs = 12_000,
                    onProtocolObserved = protocol::set,
                )
            } else {
                null
            }

            val selector = TransportEvaluationSelector(
                control = control,
                candidate = candidate,
                candidateVersion = pool?.backendVersion,
            )
            val selected = selector.select(backend)
            assertEquals(TransportEvaluationEligibility.ELIGIBLE, selected.eligibility)
            val executor = checkNotNull(selected.executor)

            val events = CopyOnWriteArrayList<FetchEvent>()
            val attemptCpuStartMs = AtomicLong(-1)
            val attemptCpuEndMs = AtomicLong(-1)
            val sessionId = "m2-g2-n0-" + planned.getString("trialId")
            val broker = FetchBroker(
                extentStore = store,
                executor = executor,
                sessionId = sessionId,
                eventListener = FetchEventListener { event ->
                    events += event
                    when (event.event) {
                        FetchEventKind.ATTEMPT_STARTED ->
                            attemptCpuStartMs.compareAndSet(-1, Process.getElapsedCpuTime())
                        FetchEventKind.ATTEMPT_COMPLETED ->
                            attemptCpuEndMs.compareAndSet(-1, Process.getElapsedCpuTime())
                        else -> Unit
                    }
                },
            )
            val recoveryEvidence = RecoveryEvidenceRecorder(
                runId = plan.getString("runId"),
                sessionId = sessionId,
            )
            val jitter = G2RecoveryJitter(checkNotNull(jitterSeed))
            // Device-side test vector guards the cross-language frozen sampler
            // without consuming the per-trial sampler passed to RecoveryCoordinator.
            assertEquals(367L, G2RecoveryJitter(checkNotNull(jitterSeed)).uniformInclusive(500L))
            val bindings = DeliveryBindingCoordinator(
                initialMaterial = deliveryMaterial,
                refresher = DeliveryBindingRefresher { _, _ ->
                    error("N0 must not refresh delivery binding")
                },
            )
            val routeGate = RouteAwareRecoveryAttemptGate(
                observations = monitor.observations,
                guard = SessionRouteGuard(),
            )
            val coordinator = RecoveryCoordinator(
                broker = broker,
                sessionId = sessionId,
                policy = RecoveryPolicy.DEFAULT,
                bindings = bindings,
                attemptGate = RecoveryAttemptGate { chainId -> routeGate.awaitPermit(chainId) },
                jitter = jitter,
                evidence = recoveryEvidence,
            )

            val extentId = "m2g2n0:" + planned.getString("trialId")
            try {
                val handle = coordinator.acquire(
                    request(extentId),
                    RecoveryConsumer(
                        id = RecoveryConsumerId("playback-" + planned.getString("trialId")),
                        kind = RecoveryConsumerKind.PLAYBACK,
                    ),
                )
                val outcome = withTimeout(45_000) { handle.await() }
                handle.close()
                assertEquals(RecoveryTerminalReason.SUCCESS, outcome.terminalReason)
            } finally {
                coordinator.shutdown()
                broker.shutdown()
            }

            val committed = store.committedExtents().single {
                it.extentId.value == extentId && it.length == RESOURCE_LENGTH
            }
            assertEquals(RESOURCE_SHA256, committed.sha256.hex)
            assertEquals(1, bindingHits.get())

            val attemptStarted = events.single { it.event == FetchEventKind.ATTEMPT_STARTED }
            val firstProgress = events.first { it.event == FetchEventKind.ATTEMPT_PROGRESS }
            val attemptCompleted = events.single { it.event == FetchEventKind.ATTEMPT_COMPLETED }
            val correlation = events.single { it.event == FetchEventKind.ATTEMPT_CORRELATED }
            val originRequestId = checkNotNull(correlation.transportCorrelationId?.toLongOrNull())
            val firstByteUs = nanosToMicros(
                firstProgress.eventElapsedRealtimeNs - attemptStarted.eventElapsedRealtimeNs,
            )
            val completionUs = nanosToMicros(
                attemptCompleted.eventElapsedRealtimeNs - attemptStarted.eventElapsedRealtimeNs,
            )
            assertTrue(firstByteUs >= 0)
            assertTrue(completionUs >= firstByteUs)

            val budget = recoveryEvidence.budgetEvents()
            val chainStarts = budget.count { it.kind == RecoveryBudgetEventKind.CHAIN_STARTED }
            val owners = budget.count { it.kind == RecoveryBudgetEventKind.OWNER_STARTED }
            val remoteCharges = budget.count { it.kind == RecoveryBudgetEventKind.CHARGE }
            val permitEpoch = budget.single {
                it.kind == RecoveryBudgetEventKind.ATTEMPT_PERMIT_GRANTED
            }.permit?.routeEpoch
            assertEquals(1, chainStarts)
            assertEquals(1, owners)
            assertEquals(1, remoteCharges)
            assertEquals(initialEpoch, permitEpoch)

            val finalEpoch = (monitor.observations.value.state as? DefaultRouteState.Available)
                ?.routeEpoch
            assertEquals(initialEpoch, finalEpoch)
            val cpuStart = attemptCpuStartMs.get()
            val cpuEnd = attemptCpuEndMs.get()
            assertTrue(cpuStart >= 0 && cpuEnd >= cpuStart)
            val cpuTimeUs = (cpuEnd - cpuStart) * 1_000L
            val maxRssBytes = readVmHwmBytes()
            assertTrue(maxRssBytes == null || maxRssBytes > 0)

            val g0Row = JSONObject().apply {
                put("trialId", planned.getString("trialId"))
                put("orderingBlock", planned.getInt("orderingBlock"))
                put("positionInBlock", planned.getInt("positionInBlock"))
                put("backendId", backend.name)
                put("backendVersion", checkNotNull(selected.backendVersion))
                put("implementationId", checkNotNull(selected.implementationId))
                put("eligibility", selected.eligibility.name)
                put("comparison", JSONObject(plan.getJSONObject("comparison").toString()))
                put("route", JSONObject().apply {
                    put("exactNetworkBound", true)
                    put("permitRouteEpoch", checkNotNull(permitEpoch))
                })
                put("result", "SUCCESS")
                put("requestCorrectness", JSONObject().apply {
                    put("range", "PASS")
                    put("contentRange", "PASS")
                    put("responseBounds", "PASS")
                    put("publishedBytes", "PASS")
                })
                put("recovery", JSONObject().apply {
                    put("recoveryChainCount", chainStarts)
                    put("ownerCount", owners)
                    put("originRequestCount", 1)
                    put("internalRetryVisibility", "OBSERVABLE")
                    put("internalRetryCount", 0)
                })
                put("metrics", JSONObject().apply {
                    put("firstByteUs", firstByteUs)
                    put("completionUs", completionUs)
                    put("cancellationLatencyUs", JSONObject.NULL)
                    put("cpuTimeUs", cpuTimeUs)
                    put("maxRssBytes", maxRssBytes ?: JSONObject.NULL)
                    put("bytesRequested", RESOURCE_LENGTH)
                    put("bytesReceived", attemptCompleted.networkBytes)
                    put("bytesPublished", committed.length)
                })
                put(
                    "negotiatedProtocol",
                    if (backend == TransportEvaluationBackend.PLATFORM_HTTP_ENGINE) {
                        protocol.get()
                    } else {
                        "UNKNOWN"
                    },
                )
                put("performanceSampleEligible", true)
                put("limitations", JSONArray().apply {
                    put("API36_EMULATOR_DIRECTIONAL_ONLY")
                    put("MAX_RSS_IS_FRESH_PROCESS_HIGH_WATER")
                    put("FIRST_BYTE_IS_FIRST_ACCEPTED_16K_CHUNK")
                    put("CANCELLATION_NOT_EXERCISED_IN_N0")
                })
            }

            val raw = JSONObject().apply {
                put("schemaVersion", 1)
                put("phase", "M2-G2-B-N0")
                put("runId", plan.getString("runId"))
                put("pairId", plan.getString("pairId"))
                put("trial", g0Row)
                put("proof", JSONObject().apply {
                    put("originRequestId", originRequestId)
                    put("committedSha256", committed.sha256.hex)
                    put("committedBytes", committed.length)
                    put("processInstanceId", PROCESS_INSTANCE_ID)
                    put("processPid", Process.myPid())
                    put("processStartClockTicks", readProcessStartClockTicks())
                    put("androidApi", Build.VERSION.SDK_INT)
                    put("primaryAbi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
                    put("extentStoreInitiallyEmpty", extentStoreInitiallyEmpty)
                    put("transportSessionFresh", true)
                    put("routeEpochBefore", initialEpoch)
                    put("routeEpochAfter", checkNotNull(finalEpoch))
                    put("bindingRevision", "binding-1")
                    put("bindingTargetResolved", bindingHits.get() == 1)
                    put("attemptStartedCount", events.count {
                        it.event == FetchEventKind.ATTEMPT_STARTED
                    })
                    put("attemptCompletedCount", events.count {
                        it.event == FetchEventKind.ATTEMPT_COMPLETED
                    })
                    put("attemptProgressCount", events.count {
                        it.event == FetchEventKind.ATTEMPT_PROGRESS
                    })
                    put("attemptCorrelationCount", events.count {
                        it.event == FetchEventKind.ATTEMPT_CORRELATED
                    })
                    put("remoteAttemptChargeCount", remoteCharges)
                    put("recoveryJitterProtocol", RECOVERY_JITTER_PROTOCOL)
                    put("recoveryJitterSeed", jitterSeed)
                    put("recoveryJitterSampleCount", jitter.samples.size)
                    put("recoveryJitterSamples", JSONArray(jitter.samples))
                })
            }
            PlatformTestStorageRegistry.getInstance().openOutputFile(
                "m2-g2-n0/" + planned.getString("trialId") + ".json",
            ).bufferedWriter().use { writer ->
                writer.write(raw.toString())
                writer.write("\n")
            }
        } finally {
            monitor.shutdown()
            scope.cancel()
            pool?.shutdown()
            store.close()
            File(context.filesDir, "sponge").deleteRecursively()
        }
    }

    private fun findTrial(plan: JSONObject, trialId: String): JSONObject {
        val blocks = plan.getJSONArray("blocks")
        for (blockIndex in 0 until blocks.length()) {
            val block = blocks.getJSONObject(blockIndex)
            val trials = block.getJSONArray("trials")
            for (trialIndex in 0 until trials.length()) {
                val trial = trials.getJSONObject(trialIndex)
                if (trial.getString("trialId") == trialId) {
                    return JSONObject(trial.toString()).apply {
                        put("orderingBlock", block.getInt("orderingBlock"))
                    }
                }
            }
        }
        error("trial $trialId not found in frozen plan")
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

    private fun nanosToMicros(value: Long): Long {
        require(value >= 0)
        return value / 1_000L
    }

    private fun readProcessStartClockTicks(): Long {
        val stat = File("/proc/self/stat").readText()
        // /proc/<pid>/stat field 2 is parenthesized and may contain spaces.
        // Field 22 (starttime) is token index 19 after the closing parenthesis.
        val close = stat.lastIndexOf(')')
        require(close > 0) { "malformed /proc/self/stat" }
        val fields = stat.substring(close + 1).trim().split(Regex("\\s+"))
        require(fields.size > 19) { "missing process starttime in /proc/self/stat" }
        return fields[19].toLong().also { require(it > 0) }
    }

    private fun readVmHwmBytes(): Long? {
        val line = runCatching {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("VmHWM:") }
            }
        }.getOrNull() ?: return null
        val kib = line.removePrefix("VmHWM:").trim()
            .substringBefore(' ')
            .toLongOrNull() ?: return null
        return kib * 1_024L
    }

    private class G2RecoveryJitter(
        private val seed: Long,
    ) : RecoveryJitterSource {
        val samples = mutableListOf<Long>()
        private var sampleIndex = 0L

        override fun uniformInclusive(windowMs: Long): Long {
            require(windowMs >= 0)
            val span = BigInteger.valueOf(windowMs).add(BigInteger.ONE)
            val unsigned64 = BigInteger.ONE.shiftLeft(64)
            val limit = unsigned64.subtract(unsigned64.mod(span))
            var drawIndex = 0L
            while (true) {
                val material =
                    "$RECOVERY_JITTER_DOMAIN:$seed:$sampleIndex:$drawIndex"
                        .toByteArray(StandardCharsets.UTF_8)
                val digest = MessageDigest.getInstance("SHA-256").digest(material)
                val value = BigInteger(1, digest.copyOfRange(0, 8))
                if (value < limit) {
                    val sample = value.mod(span).longValueExact()
                    samples += sample
                    sampleIndex += 1
                    return sample
                }
                drawIndex += 1
            }
        }
    }

    private companion object {
        const val ARG_ORIGIN = "spongetube.m2g2.originBaseUrl"
        const val ARG_TRIAL_ID = "spongetube.m2g2.trialId"
        const val ARG_RECOVERY_JITTER_SEED = "spongetube.m2g2.recoveryJitterSeed"
        const val PLAN_ASSET = "m2-g2-n0-plan.json"
        const val RESOURCE_PATH = "/fixtures/F1/segment-1-00001.m4s"
        const val RESOURCE_LENGTH = 81_811L
        const val RESOURCE_SHA256 =
            "08ac93538dcb3f5eece5996b0abab1e4e7677afbc7b21cc3292a63c776ef4943"
        const val RECOVERY_JITTER_PROTOCOL = "SHA256_COUNTER_REJECTION_V1"
        const val RECOVERY_JITTER_DOMAIN = "spongetube-g2-recovery-jitter-v1"
        val PROCESS_INSTANCE_ID: String = UUID.randomUUID().toString()
    }
}
