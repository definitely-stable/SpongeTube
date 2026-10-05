package io.github.definitelystable.spongetube.core.engine.route

import android.app.Instrumentation
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
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
import io.github.definitelystable.spongetube.core.engine.PlatformHttpEnginePool
import io.github.definitelystable.spongetube.core.engine.PlatformHttpRangeFetchExecutor
import io.github.definitelystable.spongetube.core.engine.RouteBoundFetchAttemptExecutor
import io.github.definitelystable.spongetube.core.engine.TransportEvaluationBackend
import io.github.definitelystable.spongetube.core.engine.TransportEvaluationEligibility
import io.github.definitelystable.spongetube.core.engine.TransportEvaluationSelector
import io.github.definitelystable.spongetube.core.engine.TransportPhaseKind
import io.github.definitelystable.spongetube.core.engine.TransportPhaseObservation
import io.github.definitelystable.spongetube.core.engine.TransportPhaseObserver
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingCoordinator
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingRefresher
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingSnapshot
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryMaterial
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
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryChainId
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M2-G2-E paired exact-route replacement producer.
 *
 * Reuses the proven M2-F2 mechanism: owner #1 is admitted against the current
 * exact Android Network and blocked immediately before physical I/O. The real
 * default route is then removed, owner #1 is released on the now-dead binding,
 * and RecoveryCoordinator reaches the route-aware gate. No owner/charge may be
 * created while DefaultRouteState is Unavailable. After the default route is
 * restored, owner #2 must use the replacement routeEpoch and publish the same
 * frozen G2 work. The selected transport backend is the only experimental
 * variable.
 */
@RunWith(AndroidJUnit4::class)
class TransportPairRouteReplacementAndroidTest {
    @Test(timeout = TEST_TIMEOUT_MS)
    fun executeOneFrozenRouteReplacementTrial() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT == 36)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val origin = arguments.getString(ARG_ORIGIN)?.trimEnd('/')
        val trialId = arguments.getString(ARG_TRIAL_ID)
        val jitterSeed = arguments.getString(ARG_RECOVERY_JITTER_SEED)?.toLongOrNull()
        assumeTrue("G2-E requires explicit origin", !origin.isNullOrBlank())
        assumeTrue("G2-E requires frozen trial id", !trialId.isNullOrBlank())
        assumeTrue("G2-E requires frozen recovery jitter seed", jitterSeed != null)

        val plan = instrumentation.context.assets.open(PLAN_ASSET)
            .bufferedReader()
            .use { JSONObject(it.readText()) }
        val planned = findTrial(plan, checkNotNull(trialId))
        val backend = TransportEvaluationBackend.valueOf(planned.getString("backendId"))
        val context: Context = instrumentation.targetContext
        val uri = URL(checkNotNull(origin) + RESOURCE_PATH)

        File(context.filesDir, "sponge").deleteRecursively()
        val store = ExtentStore.open(context)
        assertTrue("G2-E COLD trial inherited persisted coverage", store.committedExtents().isEmpty())

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sessionId = "m2-g2-route-" + planned.getString("trialId")
        val routeRecorder = RouteEvidenceRecorder(
            runId = plan.getString("runId"),
            sessionId = sessionId,
            androidApi = Build.VERSION.SDK_INT,
        )
        val recoveryEvidence = RecoveryEvidenceRecorder(
            runId = plan.getString("runId"),
            sessionId = sessionId,
        )
        val monitor = AndroidDefaultRouteMonitor.open(
            instrumentation.context,
            scope,
            routeRecorder,
        )
        var pool: PlatformHttpEnginePool? = null
        var broker: FetchBroker? = null
        var coordinator: RecoveryCoordinator? = null
        var monitorStopped = false
        val firstAdmitted = CountDownLatch(1)
        val releaseFirstConnect = CountDownLatch(1)
        val secondGateEntered = CompletableDeferred<Unit>()
        val validatedReplacementPermit = CompletableDeferred<RecoveryAttemptPermit>()
        val gateCalls = AtomicInteger()
        val fetchEvents = CopyOnWriteArrayList<FetchEvent>()
        val protocols = CopyOnWriteArrayList<String>()
        val transportPhases = CopyOnWriteArrayList<RecordedTransportPhase>()
        val bindingHits = AtomicInteger()
        var initialEpoch = 0L
        var restoredEpoch = 0L
        var chargesBeforeRestore = 0
        var attemptsBeforeRestore = 0
        val replacementValidationRendezvousObserved = AtomicBoolean(false)

        try {
            val initial = awaitDirectObservation(monitor, backend)
            initialEpoch = (initial.state as DefaultRouteState.Available).routeEpoch
            assertTrue(initial.executionBinding != null)

            val deliveryMaterial = object : DeliveryMaterial {}
            val phaseObserver = TransportPhaseObserver { observation ->
                transportPhases += RecordedTransportPhase(
                    observation,
                    SystemClock.elapsedRealtimeNanos(),
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
                connectTimeoutMs = HTTP_TIMEOUT_MS,
                readTimeoutMs = HTTP_TIMEOUT_MS,
                phaseObserver = phaseObserver,
            )
            val candidate = if (backend == TransportEvaluationBackend.PLATFORM_HTTP_ENGINE) {
                pool = PlatformHttpEnginePool.createIfAvailable(context)
                assertNotNull("API36 G2-E HttpEngine candidate unavailable", pool)
                PlatformHttpRangeFetchExecutor(
                    pool = checkNotNull(pool),
                    targetFor = { target },
                    bindingTargetFor = { _, selected ->
                        assertTrue(selected === deliveryMaterial)
                        bindingHits.incrementAndGet()
                        target
                    },
                    firstResponseTimeoutMs = HTTP_TIMEOUT_MS.toLong(),
                    readTimeoutMs = HTTP_TIMEOUT_MS.toLong(),
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
            val exactDelegate = checkNotNull(selected.executor) as? RouteBoundFetchAttemptExecutor
            assertNotNull("G2-E backend lost exact-route capability", exactDelegate)
            val barrierExecutor = FirstAdmissionBarrierExecutor(
                delegate = checkNotNull(exactDelegate),
                firstAdmitted = firstAdmitted,
                releaseFirstConnect = releaseFirstConnect,
            )

            broker = FetchBroker(
                extentStore = store,
                executor = barrierExecutor,
                sessionId = sessionId,
                eventListener = FetchEventListener { fetchEvents += it },
            )
            val bindings = DeliveryBindingCoordinator(
                initialMaterial = deliveryMaterial,
                refresher = DeliveryBindingRefresher { _, _ ->
                    error("G2-E route replacement must not refresh delivery binding")
                },
            )
            val exactGate = RouteAwareRecoveryAttemptGate(
                observations = monitor.observations,
                guard = SessionRouteGuard(),
                evidence = routeRecorder,
            )
            val gate = RecoveryAttemptGate { chainId ->
                val ordinal = gateCalls.incrementAndGet()
                if (ordinal == 2) secondGateEntered.complete(Unit)
                if (ordinal != 2) {
                    exactGate.awaitPermit(chainId)
                } else {
                    try {
                        val permit = awaitValidatedReplacementPermit(
                            chainId = chainId,
                            exactGate = exactGate,
                            monitor = monitor,
                        )
                        replacementValidationRendezvousObserved.set(true)
                        validatedReplacementPermit.complete(permit)
                        permit
                    } catch (throwable: Throwable) {
                        validatedReplacementPermit.completeExceptionally(throwable)
                        throw throwable
                    }
                }
            }
            val jitter = G2RecoveryJitter(checkNotNull(jitterSeed))
            assertEquals(367L, G2RecoveryJitter(checkNotNull(jitterSeed)).uniformInclusive(500L))
            coordinator = RecoveryCoordinator(
                broker = checkNotNull(broker),
                sessionId = sessionId,
                policy = RecoveryPolicy.DEFAULT,
                attemptGate = gate,
                bindings = bindings,
                jitter = jitter,
                evidence = recoveryEvidence,
            )

            val cpuStartMs = Process.getElapsedCpuTime()
            val handle = coordinator.acquire(
                request(planned.getString("trialId")),
                RecoveryConsumer(
                    RecoveryConsumerId("playback-" + planned.getString("trialId")),
                    RecoveryConsumerKind.PLAYBACK,
                ),
            )
            val outcomeDeferred = async { handle.await() }

            assertTrue(
                "G2-E owner #1 never reached the pre-I/O barrier",
                withContext(Dispatchers.IO) {
                    firstAdmitted.await(BARRIER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                },
            )
            assertEquals(1, recoveryEvidence.chargeEvents())
            assertEquals(1, fetchEvents.count { it.event == FetchEventKind.ATTEMPT_STARTED })

            setConnectivityEnabled(instrumentation, enabled = false)
            awaitStableUnavailable(monitor, routeRecorder)
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
            chargesBeforeRestore = recoveryEvidence.chargeEvents()
            attemptsBeforeRestore = fetchEvents.count { it.event == FetchEventKind.ATTEMPT_STARTED }
            assertEquals(1, chargesBeforeRestore)
            assertEquals(1, attemptsBeforeRestore)
            assertEquals(2, gateCalls.get())

            setConnectivityEnabled(instrumentation, enabled = true)
            val replacementPermit = withTimeout(ROUTE_TIMEOUT_MS) {
                validatedReplacementPermit.await()
            }
            restoredEpoch = checkNotNull(replacementPermit.routeEpoch)
            val restoredBinding = checkNotNull(replacementPermit.routeBinding)
            assertTrue(restoredEpoch > initialEpoch)
            // routeEpoch, not Android Network/binding object identity, is the
            // contract boundary. Android may reuse the same Network object
            // after an UNAVAILABLE gap; the reducer must still start a new epoch.
            // Conversely, a genuinely different platform route must never
            // retain the old route's executable binding.
            val initialRouteRef = routeRefForEpoch(routeRecorder, initialEpoch)
            val restoredRouteRef = routeRefForEpoch(routeRecorder, restoredEpoch)
            if (initialRouteRef != restoredRouteRef) {
                assertTrue(
                    "G2-E replacement route reused the old route execution binding",
                    initial.executionBinding !== restoredBinding,
                )
            }

            val outcome = withTimeout(OUTCOME_TIMEOUT_MS) { outcomeDeferred.await() }
            handle.close()
            assertEquals(RecoveryTerminalReason.SUCCESS, outcome.terminalReason)
            assertTrue(
                "G2-E replacement validation rendezvous was not observed",
                replacementValidationRendezvousObserved.get(),
            )

            coordinator.shutdown()
            broker.shutdown()
            val cpuTimeUs = (Process.getElapsedCpuTime() - cpuStartMs) * 1_000L

            val extentId = extentId(planned.getString("trialId"))
            val committed = store.committedExtents().single {
                it.extentId.value == extentId && it.length == RESOURCE_LENGTH
            }
            assertEquals(RESOURCE_SHA256, committed.sha256.hex)

            val orderedFetch = fetchEvents.sortedBy(FetchEvent::eventSequence)
            val starts = orderedFetch.filter { it.event == FetchEventKind.ATTEMPT_STARTED }
            val terminals = orderedFetch.filter {
                it.event == FetchEventKind.ATTEMPT_FAILED ||
                    it.event == FetchEventKind.ATTEMPT_COMPLETED
            }
            assertEquals(2, starts.size)
            assertEquals(2, terminals.size)
            val attemptProofs = starts.map { start ->
                val terminal = terminals.single { it.fetchId == start.fetchId }
                val correlations = orderedFetch.filter {
                    it.fetchId == start.fetchId &&
                        it.event == FetchEventKind.ATTEMPT_CORRELATED
                }
                assertTrue(correlations.size <= 1)
                PhysicalAttemptProof(
                    fetchId = start.fetchId.value,
                    startNs = start.eventElapsedRealtimeNs,
                    endNs = terminal.eventElapsedRealtimeNs,
                    terminal = terminal.event.name,
                    transportCorrelationId = correlations.singleOrNull()?.transportCorrelationId,
                    networkBytes = terminal.networkBytes,
                )
            }
            assertEquals(FetchEventKind.ATTEMPT_FAILED.name, attemptProofs[0].terminal)
            assertEquals(FetchEventKind.ATTEMPT_COMPLETED.name, attemptProofs[1].terminal)
            assertTrue(attemptProofs[0].endNs <= attemptProofs[1].startNs)

            val budget = recoveryEvidence.budgetEvents()
            val chainStarted = budget.single { it.kind == RecoveryBudgetEventKind.CHAIN_STARTED }
            val chainTerminated = budget.single { it.kind == RecoveryBudgetEventKind.CHAIN_TERMINATED }
            val ownerStarts = budget.filter { it.kind == RecoveryBudgetEventKind.OWNER_STARTED }
            val permits = budget.filter { it.kind == RecoveryBudgetEventKind.ATTEMPT_PERMIT_GRANTED }
            val backoffs = budget.filter { it.kind == RecoveryBudgetEventKind.BACKOFF_SCHEDULED }
            assertEquals(2, ownerStarts.size)
            assertEquals(listOf(initialEpoch, restoredEpoch), permits.map { checkNotNull(it.permit).routeEpoch })
            assertEquals(2, bindingHits.get())
            assertEquals(1, backoffs.size)
            assertEquals(367L, checkNotNull(backoffs.single().backoff).delayMs)

            val failure = recoveryEvidence.failures().single()
            assertEquals(FailureClassification.TRANSIENT_TRANSPORT, failure.classification)
            assertEquals(RecoveryActionKind.SCHEDULE_BACKOFF, failure.action.kind)
            assertEquals(initialEpoch, failure.routeEpoch)

            val firstProgress = orderedFetch.first { it.event == FetchEventKind.ATTEMPT_PROGRESS }
            val firstByteUs = nanosToMicros(
                firstProgress.eventElapsedRealtimeNs - chainStarted.elapsedRealtimeNs,
            )
            val completionUs = nanosToMicros(
                chainTerminated.elapsedRealtimeNs - chainStarted.elapsedRealtimeNs,
            )
            val phaseSnapshot = transportPhases.sortedBy(RecordedTransportPhase::elapsedRealtimeNs)
            val successful = attemptProofs[1]
            val successfulKinds = phaseSnapshot.filter {
                it.elapsedRealtimeNs in successful.startNs..successful.endNs
            }.map { it.observation.kind }
            assertEquals(
                listOf(
                    TransportPhaseKind.RESPONSE_HEADERS,
                    TransportPhaseKind.FIRST_BODY_BYTES,
                    TransportPhaseKind.RESPONSE_BODY_COMPLETE,
                ),
                successfulKinds,
            )

            monitor.shutdown()
            monitorStopped = true
            val correlatedOriginIds = attemptProofs.mapNotNull {
                it.transportCorrelationId?.toLongOrNull()
            }
            assertEquals(correlatedOriginIds.size, correlatedOriginIds.toSet().size)
            assertEquals(1, correlatedOriginIds.size)

            val protocol = protocols.distinct().singleOrNull() ?: "UNKNOWN"
            val row = JSONObject().apply {
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
                    put("ownerCount", 2)
                    put("originRequestCount", correlatedOriginIds.size)
                    put("internalRetryVisibility", "OPAQUE")
                    put("internalRetryCount", JSONObject.NULL)
                })
                put("metrics", JSONObject().apply {
                    put("firstByteUs", firstByteUs)
                    put("completionUs", completionUs)
                    put("cancellationLatencyUs", JSONObject.NULL)
                    put("cpuTimeUs", cpuTimeUs)
                    put("maxRssBytes", readVmHwmBytes() ?: JSONObject.NULL)
                    put("bytesRequested", RESOURCE_LENGTH)
                    put("bytesReceived", attemptProofs.sumOf(PhysicalAttemptProof::networkBytes))
                    put("bytesPublished", committed.length)
                })
                put("negotiatedProtocol", protocol)
                put("performanceSampleEligible", false)
                put("limitations", JSONArray().apply {
                    put("RAW_DEVICE_ROW_REQUIRES_HOST_RETRY_FINALIZATION")
                    put("API36_EMULATOR_DIRECTIONAL_ONLY")
                    put("ROUTE_REPLACEMENT_RESILIENCE_ONLY")
                    put("LAB_ONLY_REPLACEMENT_VALIDATION_RENDEZVOUS")
                    put("MAX_RSS_IS_FRESH_PROCESS_HIGH_WATER")
                    put("FIRST_BYTE_IS_FIRST_ACCEPTED_FETCHBROKER_CHUNK_IN_RECOVERY_CHAIN")
                    put("COMPLETION_IS_RECOVERY_CHAIN_TERMINAL_AFTER_PUBLICATION")
                    put("TRANSPORT_PHASES_ARE_PHYSICAL_ATTEMPT_LEVEL")
                })
            }

            val raw = JSONObject().apply {
                put("schemaVersion", 1)
                put("phase", "M2-G2-E-ROUTE_REPLACEMENT")
                put("scenarioFamily", "N6")
                put("scenarioVariant", "DEFAULT_ROUTE_LOSS_RESTORE")
                put("runId", plan.getString("runId"))
                put("pairId", plan.getString("pairId"))
                put("trial", row)
                put("proof", JSONObject().apply {
                    put("correlatedOriginRequestIds", JSONArray(correlatedOriginIds))
                    put("committedSha256", committed.sha256.hex)
                    put("committedBytes", committed.length)
                    put("processInstanceId", PROCESS_INSTANCE_ID)
                    put("processPid", Process.myPid())
                    put("processStartClockTicks", readProcessStartClockTicks())
                    put("androidApi", Build.VERSION.SDK_INT)
                    put("primaryAbi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
                    put("extentStoreInitiallyEmpty", true)
                    put("transportSessionFresh", true)
                    put("routeEpochBefore", initialEpoch)
                    put("routeEpochAfter", restoredEpoch)
                    put("routeUnavailableObserved", true)
                    put("chargesBeforeRestore", chargesBeforeRestore)
                    put("attemptsBeforeRestore", attemptsBeforeRestore)
                    put("gateCalls", gateCalls.get())
                    put(
                        "replacementValidationRendezvousObserved",
                        replacementValidationRendezvousObserved.get(),
                    )
                    put("routeEvidence", JSONObject(routeRecorder.toArtifactMap()))
                    put("bindingRevision", "binding-1")
                    put("bindingTargetResolutionCount", bindingHits.get())
                    put("permitRouteEpochs", JSONArray(permits.map { checkNotNull(it.permit).routeEpoch }))
                    put("chainStartedElapsedRealtimeNs", chainStarted.elapsedRealtimeNs)
                    put("firstBrokerProgressElapsedRealtimeNs", firstProgress.eventElapsedRealtimeNs)
                    put("chainTerminatedElapsedRealtimeNs", chainTerminated.elapsedRealtimeNs)
                    put("physicalAttempts", JSONArray().apply {
                        attemptProofs.forEach { attempt ->
                            put(JSONObject().apply {
                                put("fetchId", attempt.fetchId)
                                put("startElapsedRealtimeNs", attempt.startNs)
                                put("endElapsedRealtimeNs", attempt.endNs)
                                put("terminal", attempt.terminal)
                                put("transportCorrelationId", attempt.transportCorrelationId ?: JSONObject.NULL)
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
                    put("recoveryFailures", JSONArray().apply {
                        recoveryEvidence.failures().forEach { put(JSONObject(it.toArtifactMap())) }
                    })
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
                    put("firstResponseTimeoutMs", HTTP_TIMEOUT_MS)
                    put("readTimeoutMs", HTTP_TIMEOUT_MS)
                    put("runtimeRecoveryPolicyId", RecoveryPolicy.DEFAULT.policyId)
                    put("runtimeRemoteAttemptLimit", checkNotNull(
                        RecoveryPolicy.DEFAULT.budget.limit(RecoveryBudgetDimension.REMOTE_ATTEMPT),
                    ))
                    put("runtimeDeliveryBindingRefreshLimit", checkNotNull(
                        RecoveryPolicy.DEFAULT.budget.limit(RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH),
                    ))
                    put("runtimeBackoffBaseMs", RecoveryPolicy.DEFAULT.backoff.baseMs)
                    put("runtimeBackoffCapMs", RecoveryPolicy.DEFAULT.backoff.capMs)
                    put("negotiatedProtocols", JSONArray(protocols))
                })
            }
            PlatformTestStorageRegistry.getInstance().openOutputFile(
                "m2-g2-route/n6-route/" + planned.getString("trialId") + ".json",
            ).bufferedWriter().use {
                it.write(raw.toString())
                it.write("\n")
            }
        } finally {
            releaseFirstConnect.countDown()
            runCatching { setConnectivityEnabled(instrumentation, enabled = true) }
            runCatching { coordinator?.shutdown() }
            runCatching { broker?.shutdown() }
            if (!monitorStopped) runCatching { monitor.shutdown() }
            pool?.shutdown()
            store.close()
            scope.cancel()
            File(context.filesDir, "sponge").deleteRecursively()
        }
    }

    private suspend fun awaitDirectObservation(
        monitor: DefaultRouteMonitor,
        backend: TransportEvaluationBackend,
        minimumEpochExclusive: Long = 0L,
    ): RouteObservation = withTimeout(ROUTE_TIMEOUT_MS) {
        monitor.observations.first { observation ->
            val state = observation.state as? DefaultRouteState.Available
            state != null &&
                state.capabilitiesReceived &&
                state.routeEpoch > minimumEpochExclusive &&
                state.capabilities.vpn == ObservedBoolean.FALSE &&
                state.capabilities.internet != ObservedBoolean.FALSE &&
                state.capabilities.validated == ObservedBoolean.TRUE &&
                state.capabilities.metered == ObservedBoolean.FALSE &&
                observation.executionBinding != null &&
                (
                    backend != TransportEvaluationBackend.PLATFORM_HTTP_ENGINE ||
                        observation.executionBinding is PlatformHttpEngineRouteBinding
                )
        }
    }

    /**
     * Lab-only stabilization barrier for owner #2.
     *
     * Android may briefly publish an ALLOW-able replacement route and then
     * replace it again before that epoch reaches VALIDATED. A permit for that
     * transient epoch must never strand the recovery chain: no owner has been
     * opened and no budget has been charged yet, so reacquiring the exact gate
     * is still part of the same permit wait, not a retry.
     */
    private suspend fun awaitValidatedReplacementPermit(
        chainId: RecoveryChainId,
        exactGate: RouteAwareRecoveryAttemptGate,
        monitor: DefaultRouteMonitor,
    ): RecoveryAttemptPermit {
        var permit = exactGate.awaitPermit(chainId)
        while (true) {
            val routeEpoch = checkNotNull(permit.routeEpoch)
            val routeBinding = checkNotNull(permit.routeBinding)
            val observation = monitor.observations.value
            val state = observation.state as? DefaultRouteState.Available
            val samePermitRoute =
                state?.routeEpoch == routeEpoch &&
                    observation.executionBinding === routeBinding

            if (
                samePermitRoute &&
                state != null &&
                state.capabilitiesReceived &&
                state.capabilities.validated == ObservedBoolean.TRUE &&
                state.capabilities.vpn == ObservedBoolean.FALSE &&
                state.capabilities.internet != ObservedBoolean.FALSE &&
                state.capabilities.metered == ObservedBoolean.FALSE
            ) {
                return permit
            }

            if (!samePermitRoute) {
                permit = exactGate.awaitPermit(chainId)
                continue
            }

            val sequence = observation.sequence
            monitor.observations.first { it.sequence > sequence }
        }
    }

    private fun routeRefForEpoch(
        evidence: RouteEvidenceRecorder,
        routeEpoch: Long,
    ): PlatformRouteRef =
        checkNotNull(
            evidence.events().firstOrNull { event ->
                event.disposition == RouteEventDisposition.APPLIED &&
                    event.routeEpochAfter == routeEpoch &&
                    (
                        event.signal == RouteSignalKind.AVAILABLE ||
                            event.signal == RouteSignalKind.BOOTSTRAP_SNAPSHOT
                    )
            }?.platformRouteRef,
        ) { "G2-E route epoch $routeEpoch lacks an establishment route ref" }

    private suspend fun awaitStableUnavailable(
        monitor: DefaultRouteMonitor,
        evidence: RouteEvidenceRecorder,
    ) = withTimeout(ROUTE_TIMEOUT_MS) {
        while (true) {
            monitor.observations.first { it.state == DefaultRouteState.Unavailable }
            val eventWatermark = evidence.events().lastOrNull()?.sequence ?: 0L
            delay(ROUTE_UNAVAILABLE_STABILITY_MS)
            val reboundObserved = evidence.events().any { event ->
                event.sequence > eventWatermark &&
                    event.stateAfter is DefaultRouteState.Available
            }
            if (!reboundObserved && monitor.observations.value.state == DefaultRouteState.Unavailable) {
                return@withTimeout
            }
        }
    }

    private suspend fun setConnectivityEnabled(
        instrumentation: Instrumentation,
        enabled: Boolean,
    ) {
        val commands = if (enabled) {
            listOf(
                // G2-E measures one deterministic Wi-Fi default-route
                // replacement. Mobile data stays disabled for the full
                // measured interval so CELLULAR cannot become a competing
                // default route and manufacture extra recovery owners.
                "svc data disable",
                "cmd connectivity airplane-mode disable",
                "svc wifi enable",
            )
        } else {
            listOf(
                "svc data disable",
                "svc wifi disable",
                "cmd connectivity airplane-mode enable",
            )
        }
        for (command in commands) {
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command),
            ).bufferedReader().use { it.readText() }
        }
    }

    private fun request(trialId: String): FetchRequest = FetchRequest(
        fetchKey = FetchKey("fixture:F1/video-main/f1-video-0/segment-0-00001:$trialId"),
        extentSpec = ExtentSpec(
            mediaAssetId = MediaAssetId("fixture:F1"),
            extentId = ExtentId(extentId(trialId)),
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

    private fun extentId(trialId: String) = "m2g2route:$trialId"

    private fun RecoveryEvidenceRecorder.chargeEvents(): Int =
        budgetEvents().count { it.kind == RecoveryBudgetEventKind.CHARGE }

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
        error("trial $trialId not found")
    }

    private fun nanosToMicros(value: Long): Long {
        require(value >= 0)
        return value / 1_000L
    }

    private fun readProcessStartClockTicks(): Long {
        val stat = File("/proc/self/stat").readText()
        val close = stat.lastIndexOf(')')
        require(close > 0)
        val fields = stat.substring(close + 1).trim().split(Regex("\\s+"))
        return fields[19].toLong().also { require(it > 0) }
    }

    private fun readVmHwmBytes(): Long? {
        val line = runCatching {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("VmHWM:") }
            }
        }.getOrNull() ?: return null
        val kib = line.removePrefix("VmHWM:").trim().substringBefore(' ').toLongOrNull() ?: return null
        return kib * 1_024L
    }

    private class FirstAdmissionBarrierExecutor(
        private val delegate: RouteBoundFetchAttemptExecutor,
        private val firstAdmitted: CountDownLatch,
        private val releaseFirstConnect: CountDownLatch,
    ) : RouteBoundFetchAttemptExecutor {
        private val routeExecutions = AtomicInteger()

        override suspend fun execute(
            request: FetchRequest,
            attempt: Int,
            priority: StateFlow<FetchPriority>,
            emitChunk: suspend (FetchNetworkChunk) -> Unit,
        ): FetchAttemptDisposition = error("G2-E requires exact route binding")

        override suspend fun executeCorrelated(
            request: FetchRequest,
            attempt: Int,
            priority: StateFlow<FetchPriority>,
            onTransportCorrelation: suspend (String) -> Unit,
            emitChunk: suspend (FetchNetworkChunk) -> Unit,
        ): FetchAttemptDisposition = error("G2-E requires exact route binding")

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
                request,
                attempt,
                priority,
                routeBinding,
                deliveryBinding,
                onPhysicalAttemptStart = {
                    onPhysicalAttemptStart()
                    if (ordinal == 1) {
                        firstAdmitted.countDown()
                        check(releaseFirstConnect.await(BARRIER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                            "G2-E first owner barrier timed out"
                        }
                    }
                },
                onTransportCorrelation = onTransportCorrelation,
                emitChunk = emitChunk,
            )
        }
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

    private class G2RecoveryJitter(private val seed: Long) : RecoveryJitterSource {
        val samples = mutableListOf<Long>()
        private var sampleIndex = 0L

        override fun uniformInclusive(windowMs: Long): Long {
            require(windowMs >= 0)
            val span = BigInteger.valueOf(windowMs).add(BigInteger.ONE)
            val unsigned64 = BigInteger.ONE.shiftLeft(64)
            val limit = unsigned64.subtract(unsigned64.mod(span))
            var drawIndex = 0L
            while (true) {
                val material = "$RECOVERY_JITTER_DOMAIN:$seed:$sampleIndex:$drawIndex"
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
        const val PLAN_ASSET = "m2-g2-route-plan.json"
        const val RESOURCE_PATH = "/fixtures/F1/segment-0-00001.m4s"
        const val RESOURCE_LENGTH = 711_501L
        const val RESOURCE_SHA256 =
            "f3e8a844487d57a05c69975389566bde3bcb38d9afa1d53538be18c959d77fa3"
        const val RECOVERY_JITTER_PROTOCOL = "SHA256_COUNTER_REJECTION_V1"
        const val RECOVERY_JITTER_DOMAIN = "spongetube-g2-recovery-jitter-v1"
        const val HTTP_TIMEOUT_MS = 12_000
        const val BARRIER_TIMEOUT_MS = 15_000L
        const val ROUTE_TIMEOUT_MS = 30_000L
        const val ROUTE_UNAVAILABLE_STABILITY_MS = 750L
        const val OUTCOME_TIMEOUT_MS = 60_000L
        const val TEST_TIMEOUT_MS = 180_000L
        val PROCESS_INSTANCE_ID: String = UUID.randomUUID().toString()
    }
}
