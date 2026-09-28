package io.github.definitelystable.spongetube.core.engine.route

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
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
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryBudgetDimension
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryBudgetEventKind
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumer
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumerId
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumerKind
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryCoordinator
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryEvidenceRecorder
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryJitterSource
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryPermitReason
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryPolicy
import io.github.definitelystable.spongetube.core.engine.recovery.RecoverySleeper
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryTerminalReason
import io.github.definitelystable.spongetube.core.engine.recovery.RouteAwareRecoveryAttemptGate
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentSpec
import io.github.definitelystable.spongetube.core.storage.ExtentStore
import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import io.github.definitelystable.spongetube.core.storage.Sha256Digest
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M2-F3 N7 acceptance.
 *
 * Both cases begin on a proven usable actual VPN default route. Owner 1 is
 * admitted with the exact VPN Network but held immediately before connect.
 * The VPN is then removed, Android selects the physical NON_VPN replacement,
 * and the old exact binding fails. The same RecoveryChain must pause without
 * another owner or charge because the session guard is sticky VPN-required.
 *
 * VPN_RESTORE resumes only after a new usable VPN default appears.
 * DIRECT_OVERRIDE changes only the session override and proves that this
 * event itself wakes an already-paused gate on the same direct routeEpoch.
 */
@RunWith(AndroidJUnit4::class)
class VpnContinuityRecoveryAndroidTest {
    private lateinit var targetContext: Context

    @Before
    fun setUp() {
        targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        cleanStoreRoot()
    }

    @After
    fun tearDown() {
        cleanStoreRoot()
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun vpnRequiredChainPausesOnDirectReplacementAndResumesOnRestoredVpn() = runBlocking {
        runScenario(VpnRecoveryScenario.VPN_RESTORE)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun explicitDirectOverrideWakesPausedChainWithoutAnotherRouteChange() = runBlocking {
        runScenario(VpnRecoveryScenario.DIRECT_OVERRIDE)
    }

    private suspend fun runScenario(scenario: VpnRecoveryScenario) {
        assumeTrue(Build.VERSION.SDK_INT == 36)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val originBaseUrl = InstrumentationRegistry.getArguments()
            .getString(ORIGIN_ARGUMENT)
            ?.trimEnd('/')
        assumeTrue("M2-F3 requires $ORIGIN_ARGUMENT", !originBaseUrl.isNullOrBlank())
        val originText = checkNotNull(originBaseUrl)
        val origin = URL(originText)
        val connectivity = checkNotNull(
            testContext.getSystemService(ConnectivityManager::class.java),
        )
        val device = UiDevice.getInstance(instrumentation)
        val preferences = testContext.getSharedPreferences(
            RouteVpnFeasibilityVpnService.PREFERENCES,
            Context.MODE_PRIVATE,
        )

        val sessionId = "$SESSION_PREFIX-${scenario.id.lowercase(Locale.US)}"
        val routeRecorder = RouteEvidenceRecorder(
            runId = RUN_ID,
            sessionId = sessionId,
            androidApi = Build.VERSION.SDK_INT,
        )
        val recoveryRecorder = RecoveryEvidenceRecorder(
            runId = RUN_ID,
            sessionId = sessionId,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val monitor = AndroidDefaultRouteMonitor.open(testContext, scope, routeRecorder)

        var store: ExtentStore? = null
        var broker: FetchBroker? = null
        var coordinator: RecoveryCoordinator? = null
        var preflightRelay: OriginRelay? = null
        var recoveryRelay: OriginRelay? = null
        var monitorStopped = false
        val releaseFirstConnect = CountDownLatch(1)

        try {
            val initialDirect = awaitRoute(
                monitor = monitor,
                vpn = ObservedBoolean.FALSE,
            )
            val initialDirectState = initialDirect.state as DefaultRouteState.Available
            val underlying = checkNotNull(connectivity.activeNetwork)
            val underlyingCapabilities = checkNotNull(
                connectivity.getNetworkCapabilities(underlying),
            )
            assertTrue(
                "F3 requires a NON_VPN physical underlay before VPN start",
                underlyingCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
            )

            prepareVpn(testContext, device)
            preferences.edit().clear().commit()
            startVpn(testContext)
            awaitVpnReady(preferences)

            val initialVpn = awaitRoute(
                monitor = monitor,
                vpn = ObservedBoolean.TRUE,
                minimumEpochExclusive = initialDirectState.routeEpoch,
            )
            val initialVpnState = initialVpn.state as DefaultRouteState.Available
            val initialVpnBinding = checkNotNull(initialVpn.executionBinding)

            preflightRelay = OriginRelay(
                underlying = underlying,
                originHost = checkNotNull(origin.host),
                originPort = origin.effectivePort(),
            ).also { it.start() }
            val preflightUrl = URL(
                "http://${RouteVpnFeasibilityVpnService.SYNTHETIC_MEDIA_HOST}:" +
                    "${preflightRelay.localPort}$PREFLIGHT_PATH",
            )
            val preflightBody = vpnPreflight(initialVpnBinding, preflightUrl)
            preflightRelay.awaitSuccess()
            preflightRelay.close()
            preflightRelay = null

            val openedStore = ExtentStore.open(targetContext)
            store = openedStore
            seedSentinel(openedStore, scenario)
            val persistedBefore = committedExtentIds(openedStore)
            assertEquals(listOf(scenario.sentinelExtentId), persistedBefore)

            recoveryRelay = OriginRelay(
                underlying = underlying,
                originHost = checkNotNull(origin.host),
                originPort = origin.effectivePort(),
            ).also { it.start() }

            val vpnTarget = URL(
                "http://${RouteVpnFeasibilityVpnService.SYNTHETIC_MEDIA_HOST}:" +
                    "${recoveryRelay.localPort}${scenario.mediaPath}",
            )
            val directTarget = URL("$originText${scenario.mediaPath}")
            val vpnExecutor = executorFor(vpnTarget, scenario)
            val directExecutor = executorFor(directTarget, scenario)
            val firstAdmitted = CountDownLatch(1)
            val routeExecutor = ScenarioRouteExecutor(
                vpnExecutor = vpnExecutor,
                directExecutor = directExecutor,
                directSecondOwner = scenario == VpnRecoveryScenario.DIRECT_OVERRIDE,
                firstAdmitted = firstAdmitted,
                releaseFirstConnect = releaseFirstConnect,
            )

            val fetchEvents = CopyOnWriteArrayList<FetchEvent>()
            val guard = SessionRouteGuard()
            val routeGate = RouteAwareRecoveryAttemptGate(
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
                routeGate.awaitPermit(chainId)
            }

            val openedBroker = FetchBroker(
                extentStore = openedStore,
                executor = routeExecutor,
                sessionId = sessionId,
                eventListener = FetchEventListener { fetchEvents += it },
            )
            broker = openedBroker
            val openedCoordinator = RecoveryCoordinator(
                broker = openedBroker,
                sessionId = sessionId,
                policy = RecoveryPolicy.DEFAULT,
                attemptGate = gate,
                jitter = RecoveryJitterSource { 0L },
                sleeper = RecoverySleeper { },
                evidence = recoveryRecorder,
            )
            coordinator = openedCoordinator

            val handle = openedCoordinator.acquire(
                requestFor(scenario),
                RecoveryConsumer(
                    RecoveryConsumerId("playback-${scenario.id.lowercase(Locale.US)}"),
                    RecoveryConsumerKind.PLAYBACK,
                ),
            )
            val chainId = handle.recoveryChainId.value
            val outcomeDeferred = scope.async { handle.await() }

            assertTrue(
                "owner 1 was not admitted on the VPN route",
                withContext(Dispatchers.IO) {
                    firstAdmitted.await(BARRIER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                },
            )
            assertEquals(SessionRouteGuardState.VPN_CONTINUITY_REQUIRED, guard.state)
            assertEquals(1, recoveryRecorder.chargeCount())
            assertEquals(1, fetchEvents.attemptStarts())

            stopVpn(testContext)
            val directReplacement = awaitRoute(
                monitor = monitor,
                vpn = ObservedBoolean.FALSE,
                minimumEpochExclusive = initialVpnState.routeEpoch,
            )
            val directState = directReplacement.state as DefaultRouteState.Available
            val initialVpnStats = awaitVpnStoppedStats(preferences)
            assertTrue(initialVpnStats.packets > 0)
            assertTrue(initialVpnStats.bytes > 0)

            releaseFirstConnect.countDown()
            withTimeout(ROUTE_TIMEOUT_MS) { secondGateEntered.await() }
            withTimeout(ROUTE_TIMEOUT_MS) {
                while (
                    routeRecorder.policyEvaluations().none {
                        it.routeEpoch == directState.routeEpoch &&
                            it.decision.reason ==
                            ExternalFetchRouteReason.VPN_CONTINUITY_REQUIRED
                    }
                ) {
                    yield()
                }
            }

            val chargesWhilePaused = recoveryRecorder.chargeCount()
            val attemptsWhilePaused = fetchEvents.attemptStarts()
            assertEquals(1, chargesWhilePaused)
            assertEquals(1, attemptsWhilePaused)
            assertEquals(2, gateCalls.get())

            var resumeEpoch: Long
            var resumedVpnStats = VpnStats(0L, 0L)
            val expectedSecondReason: RecoveryPermitReason

            when (scenario) {
                VpnRecoveryScenario.VPN_RESTORE -> {
                    preferences.edit().clear().commit()
                    startVpn(testContext)
                    awaitVpnReady(preferences)
                    val restoredVpn = awaitRoute(
                        monitor = monitor,
                        vpn = ObservedBoolean.TRUE,
                        minimumEpochExclusive = directState.routeEpoch,
                    )
                    resumeEpoch =
                        (restoredVpn.state as DefaultRouteState.Available).routeEpoch
                    expectedSecondReason = RecoveryPermitReason.ROUTE_READY
                }
                VpnRecoveryScenario.DIRECT_OVERRIDE -> {
                    resumeEpoch = directState.routeEpoch
                    expectedSecondReason = RecoveryPermitReason.EXPLICIT_DIRECT_OVERRIDE
                    // No route mutation here. F3 requires this state change to
                    // wake an already suspended gate by itself.
                    guard.explicitDirectOverride = true
                }
            }

            val outcome = withTimeout(OUTCOME_TIMEOUT_MS) { outcomeDeferred.await() }

            if (scenario == VpnRecoveryScenario.VPN_RESTORE) {
                recoveryRelay.awaitSuccess()
                stopVpn(testContext)
                resumedVpnStats = awaitVpnStoppedStats(preferences)
                assertTrue(resumedVpnStats.packets > 0)
                assertTrue(resumedVpnStats.bytes > 0)
                awaitRoute(
                    monitor = monitor,
                    vpn = ObservedBoolean.FALSE,
                    minimumEpochExclusive = resumeEpoch,
                )
            }

            val persistedAfter = committedExtentIds(openedStore)
            assertEquals(RecoveryTerminalReason.SUCCESS, outcome.terminalReason)
            assertEquals(chainId, outcome.recoveryChainId.value)
            assertEquals(scenario.fetchKey, outcome.fetchKey.value)
            assertEquals(SessionRouteGuardState.VPN_CONTINUITY_REQUIRED, guard.state)
            assertEquals(2, recoveryRecorder.chargeCount())
            assertEquals(
                listOf(1, 2),
                recoveryRecorder.budgetEvents()
                    .filter { it.kind == RecoveryBudgetEventKind.CHARGE }
                    .map {
                        it.spent.getValue(RecoveryBudgetDimension.REMOTE_ATTEMPT)
                    },
            )

            val permits = recoveryRecorder.budgetEvents()
                .filter { it.kind == RecoveryBudgetEventKind.ATTEMPT_PERMIT_GRANTED }
                .map { checkNotNull(it.permit) }
            assertEquals(2, permits.size)
            assertEquals(initialVpnState.routeEpoch, permits[0].routeEpoch)
            assertEquals(RecoveryPermitReason.ROUTE_READY, permits[0].reason)
            assertEquals(resumeEpoch, permits[1].routeEpoch)
            assertEquals(expectedSecondReason, permits[1].reason)

            assertEquals(2, fetchEvents.attemptStarts())
            val failure = recoveryRecorder.failures().single()
            assertEquals(initialVpnState.routeEpoch, failure.routeEpoch)
            assertEquals(FailureClassification.TRANSIENT_TRANSPORT, failure.classification)
            assertEquals(RecoveryActionKind.SCHEDULE_BACKOFF, failure.action.kind)
            assertTrue(persistedAfter.contains(scenario.sentinelExtentId))
            assertTrue(persistedAfter.contains(scenario.targetExtentId))

            val directPause = routeRecorder.policyEvaluations()
                .last {
                    it.routeEpoch == directState.routeEpoch &&
                        it.decision.reason ==
                        ExternalFetchRouteReason.VPN_CONTINUITY_REQUIRED
                }
            val resumeEvaluation = routeRecorder.policyEvaluations()
                .last {
                    it.routeEpoch == resumeEpoch &&
                        it.decision.action == ExternalFetchAction.ALLOW
                }

            if (scenario == VpnRecoveryScenario.DIRECT_OVERRIDE) {
                assertEquals(
                    ExternalFetchRouteReason.EXPLICIT_DIRECT_OVERRIDE,
                    resumeEvaluation.decision.reason,
                )
                assertEquals(directPause.routeEpoch, resumeEvaluation.routeEpoch)
                assertTrue(resumeEvaluation.decision.explicitDirectOverride)
            } else {
                assertEquals(
                    ExternalFetchRouteReason.ROUTE_READY,
                    resumeEvaluation.decision.reason,
                )
                assertFalse(resumeEvaluation.decision.explicitDirectOverride)
            }

            monitor.shutdown()
            monitorStopped = true
            assertTrue(monitor.isClosed)

            writeEvidence(
                scenario = scenario,
                routeRecorder = routeRecorder,
                recoveryRecorder = recoveryRecorder,
                fetchEvents = fetchEvents,
                case = linkedMapOf(
                    "schemaVersion" to 1,
                    "phase" to "M2-F3",
                    "scenarioId" to scenario.id,
                    "deviceApi" to Build.VERSION.SDK_INT,
                    "status" to "PASS",
                    "recoveryChainId" to chainId,
                    "initialDirectEpoch" to initialDirectState.routeEpoch,
                    "initialVpnEpoch" to initialVpnState.routeEpoch,
                    "directReplacementEpoch" to directState.routeEpoch,
                    "resumeEpoch" to resumeEpoch,
                    "resumePermitReason" to expectedSecondReason.value,
                    "vpnPreflightStatus" to HttpURLConnection.HTTP_PARTIAL,
                    "vpnPreflightBytes" to preflightBody.size,
                    "vpnPreflightBodySha256" to sha256(preflightBody),
                    "initialVpnReflectedPackets" to initialVpnStats.packets,
                    "initialVpnReflectedBytes" to initialVpnStats.bytes,
                    "resumedVpnReflectedPackets" to resumedVpnStats.packets,
                    "resumedVpnReflectedBytes" to resumedVpnStats.bytes,
                    "gateCalls" to gateCalls.get(),
                    "chargesWhilePaused" to chargesWhilePaused,
                    "attemptsWhilePaused" to attemptsWhilePaused,
                    "directPauseRouteEventWatermark" to
                        directPause.routeEventSequenceWatermark,
                    "resumeRouteEventWatermark" to
                        resumeEvaluation.routeEventSequenceWatermark,
                    "explicitDirectOverride" to guard.explicitDirectOverride,
                    "persistedExtentIdsBefore" to persistedBefore,
                    "persistedExtentIdsAfter" to persistedAfter,
                    "sentinelExtentId" to scenario.sentinelExtentId,
                    "targetExtentId" to scenario.targetExtentId,
                    "mediaPathUsesAdbReverse" to false,
                    "monitorStopped" to true,
                ),
            )
        } finally {
            releaseFirstConnect.countDown()
            runCatching { stopVpn(testContext) }
            runCatching { coordinator?.shutdown() }
            runCatching { broker?.shutdown() }
            if (!monitorStopped) {
                runCatching { monitor.shutdown() }
            }
            runCatching { store?.close() }
            runCatching { preflightRelay?.close() }
            runCatching { recoveryRelay?.close() }
            scope.cancel()
        }
    }

    private fun executorFor(
        target: URL,
        scenario: VpnRecoveryScenario,
    ): HttpRangeFetchExecutor =
        HttpRangeFetchExecutor(
            targetFor = {
                HttpRangeTarget(
                    url = target,
                    resourceLength = scenario.resourceLength,
                )
            },
            connectTimeoutMs = HTTP_TIMEOUT_MS,
            readTimeoutMs = HTTP_TIMEOUT_MS,
        )

    private fun requestFor(scenario: VpnRecoveryScenario): FetchRequest =
        FetchRequest(
            fetchKey = FetchKey(scenario.fetchKey),
            extentSpec = ExtentSpec(
                mediaAssetId = MediaAssetId("fixture:F1"),
                extentId = ExtentId(scenario.targetExtentId),
                trackId = "audio-main",
                representationId = "f1-audio-1",
                mediaStartUs = 0,
                mediaEndUs = 9_941_333,
                byteStart = 0,
                byteEndExclusive = scenario.resourceLength,
                expectedLength = scenario.resourceLength,
                expectedSha256 = Sha256Digest(scenario.resourceSha256),
            ),
        )

    private suspend fun seedSentinel(
        store: ExtentStore,
        scenario: VpnRecoveryScenario,
    ) {
        val spec = ExtentSpec(
            mediaAssetId = MediaAssetId("fixture:M2F3-${scenario.id}"),
            extentId = ExtentId(scenario.sentinelExtentId),
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

    private suspend fun awaitRoute(
        monitor: DefaultRouteMonitor,
        vpn: ObservedBoolean,
        minimumEpochExclusive: Long = 0L,
    ): RouteObservation =
        withTimeout(ROUTE_TIMEOUT_MS) {
            monitor.observations.first { observation ->
                val state = observation.state as? DefaultRouteState.Available
                state != null &&
                    state.capabilitiesReceived &&
                    state.routeEpoch > minimumEpochExclusive &&
                    state.capabilities.vpn == vpn &&
                    state.capabilities.internet != ObservedBoolean.FALSE &&
                    observation.executionBinding != null
            }
        }

    private suspend fun prepareVpn(
        context: Context,
        device: UiDevice,
    ) {
        val prepare = VpnService.prepare(context)
        if (prepare != null) {
            val dialogPackage = checkNotNull(prepare.component?.packageName) {
                "VpnService.prepare returned an intent without a component package"
            }
            RouteVpnFeasibilityConsentActivity.resetResult()
            context.startActivity(
                Intent(context, RouteVpnFeasibilityConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            device.waitForIdle()

            val allow = device.findObject(
                UiSelector()
                    .className("android.widget.Button")
                    .packageName(dialogPackage)
                    .resourceIdMatches("android:id/button1$|button_start_vpn"),
            )
            assertTrue(
                "VPN confirmation allow button not found",
                allow.waitForExists(VPN_CONSENT_TIMEOUT_MS),
            )
            assertTrue("VPN confirmation click failed", allow.click())
            device.waitForIdle()
            assertEquals(
                Activity.RESULT_OK,
                RouteVpnFeasibilityConsentActivity.awaitResult(VPN_CONSENT_TIMEOUT_MS),
            )

            withTimeout(VPN_CONSENT_TIMEOUT_MS) {
                while (VpnService.prepare(context) != null) {
                    delay(100)
                }
            }
        }
        assertTrue("VPN package is not prepared", VpnService.prepare(context) == null)
    }

    private fun startVpn(context: Context) {
        context.startForegroundService(
            Intent(context, RouteVpnFeasibilityVpnService::class.java)
                .setAction(RouteVpnFeasibilityVpnService.ACTION_START),
        )
    }

    private fun stopVpn(context: Context) {
        context.startService(
            Intent(context, RouteVpnFeasibilityVpnService::class.java)
                .setAction(RouteVpnFeasibilityVpnService.ACTION_STOP),
        )
    }

    private suspend fun awaitVpnReady(preferences: SharedPreferences) {
        withTimeout(VPN_READY_TIMEOUT_MS) {
            while (!preferences.getBoolean(RouteVpnFeasibilityVpnService.KEY_READY, false)) {
                delay(25)
            }
        }
    }

    private suspend fun awaitVpnStoppedStats(
        preferences: SharedPreferences,
    ): VpnStats =
        withTimeout(VPN_STOP_TIMEOUT_MS) {
            while (
                preferences.getBoolean(RouteVpnFeasibilityVpnService.KEY_READY, true) ||
                !preferences.contains(RouteVpnFeasibilityVpnService.KEY_REFLECTED_PACKETS) ||
                !preferences.contains(RouteVpnFeasibilityVpnService.KEY_REFLECTED_BYTES)
            ) {
                delay(25)
            }
            VpnStats(
                packets = preferences.getLong(
                    RouteVpnFeasibilityVpnService.KEY_REFLECTED_PACKETS,
                    0L,
                ),
                bytes = preferences.getLong(
                    RouteVpnFeasibilityVpnService.KEY_REFLECTED_BYTES,
                    0L,
                ),
            )
        }

    private fun vpnPreflight(
        binding: RouteExecutionBinding,
        url: URL,
    ): ByteArray {
        val connection = binding.openConnection(url)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Range", PREFLIGHT_RANGE)
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Connection", "close")
            assertEquals(HttpURLConnection.HTTP_PARTIAL, connection.responseCode)
            assertEquals(
                PREFLIGHT_CONTENT_RANGE,
                connection.getHeaderField("Content-Range"),
            )
            return connection.inputStream.use { it.readBytes() }.also {
                assertEquals(PREFLIGHT_BYTES, it.size)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun writeEvidence(
        scenario: VpnRecoveryScenario,
        routeRecorder: RouteEvidenceRecorder,
        recoveryRecorder: RecoveryEvidenceRecorder,
        fetchEvents: List<FetchEvent>,
        case: Map<String, Any?>,
    ) {
        val output = PlatformTestStorageRegistry.getInstance()
        val policyId = RecoveryPolicy.DEFAULT.policyId
        val directory = "$EVIDENCE_DIR/${scenario.id}"

        fun write(name: String, value: String) {
            output.openOutputFile("$directory/$name")
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
        File(targetContext.filesDir, "sponge").deleteRecursively()
    }

    private class ScenarioRouteExecutor(
        private val vpnExecutor: HttpRangeFetchExecutor,
        private val directExecutor: HttpRangeFetchExecutor,
        private val directSecondOwner: Boolean,
        private val firstAdmitted: CountDownLatch,
        private val releaseFirstConnect: CountDownLatch,
    ) : RouteBoundFetchAttemptExecutor {
        private val executions = AtomicInteger()

        override suspend fun execute(
            request: FetchRequest,
            attempt: Int,
            priority: StateFlow<FetchPriority>,
            emitChunk: suspend (FetchNetworkChunk) -> Unit,
        ): FetchAttemptDisposition =
            error("M2-F3 requires exact route-bound execution")

        override suspend fun executeCorrelated(
            request: FetchRequest,
            attempt: Int,
            priority: StateFlow<FetchPriority>,
            onTransportCorrelation: suspend (String) -> Unit,
            emitChunk: suspend (FetchNetworkChunk) -> Unit,
        ): FetchAttemptDisposition =
            error("M2-F3 requires exact route-bound execution")

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
            val ordinal = executions.incrementAndGet()
            check(ordinal <= 2) { "M2-F3 expected at most two owners" }
            val delegate = if (ordinal == 2 && directSecondOwner) {
                directExecutor
            } else {
                vpnExecutor
            }
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
                            "M2-F3 first owner barrier timed out"
                        }
                    }
                },
                onTransportCorrelation = onTransportCorrelation,
                emitChunk = emitChunk,
            )
        }
    }

    private class OriginRelay(
        private val underlying: Network,
        private val originHost: String,
        private val originPort: Int,
    ) : AutoCloseable {
        private val failure = AtomicReference<Throwable?>()
        private val server = ServerSocket(0, 1, InetAddress.getByName("0.0.0.0"))
        private lateinit var worker: Thread

        val localPort: Int
            get() = server.localPort

        fun start() {
            worker = thread(name = "M2-F3-OriginRelay", start = true) {
                try {
                    server.accept().use { client ->
                        client.soTimeout = HTTP_TIMEOUT_MS
                        Socket().use { upstream ->
                            underlying.bindSocket(upstream)
                            upstream.connect(
                                InetSocketAddress(originHost, originPort),
                                HTTP_TIMEOUT_MS,
                            )
                            upstream.soTimeout = HTTP_TIMEOUT_MS

                            val clientInput = BufferedInputStream(client.getInputStream())
                            val clientOutput = BufferedOutputStream(client.getOutputStream())
                            val upstreamInput = BufferedInputStream(upstream.getInputStream())
                            val upstreamOutput = BufferedOutputStream(upstream.getOutputStream())

                            upstreamOutput.write(readHeaders(clientInput))
                            upstreamOutput.flush()

                            val responseHeaders = readHeaders(upstreamInput)
                            val contentLength = parseContentLength(responseHeaders)
                            clientOutput.write(responseHeaders)
                            copyExactly(upstreamInput, clientOutput, contentLength)
                            clientOutput.flush()
                        }
                    }
                } catch (error: Throwable) {
                    if (!server.isClosed) {
                        failure.compareAndSet(null, error)
                    }
                }
            }
        }

        fun awaitSuccess() {
            worker.join(RELAY_TIMEOUT_MS)
            assertFalse("origin relay did not terminate", worker.isAlive)
            failure.get()?.let { throw AssertionError("origin relay failed", it) }
        }

        override fun close() {
            runCatching { server.close() }
            if (::worker.isInitialized) {
                worker.interrupt()
                worker.join(2_000)
            }
        }

        private fun readHeaders(input: BufferedInputStream): ByteArray {
            val output = ByteArrayOutputStream()
            var matched = 0
            val delimiter = byteArrayOf(
                '\r'.code.toByte(),
                '\n'.code.toByte(),
                '\r'.code.toByte(),
                '\n'.code.toByte(),
            )
            while (output.size() < MAX_HEADER_BYTES) {
                val value = input.read()
                check(value >= 0) { "HTTP stream ended before headers completed" }
                output.write(value)
                if (value.toByte() == delimiter[matched]) {
                    matched += 1
                    if (matched == delimiter.size) {
                        return output.toByteArray()
                    }
                } else {
                    matched = if (value.toByte() == delimiter[0]) 1 else 0
                }
            }
            error("HTTP headers exceed $MAX_HEADER_BYTES bytes")
        }

        private fun parseContentLength(headers: ByteArray): Int {
            val text = String(headers, StandardCharsets.ISO_8859_1)
            val value = text.lineSequence()
                .map { it.trim() }
                .firstOrNull {
                    it.lowercase(Locale.US).startsWith("content-length:")
                }
                ?.substringAfter(':')
                ?.trim()
                ?: error("origin response is missing Content-Length")
            return value.toInt().also { require(it >= 0) }
        }

        private fun copyExactly(
            input: BufferedInputStream,
            output: BufferedOutputStream,
            bytes: Int,
        ) {
            var remaining = bytes
            val buffer = ByteArray(8192)
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                check(count > 0) { "origin response ended with $remaining bytes remaining" }
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
    }

    private data class VpnStats(
        val packets: Long,
        val bytes: Long,
    )

    private enum class VpnRecoveryScenario(
        val id: String,
        val mediaPath: String,
        val fetchKey: String,
        val targetExtentId: String,
        val sentinelExtentId: String,
        val resourceLength: Long,
        val resourceSha256: String,
    ) {
        VPN_RESTORE(
            id = "VPN_RESTORE",
            mediaPath = "/fixtures/F1/segment-1-00001.m4s",
            fetchKey = "fixture:F1/m2f3/vpn-restore/segment-1-00001",
            targetExtentId = "m2f3:vpn-restore:target",
            sentinelExtentId = "m2f3:vpn-restore:sentinel",
            resourceLength = 81_811L,
            resourceSha256 =
                "08ac93538dcb3f5eece5996b0abab1e4e7677afbc7b21cc3292a63c776ef4943",
        ),
        DIRECT_OVERRIDE(
            id = "DIRECT_OVERRIDE",
            mediaPath = "/fixtures/F1/segment-1-00002.m4s",
            fetchKey = "fixture:F1/m2f3/direct-override/segment-1-00002",
            targetExtentId = "m2f3:direct-override:target",
            sentinelExtentId = "m2f3:direct-override:sentinel",
            resourceLength = 82_463L,
            resourceSha256 =
                "6d6ae7393926ab6cf07cee17e88fd8a13e13eebb71a688d76f242334b0028b75",
        ),
    }

    private fun RecoveryEvidenceRecorder.chargeCount(): Int =
        budgetEvents().count { it.kind == RecoveryBudgetEventKind.CHARGE }

    private fun List<FetchEvent>.attemptStarts(): Int =
        count { it.event == FetchEventKind.ATTEMPT_STARTED }

    private fun URL.effectivePort(): Int =
        if (port >= 0) port else defaultPort

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }

    private companion object {
        const val ORIGIN_ARGUMENT = "spongetube.m2f3.originBaseUrl"
        const val RUN_ID = "m2-f3-api36-vpn-continuity"
        const val SESSION_PREFIX = "m2-f3-vpn"
        const val EVIDENCE_DIR = "m2-f3-vpn-continuity"

        const val PREFLIGHT_PATH = "/fixtures/F0/progressive.mp4"
        const val PREFLIGHT_RANGE = "bytes=0-4095"
        const val PREFLIGHT_CONTENT_RANGE = "bytes 0-4095/1054544"
        const val PREFLIGHT_BYTES = 4096

        val SENTINEL_BYTES = byteArrayOf(11, 22, 33, 44)
        const val SENTINEL_SHA256 =
            "bdbdce312f5762571faa17f3f4780235ed2c54f966c0f60dace1cddd2634f4f3"

        const val HTTP_TIMEOUT_MS = 10_000
        const val BARRIER_TIMEOUT_MS = 15_000L
        const val ROUTE_TIMEOUT_MS = 30_000L
        const val OUTCOME_TIMEOUT_MS = 45_000L
        const val VPN_CONSENT_TIMEOUT_MS = 10_000L
        const val VPN_READY_TIMEOUT_MS = 10_000L
        const val VPN_STOP_TIMEOUT_MS = 10_000L
        const val RELAY_TIMEOUT_MS = 15_000L
        const val MAX_HEADER_BYTES = 64 * 1024
        const val TEST_TIMEOUT_MS = 150_000L

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
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") {
                json(it)
            }
            else -> error("unsupported JSON value: $value")
        }
    }
}
