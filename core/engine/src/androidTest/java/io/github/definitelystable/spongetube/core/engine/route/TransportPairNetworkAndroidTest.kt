package io.github.definitelystable.spongetube.core.engine.route

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
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
import io.github.definitelystable.spongetube.core.engine.TransportPhaseObservation
import io.github.definitelystable.spongetube.core.engine.TransportPhaseObserver
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingCoordinator
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingRefresher
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryMaterial
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryAttemptGate
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryBudgetDimension
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
 * G2-C executes one frozen NETWORK trial. CI owns netem lifecycle and launches
 * every planned row in a fresh instrumentation process.
 *
 * This test never configures the fault: it only observes the same
 * RecoveryCoordinator -> FetchBroker -> ExtentStore path used by both exact
 * route transports.
 */
@RunWith(AndroidJUnit4::class)
class TransportPairNetworkAndroidTest {
    @Test(timeout = 120_000L)
    fun executeOneFrozenNetworkTrial() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT == 36)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val origin = arguments.getString(ARG_ORIGIN)?.trimEnd('/')
        val trialId = arguments.getString(ARG_TRIAL_ID)
        val scenarioRaw = arguments.getString(ARG_SCENARIO)
        val jitterSeed = arguments.getString(ARG_RECOVERY_JITTER_SEED)?.toLongOrNull()
        assumeTrue("G2-C requires an explicit origin", !origin.isNullOrBlank())
        assumeTrue("G2-C requires an explicit frozen trial id", !trialId.isNullOrBlank())
        assumeTrue("G2-C requires an explicit NETWORK scenario", !scenarioRaw.isNullOrBlank())
        assumeTrue("G2-C requires the frozen recovery-jitter seed", jitterSeed != null)
        val scenario = Scenario.parse(checkNotNull(scenarioRaw))

        val plan = instrumentation.context.assets.open(scenario.planAsset)
            .bufferedReader()
            .use { JSONObject(it.readText()) }
        val planned = findTrial(plan, checkNotNull(trialId))
        val backend = TransportEvaluationBackend.valueOf(planned.getString("backendId"))
        val context: Context = instrumentation.targetContext
        val uri = URL(checkNotNull(origin) + RESOURCE_PATH)

        File(context.filesDir, "sponge").deleteRecursively()
        val store = ExtentStore.open(context)
        val extentStoreInitiallyEmpty = store.committedExtents().isEmpty()
        assertTrue("G2-C COLD trial inherited persisted coverage", extentStoreInitiallyEmpty)

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
            val protocols = CopyOnWriteArrayList<String>()
            val transportPhases = CopyOnWriteArrayList<RecordedTransportPhase>()
            val phaseObserver = TransportPhaseObserver { observation ->
                transportPhases += RecordedTransportPhase(
                    observation = observation,
                    elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
                )
            }
            val target = HttpRangeTarget(uri, RESOURCE_LENGTH)
            val control = HttpRangeFetchExecutor(
                targetFor = { target },
                bindingTargetFor = { _, selected ->
                    assertTrue(selected === deliveryMaterial)
                    bindingHits.incrementAndGet()
                    target
                },
                connectTimeoutMs = FIRST_RESPONSE_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
                phaseObserver = phaseObserver,
            )
            val candidate = if (backend == TransportEvaluationBackend.PLATFORM_HTTP_ENGINE) {
                pool = PlatformHttpEnginePool.createIfAvailable(context)
                val engine = pool
                assertNotNull("API36 G2-C candidate must remain available after G1", engine)
                PlatformHttpRangeFetchExecutor(
                    pool = checkNotNull(engine),
                    targetFor = { target },
                    bindingTargetFor = { _, selected ->
                        assertTrue(selected === deliveryMaterial)
                        bindingHits.incrementAndGet()
                        target
                    },
                    firstResponseTimeoutMs = FIRST_RESPONSE_TIMEOUT_MS.toLong(),
                    readTimeoutMs = READ_TIMEOUT_MS.toLong(),
                    onProtocolObserved = { protocols += it },
                    phaseObserver = phaseObserver,
                )
            } else {
                null
            }

            val selected = TransportEvaluationSelector(
                control = control,
                candidate = candidate,
                candidateVersion = pool?.backendVersion,
            ).select(backend)
            assertEquals(TransportEvaluationEligibility.ELIGIBLE, selected.eligibility)
            val executor = checkNotNull(selected.executor)

            val fetchEvents = CopyOnWriteArrayList<FetchEvent>()
            val sessionId = "m2-g2-" + scenario.family.lowercase() + "-" + planned.getString("trialId")
            val broker = FetchBroker(
                extentStore = store,
                executor = executor,
                sessionId = sessionId,
                eventListener = FetchEventListener { fetchEvents += it },
            )
            val recoveryEvidence = RecoveryEvidenceRecorder(
                runId = plan.getString("runId"),
                sessionId = sessionId,
            )
            val jitter = G2RecoveryJitter(checkNotNull(jitterSeed))
            assertEquals(
                367L,
                G2RecoveryJitter(checkNotNull(jitterSeed)).uniformInclusive(500L),
            )
            val bindings = DeliveryBindingCoordinator(
                initialMaterial = deliveryMaterial,
                refresher = DeliveryBindingRefresher { _, _ ->
                    error("NETWORK fault must not refresh delivery binding")
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

            val cpuStartMs = Process.getElapsedCpuTime()
            val extentId = "m2g2" + scenario.family.lowercase() + ":" + planned.getString("trialId")
            try {
                val handle = coordinator.acquire(
                    request(extentId),
                    RecoveryConsumer(
                        id = RecoveryConsumerId("playback-" + planned.getString("trialId")),
                        kind = RecoveryConsumerKind.PLAYBACK,
                    ),
                )
                val outcome = withTimeout(75_000) { handle.await() }
                handle.close()
                assertEquals(
                    "canonical NETWORK trial must eventually publish the immutable extent",
                    RecoveryTerminalReason.SUCCESS,
                    outcome.terminalReason,
                )
            } finally {
                coordinator.shutdown()
                broker.shutdown()
            }
            val cpuTimeUs = (Process.getElapsedCpuTime() - cpuStartMs) * 1_000L

            val committed = store.committedExtents().single {
                it.extentId.value == extentId && it.length == RESOURCE_LENGTH
            }
            assertEquals(RESOURCE_SHA256, committed.sha256.hex)

            val orderedFetchEvents = fetchEvents.sortedBy(FetchEvent::eventSequence)
            val starts = orderedFetchEvents.filter { it.event == FetchEventKind.ATTEMPT_STARTED }
            val terminalAttempts = orderedFetchEvents.filter {
                it.event == FetchEventKind.ATTEMPT_COMPLETED ||
                    it.event == FetchEventKind.ATTEMPT_FAILED
            }
            assertTrue(starts.isNotEmpty())
            assertEquals(starts.size, terminalAttempts.size)
            val attemptProofs = starts.map { started ->
                val terminal = terminalAttempts.single { it.fetchId == started.fetchId }
                val correlations = orderedFetchEvents.filter {
                    it.fetchId == started.fetchId &&
                        it.event == FetchEventKind.ATTEMPT_CORRELATED
                }
                assertTrue(correlations.size <= 1)
                PhysicalAttemptProof(
                    fetchId = started.fetchId.value,
                    startNs = started.eventElapsedRealtimeNs,
                    endNs = terminal.eventElapsedRealtimeNs,
                    terminal = terminal.event.name,
                    transportCorrelationId = correlations.singleOrNull()?.transportCorrelationId,
                    networkBytes = terminal.networkBytes,
                )
            }
            assertTrue(attemptProofs.zipWithNext().all { (a, b) -> a.endNs <= b.startNs })

            val budget = recoveryEvidence.budgetEvents()
            val chainStarted = budget.single { it.kind == RecoveryBudgetEventKind.CHAIN_STARTED }
            val chainTerminated = budget.single { it.kind == RecoveryBudgetEventKind.CHAIN_TERMINATED }
            val ownerStarts = budget.filter { it.kind == RecoveryBudgetEventKind.OWNER_STARTED }
            val charges = budget.filter { it.kind == RecoveryBudgetEventKind.CHARGE }
            val permits = budget.filter { it.kind == RecoveryBudgetEventKind.ATTEMPT_PERMIT_GRANTED }
            val backoffs = budget.filter { it.kind == RecoveryBudgetEventKind.BACKOFF_SCHEDULED }
            assertEquals(starts.size, ownerStarts.size)
            assertEquals(starts.size, charges.size)
            assertEquals(starts.size, permits.size)
            assertTrue(permits.all { it.permit?.routeEpoch == initialEpoch })
            assertTrue(chainStarted.elapsedRealtimeNs <= starts.first().eventElapsedRealtimeNs)
            assertTrue(terminalAttempts.last().eventElapsedRealtimeNs <= chainTerminated.elapsedRealtimeNs)

            val firstProgress = orderedFetchEvents.first { it.event == FetchEventKind.ATTEMPT_PROGRESS }
            val firstByteUs = nanosToMicros(
                firstProgress.eventElapsedRealtimeNs - chainStarted.elapsedRealtimeNs,
            )
            val completionUs = nanosToMicros(
                chainTerminated.elapsedRealtimeNs - chainStarted.elapsedRealtimeNs,
            )
            assertTrue(firstByteUs >= 0L)
            assertTrue(completionUs >= firstByteUs)

            val phaseSnapshot = transportPhases.sortedBy(RecordedTransportPhase::elapsedRealtimeNs)
            assertTrue(phaseSnapshot.isNotEmpty())
            assertTrue(phaseSnapshot.all { phase ->
                phase.observation.fetchKey == starts.first().fetchKey &&
                    phase.observation.attempt == 1 &&
                    attemptProofs.count {
                        phase.elapsedRealtimeNs in it.startNs..it.endNs
                    } == 1
            })
            val successfulAttempt = attemptProofs.single { it.terminal == FetchEventKind.ATTEMPT_COMPLETED.name }
            val successfulKinds = phaseSnapshot.filter {
                it.elapsedRealtimeNs in successfulAttempt.startNs..successfulAttempt.endNs
            }.map { it.observation.kind }
            assertEquals(
                listOf(
                    io.github.definitelystable.spongetube.core.engine.TransportPhaseKind.RESPONSE_HEADERS,
                    io.github.definitelystable.spongetube.core.engine.TransportPhaseKind.FIRST_BODY_BYTES,
                    io.github.definitelystable.spongetube.core.engine.TransportPhaseKind.RESPONSE_BODY_COMPLETE,
                ),
                successfulKinds,
            )

            val finalEpoch = (monitor.observations.value.state as? DefaultRouteState.Available)
                ?.routeEpoch
            assertEquals(initialEpoch, finalEpoch)
            assertEquals(ownerStarts.size, bindingHits.get())

            val correlatedOriginIds = attemptProofs.mapNotNull {
                it.transportCorrelationId?.toLongOrNull()
            }
            assertEquals(correlatedOriginIds.size, correlatedOriginIds.toSet().size)
            val receivedBytes = attemptProofs.sumOf(PhysicalAttemptProof::networkBytes)
            val protocol = protocols.distinct().singleOrNull() ?: "UNKNOWN"
            val maxRssBytes = readVmHwmBytes()

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
                    put("permitRouteEpoch", initialEpoch)
                })
                put("result", "SUCCESS")
                put("requestCorrectness", JSONObject().apply {
                    put("range", "PASS")
                    put("contentRange", "PASS")
                    put("responseBounds", "PASS")
                    put("publishedBytes", "PASS")
                })
                put("recovery", JSONObject().apply {
                    put("recoveryChainCount", 1)
                    put("ownerCount", ownerStarts.size)
                    // Device raw counts only explicit owners that received an
                    // origin correlation. Host reconciliation owns final origin
                    // GET count and transport-internal replay derivation.
                    put("originRequestCount", correlatedOriginIds.size)
                    put("internalRetryVisibility", "OPAQUE")
                    put("internalRetryCount", JSONObject.NULL)
                })
                put("metrics", JSONObject().apply {
                    put("firstByteUs", firstByteUs)
                    put("completionUs", completionUs)
                    put("cancellationLatencyUs", JSONObject.NULL)
                    put("cpuTimeUs", cpuTimeUs)
                    put("maxRssBytes", maxRssBytes ?: JSONObject.NULL)
                    put("bytesRequested", RESOURCE_LENGTH)
                    put("bytesReceived", receivedBytes)
                    put("bytesPublished", committed.length)
                })
                put("negotiatedProtocol", protocol)
                put("performanceSampleEligible", false)
                put("limitations", JSONArray().apply {
                    put("RAW_DEVICE_ROW_REQUIRES_HOST_RETRY_FINALIZATION")
                    put("API36_EMULATOR_DIRECTIONAL_ONLY")
                    put("MAX_RSS_IS_FRESH_PROCESS_HIGH_WATER")
                    put("FIRST_BYTE_IS_FIRST_ACCEPTED_FETCHBROKER_CHUNK_IN_RECOVERY_CHAIN")
                    put("COMPLETION_IS_RECOVERY_CHAIN_TERMINAL_AFTER_PUBLICATION")
                    put("TRANSPORT_PHASES_ARE_PHYSICAL_ATTEMPT_LEVEL")
                })
            }

            val raw = JSONObject().apply {
                put("schemaVersion", 1)
                put("phase", "M2-G2-C-NETWORK")
                put("scenarioFamily", scenario.family)
                put("scenarioVariant", scenario.variant)
                put("runId", plan.getString("runId"))
                put("pairId", plan.getString("pairId"))
                put("trial", g0Row)
                put("proof", JSONObject().apply {
                    put("correlatedOriginRequestIds", JSONArray(correlatedOriginIds))
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
                    put("bindingTargetResolutionCount", bindingHits.get())
                    put("chainStartedElapsedRealtimeNs", chainStarted.elapsedRealtimeNs)
                    put("chainTerminatedElapsedRealtimeNs", chainTerminated.elapsedRealtimeNs)
                    put("physicalAttempts", JSONArray().apply {
                        attemptProofs.forEach { attempt ->
                            put(JSONObject().apply {
                                put("fetchId", attempt.fetchId)
                                put("startElapsedRealtimeNs", attempt.startNs)
                                put("endElapsedRealtimeNs", attempt.endNs)
                                put("terminal", attempt.terminal)
                                put(
                                    "transportCorrelationId",
                                    attempt.transportCorrelationId ?: JSONObject.NULL,
                                )
                                put("networkBytes", attempt.networkBytes)
                            })
                        }
                    })
                    put("transportPhases", JSONArray().apply {
                        phaseSnapshot.forEach { phase ->
                            put(JSONObject().apply {
                                put("fetchKey", phase.observation.fetchKey.value)
                                put("attempt", phase.observation.attempt)
                                put("kind", phase.observation.kind.name)
                                put("elapsedRealtimeNs", phase.elapsedRealtimeNs)
                            })
                        }
                    })
                    put("recoveryFailureCount", recoveryEvidence.failures().size)
                    put("recoveryBackoffs", JSONArray().apply {
                        backoffs.forEach { event ->
                            val backoff = checkNotNull(event.backoff)
                            put(JSONObject().apply {
                                put("retryOrdinal", backoff.retryOrdinal)
                                put("windowMs", backoff.windowMs)
                                put("delayMs", backoff.delayMs)
                            })
                        }
                    })
                    put("recoveryJitterProtocol", RECOVERY_JITTER_PROTOCOL)
                    put("recoveryJitterSeed", jitterSeed)
                    put("recoveryJitterSampleCount", jitter.samples.size)
                    put("recoveryJitterSamples", JSONArray(jitter.samples))
                    put("firstResponseTimeoutMs", FIRST_RESPONSE_TIMEOUT_MS)
                    put("readTimeoutMs", READ_TIMEOUT_MS)
                    put("runtimeRecoveryPolicyId", RecoveryPolicy.DEFAULT.policyId)
                    put(
                        "runtimeRemoteAttemptLimit",
                        checkNotNull(
                            RecoveryPolicy.DEFAULT.budget.limit(
                                RecoveryBudgetDimension.REMOTE_ATTEMPT,
                            ),
                        ),
                    )
                    put(
                        "runtimeDeliveryBindingRefreshLimit",
                        checkNotNull(
                            RecoveryPolicy.DEFAULT.budget.limit(
                                RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH,
                            ),
                        ),
                    )
                    put("runtimeBackoffBaseMs", RecoveryPolicy.DEFAULT.backoff.baseMs)
                    put("runtimeBackoffCapMs", RecoveryPolicy.DEFAULT.backoff.capMs)
                    put("negotiatedProtocols", JSONArray(protocols))
                })
            }
            PlatformTestStorageRegistry.getInstance().openOutputFile(
                "m2-g2-network/" + scenario.family.lowercase() + "/" +
                    planned.getString("trialId") + ".json",
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
        fetchKey = FetchKey("fixture:F1/video-main/f1-video-0/" + id),
        extentSpec = ExtentSpec(
            mediaAssetId = MediaAssetId("fixture:F1"),
            extentId = ExtentId(id),
            trackId = "video-main",
            representationId = "f1-video-0",
            mediaStartUs = 0,
            mediaEndUs = 10_000_000,
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
        val close = stat.lastIndexOf(')')
        require(close > 0)
        val fields = stat.substring(close + 1).trim().split(Regex("\\s+"))
        require(fields.size > 19)
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

    private data class RecordedTransportPhase(
        val observation: TransportPhaseObservation,
        val elapsedRealtimeNs: Long,
    )

    private data class PhysicalAttemptProof(
        val fetchId: String,
        val startNs: Long,
        val endNs: Long,
        val terminal: String,
        val transportCorrelationId: String?,
        val networkBytes: Long,
    )

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

    private data class Scenario(
        val family: String,
        val variant: String,
        val planAsset: String,
    ) {
        companion object {
            fun parse(raw: String?): Scenario = when (raw) {
                "N2" -> Scenario("N2", "HIGH_RTT_JITTER", "m2-g2-n2-plan.json")
                "N3" -> Scenario("N3", "BURST_PACKET_LOSS", "m2-g2-n3-plan.json")
                "N5" -> Scenario("N5", "BURST_LOSS", "m2-g2-n5-plan.json")
                else -> error("unsupported G2-C NETWORK scenario: $raw")
            }
        }
    }

    private companion object {
        const val ARG_ORIGIN = "spongetube.m2g2.originBaseUrl"
        const val ARG_TRIAL_ID = "spongetube.m2g2.trialId"
        const val ARG_SCENARIO = "spongetube.m2g2.networkScenario"
        const val ARG_RECOVERY_JITTER_SEED = "spongetube.m2g2.recoveryJitterSeed"
        const val RESOURCE_PATH = "/fixtures/F1/segment-0-00001.m4s"
        const val RESOURCE_LENGTH = 711_501L
        const val RESOURCE_SHA256 =
            "f3e8a844487d57a05c69975389566bde3bcb38d9afa1d53538be18c959d77fa3"
        const val RECOVERY_JITTER_PROTOCOL = "SHA256_COUNTER_REJECTION_V1"
        const val RECOVERY_JITTER_DOMAIN = "spongetube-g2-recovery-jitter-v1"
        const val FIRST_RESPONSE_TIMEOUT_MS = 12_000
        const val READ_TIMEOUT_MS = 12_000
        val PROCESS_INSTANCE_ID: String = UUID.randomUUID().toString()
    }
}
