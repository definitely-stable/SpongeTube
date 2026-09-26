package io.github.definitelystable.spongetube.core.engine

import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingCoordinator
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingEvent
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingEventKind
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingEvidenceRecorder
import io.github.definitelystable.spongetube.core.engine.delivery.ProviderWallClock
import io.github.definitelystable.spongetube.core.engine.delivery.RetryAfterKind
import io.github.definitelystable.spongetube.core.engine.recovery.FailureClassification
import io.github.definitelystable.spongetube.core.engine.recovery.FailureDecisionEvent
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryActionKind
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumer
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumerId
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumerKind
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryCoordinator
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryEvidenceRecorder
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryOutcome
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryPolicy
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryTerminalReason
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentSpec
import io.github.definitelystable.spongetube.core.storage.ExtentStore
import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import io.github.definitelystable.spongetube.core.storage.Sha256Digest
import io.github.definitelystable.spongetube.testsupport.fixture.f1.F1FixtureAssets
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M2-D Android provider-recovery proof against the Media Lab deterministic
 * provider simulator (N8/N9/N10), driving the production `RecoveryCoordinator`
 * (`sponge-recovery-v2`), `FetchBroker`, real `HttpRangeFetchExecutor`,
 * `DeliveryBindingCoordinator` and `ExtentStore`.
 *
 * ```text
 * N8_BARE_403                    403 -> PROVIDER_REJECTED -> TERMINAL_FAILURE
 * N9_429_DELAY_SECONDS           429 + Retry-After: 2 -> WAIT_PROVIDER -> 206
 * N9_429_HTTP_DATE               429 + Retry-After: HTTP-date -> WAIT_PROVIDER -> 206
 * N10_BINDING_EXPIRED_REFRESH    403 + stale signal -> refresh -> gen-2 -> 206
 * ```
 *
 * One run is one scenario: the scenario id, the origin base URL and the
 * control base URL arrive as instrumentation arguments, so the plain
 * full-suite run on every API skips this test. Exported evidence is verified
 * on the host by `scripts/ci/verify-m2-d-provider-evidence.sh device` against
 * the Media Lab request trace and the provider-fault-events-v1 artifact.
 */
@RunWith(AndroidJUnit4::class)
class ProviderRecoveryAndroidTest {
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
    fun providerScenarioRecoversDeterministically() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val originBaseUrl = arguments.getString(ORIGIN_BASE_URL_ARGUMENT)?.trimEnd('/')
        val controlBaseUrl = arguments.getString(CONTROL_BASE_URL_ARGUMENT)?.trimEnd('/')
        val scenarioId = arguments.getString(SCENARIO_ARGUMENT)?.trim()
        assumeTrue(
            "M2-D provider recovery requires $ORIGIN_BASE_URL_ARGUMENT, " +
                "$CONTROL_BASE_URL_ARGUMENT and $SCENARIO_ARGUMENT",
            !originBaseUrl.isNullOrBlank() &&
                !controlBaseUrl.isNullOrBlank() &&
                !scenarioId.isNullOrBlank(),
        )
        val origin = checkNotNull(originBaseUrl)
        val control = checkNotNull(controlBaseUrl)
        val scenario = checkNotNull(ProviderScenario.forScenarioId(checkNotNull(scenarioId))) {
            "unknown M2-D provider scenario: $scenarioId"
        }
        val config = readLabConfig(control)
        assertEquals(scenario.profileId, config.profileId)
        assertEquals(scenario.providerVariant, config.providerVariant)
        // The lab reports a fixed virtual provider wall clock; only this
        // PROVIDER_WALL_CLOCK value may order provider expiry and HTTP dates.
        val providerWallClock = ProviderWallClock { config.providerWallClockEpochMs }

        val fixtures = F1FixtureAssets(context.assets)
        val fixtureFact = checkNotNull(fixtures.resourceFacts[F1_RESOURCE]) {
            "fixture manifest has no $F1_RESOURCE"
        }
        val initialMaterial = LabDeliveryMaterial(
            generation = INITIAL_GENERATION,
            resources = fixtures.resourceFacts.entries.associate { (path, fact) ->
                LAB_RESOURCE_PREFIX + path to fact.length
            },
        )
        val request = FetchRequest(
            fetchKey = FetchKey(FETCH_KEY),
            extentSpec = ExtentSpec(
                mediaAssetId = MediaAssetId("fixture:F1"),
                extentId = ExtentId(EXTENT_ID),
                trackId = "audio-main",
                representationId = "f1-audio-1",
                mediaStartUs = 0,
                mediaEndUs = 9_941_333,
                byteStart = 0,
                byteEndExclusive = fixtureFact.length,
                expectedLength = fixtureFact.length,
                expectedSha256 = Sha256Digest(fixtureFact.sha256),
            ),
        )

        val fetchEvents = CopyOnWriteArrayList<FetchEvent>()
        val recoveryRecorder = RecoveryEvidenceRecorder(
            runId = RUN_ID,
            sessionId = SESSION_ID,
        )
        val deliveryRecorder = DeliveryBindingEvidenceRecorder(
            runId = RUN_ID,
            sessionId = SESSION_ID,
        )
        val store = ExtentStore.open(context)
        val bindings = DeliveryBindingCoordinator(
            initialMaterial = initialMaterial,
            refresher = LabRefresher(
                originBaseUrl = origin,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
            ),
            evidence = deliveryRecorder,
        )
        val broker = FetchBroker(
            extentStore = store,
            executor = HttpRangeFetchExecutor(
                targetFor = { null },
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
                bindingTargetFor = { _, material ->
                    labHttpRangeTarget(material, origin, LAB_RESOURCE_PATH)
                },
                providerSignalFor = ::labProviderSignal,
                providerWallClock = providerWallClock,
            ),
            sessionId = SESSION_ID,
            eventListener = FetchEventListener { fetchEvents += it },
        )
        val coordinator = RecoveryCoordinator(
            broker = broker,
            sessionId = SESSION_ID,
            policy = RecoveryPolicy.DEFAULT,
            bindings = bindings,
            providerWallClock = providerWallClock,
            evidence = recoveryRecorder,
        )

        val persistedBefore = committedExtentIds(store)
        var persistedAfter = persistedBefore
        val outcome = try {
            val result = withTimeout(TEST_TIMEOUT_MS) {
                coordinator.acquire(
                    request,
                    RecoveryConsumer(
                        RecoveryConsumerId("playback-m2d"),
                        RecoveryConsumerKind.PLAYBACK,
                    ),
                ).await()
            }
            persistedAfter = committedExtentIds(store)
            result
        } finally {
            coordinator.shutdown()
            broker.shutdown()
            store.close()
        }

        assertScenario(
            scenario = scenario,
            config = config,
            outcome = outcome,
            request = request,
            persistedAfter = persistedAfter,
            fetchEvents = fetchEvents,
            deliveryEvents = deliveryRecorder.events(),
            refreshOperationCount = bindings.refreshOperationCountForTest(),
            failures = recoveryRecorder.failures(),
        )
        assertTrue(
            "a committed extent was removed: $persistedBefore -> $persistedAfter",
            persistedAfter.containsAll(persistedBefore),
        )

        writeEvidence(
            api = Build.VERSION.SDK_INT,
            scenarioId = scenario.scenarioId,
            case = caseDefinition(scenario, persistedBefore, persistedAfter),
            recoveryRecorder = recoveryRecorder,
            deliveryRecorder = deliveryRecorder,
            fetchEvents = fetchEvents,
        )
    }

    private fun assertScenario(
        scenario: ProviderScenario,
        config: LabConfig,
        outcome: RecoveryOutcome,
        request: FetchRequest,
        persistedAfter: List<String>,
        fetchEvents: List<FetchEvent>,
        deliveryEvents: List<DeliveryBindingEvent>,
        refreshOperationCount: Int,
        failures: List<FailureDecisionEvent>,
    ) {
        assertEquals(request.fetchKey, outcome.fetchKey)
        assertEquals(
            scenario.expectedTerminals,
            mapOf(outcome.terminalReason.name to 1),
        )
        val attempts = fetchEvents.filter { it.event == FetchEventKind.ATTEMPT_STARTED }
        assertEquals(scenario.expectedPhysicalAttempts, attempts.size)
        assertEquals(1, attempts.map { it.fetchKey }.distinct().size)
        val selections = deliveryEvents
            .filter { it.kind == DeliveryBindingEventKind.BINDING_SELECTED_FOR_ATTEMPT }
        assertEquals(
            scenario.expectedBindingRevisions,
            selections.map { checkNotNull(it.currentRevision).value },
        )
        assertEquals(1, selections.map { it.fetchKey to it.extentId }.distinct().size)
        assertEquals(
            scenario.expectedRefreshOperations,
            deliveryEvents.count { it.kind == DeliveryBindingEventKind.REFRESH_STARTED },
        )
        assertEquals(scenario.expectedRefreshOperations, refreshOperationCount)
        assertEquals(
            scenario.expectedProviderWaits,
            failures.filter { it.action.kind == RecoveryActionKind.WAIT_PROVIDER }
                .map { checkNotNull(it.action.providerWait).waitMs },
        )

        when (scenario) {
            ProviderScenario.N8_BARE_403 -> {
                assertEquals(RecoveryTerminalReason.TERMINAL_FAILURE, outcome.terminalReason)
                assertEquals(
                    FailureClassification.PROVIDER_REJECTED,
                    failures.single().classification,
                )
                assertNull(outcome.committedExtent)
                assertTrue(persistedAfter.isEmpty())
            }
            ProviderScenario.N9_429_DELAY_SECONDS -> {
                assertEquals(RecoveryTerminalReason.SUCCESS, outcome.terminalReason)
                val wait = checkNotNull(failures.single().action.providerWait)
                assertEquals(RetryAfterKind.DELAY_SECONDS, wait.rawKind)
                assertEquals(2_000L, wait.waitMs)
                assertNull(wait.notBeforeUtcEpochMs)
                assertCommitted(outcome, request)
            }
            ProviderScenario.N9_429_HTTP_DATE -> {
                assertEquals(RecoveryTerminalReason.SUCCESS, outcome.terminalReason)
                val wait = checkNotNull(failures.single().action.providerWait)
                assertEquals(RetryAfterKind.HTTP_DATE, wait.rawKind)
                assertEquals(2_000L, wait.waitMs)
                assertEquals(
                    config.providerWallClockEpochMs + 2_000L,
                    checkNotNull(wait.notBeforeUtcEpochMs),
                )
                assertEquals(
                    config.providerWallClockEpochMs,
                    checkNotNull(wait.wallClockNowUtcEpochMs),
                )
                assertCommitted(outcome, request)
            }
            ProviderScenario.N10_BINDING_EXPIRED_REFRESH -> {
                assertEquals(RecoveryTerminalReason.SUCCESS, outcome.terminalReason)
                assertEquals(1, refreshOperationCount)
                assertCommitted(outcome, request)
            }
        }
    }

    private fun assertCommitted(
        outcome: RecoveryOutcome,
        request: FetchRequest,
    ) {
        val committed = checkNotNull(outcome.committedExtent) {
            "expected a committed extent for ${request.extentSpec.extentId}"
        }
        assertEquals(request.extentSpec.extentId, committed.extentId)
        assertEquals(request.extentSpec.expectedLength, committed.length)
    }

    private fun caseDefinition(
        scenario: ProviderScenario,
        persistedBefore: List<String>,
        persistedAfter: List<String>,
    ): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to 1,
        "caseId" to scenario.caseId,
        "evidenceSource" to "ANDROID_MEDIA_LAB",
        "policyId" to RecoveryPolicy.DEFAULT.policyId,
        "scenarioFamily" to scenario.profileId,
        "variant" to scenario.providerVariant,
        "expectedPhysicalAttempts" to scenario.expectedPhysicalAttempts,
        "expectedTerminals" to scenario.expectedTerminals,
        "expectedRefreshOperations" to scenario.expectedRefreshOperations,
        "expectedProviderWaits" to scenario.expectedProviderWaits,
        "expectedBindingRevisions" to scenario.expectedBindingRevisions,
        "persistedExtentIdsBefore" to persistedBefore,
        "persistedExtentIdsAfter" to persistedAfter,
        "bindingsEnabled" to true,
    )

    private fun readLabConfig(controlBaseUrl: String): LabConfig {
        val connection = (
            URL("$controlBaseUrl/__lab/config").openConnection() as HttpURLConnection
            ).apply {
            requestMethod = "GET"
            connectTimeout = CONTROL_TIMEOUT_MS
            readTimeout = CONTROL_TIMEOUT_MS
            useCaches = false
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "__lab/config returned ${connection.responseCode}"
            }
            val body = connection.inputStream.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            val json = JSONObject(body)
            return LabConfig(
                profileId = json.getString("profileId"),
                providerVariant = json.getString("providerVariant"),
                providerWallClockEpochMs = json.getLong("providerWallClockEpochMs"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun committedExtentIds(store: ExtentStore): List<String> =
        store.committedExtents().map { it.extentId.value }.sorted()

    private fun writeEvidence(
        api: Int,
        scenarioId: String,
        case: Map<String, Any?>,
        recoveryRecorder: RecoveryEvidenceRecorder,
        deliveryRecorder: DeliveryBindingEvidenceRecorder,
        fetchEvents: List<FetchEvent>,
    ) {
        val policyId = RecoveryPolicy.DEFAULT.policyId
        val artifacts = listOf(
            "failure-decision-events.json" to
                json(recoveryRecorder.failureArtifact(policyId)) + "\n",
            "recovery-budget-events.json" to
                json(recoveryRecorder.budgetArtifact(policyId)) + "\n",
            "delivery-binding-events.json" to json(deliveryRecorder.artifact()) + "\n",
            "fetch-events.jsonl" to
                fetchEvents.sortedBy(FetchEvent::eventSequence)
                    .joinToString(separator = "\n", postfix = "\n") { json(it.toArtifactMap()) },
            "case.json" to json(case) + "\n",
        )
        if (api < EVIDENCE_EXPORT_API) {
            // API 23 cannot use the modern additional-test-output staging path;
            // every artifact is still rendered (bounded, complete).
            return
        }
        val output = PlatformTestStorageRegistry.getInstance()
        for ((name, text) in artifacts) {
            output.openOutputFile("$EVIDENCE_DIR/$scenarioId/$name")
                .bufferedWriter()
                .use { it.write(text) }
        }
    }

    private fun cleanStoreRoot() {
        File(context.filesDir, "sponge").deleteRecursively()
    }

    private data class LabConfig(
        val profileId: String,
        val providerVariant: String,
        val providerWallClockEpochMs: Long,
    )

    private companion object {
        const val ORIGIN_BASE_URL_ARGUMENT = "spongetube.m2d.originBaseUrl"
        const val CONTROL_BASE_URL_ARGUMENT = "spongetube.m2d.controlBaseUrl"
        const val SCENARIO_ARGUMENT = "spongetube.m2d.scenario"
        const val RUN_ID = "m2-d-android-provider-recovery"
        const val SESSION_ID = "m2-d-provider-recovery"
        const val EVIDENCE_DIR = "m2-d-provider-evidence"
        const val EXTENT_ID = "m2d:f1:audio:1:1"
        const val FETCH_KEY = "fixture:F1/audio-main/f1-audio-1/segment-1-00001"
        const val F1_RESOURCE = "F1/segment-1-00001.m4s"
        const val LAB_RESOURCE_PREFIX = "/fixtures/"
        const val LAB_RESOURCE_PATH = LAB_RESOURCE_PREFIX + F1_RESOURCE
        const val INITIAL_GENERATION = "gen-1"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 5_000
        const val CONTROL_TIMEOUT_MS = 5_000
        const val TEST_TIMEOUT_MS = 60_000L
        const val EVIDENCE_EXPORT_API = 34

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
            is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
                json(key.toString()) + ":" + json(item)
            }
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { json(it) }
            else -> error("unsupported JSON value: $value")
        }
    }
}

/**
 * The four M2-D Android provider scenarios: one Media Lab profile/variant pair
 * and the anchors the exported case.json must carry.
 */
private enum class ProviderScenario(
    val scenarioId: String,
    val profileId: String,
    val providerVariant: String,
    val caseId: String,
    val expectedPhysicalAttempts: Int,
    val expectedTerminals: Map<String, Int>,
    val expectedRefreshOperations: Int,
    val expectedProviderWaits: List<Long>,
    val expectedBindingRevisions: List<String>,
) {
    N8_BARE_403(
        scenarioId = "N8_BARE_403",
        profileId = "N8",
        providerVariant = "HTTP_403_BARE",
        caseId = "n8-bare-403",
        expectedPhysicalAttempts = 1,
        expectedTerminals = mapOf("TERMINAL_FAILURE" to 1),
        expectedRefreshOperations = 0,
        expectedProviderWaits = emptyList(),
        expectedBindingRevisions = listOf("binding-1"),
    ),
    N9_429_DELAY_SECONDS(
        scenarioId = "N9_429_DELAY_SECONDS",
        profileId = "N9",
        providerVariant = "HTTP_429_RETRY_AFTER_DELAY_SECONDS",
        caseId = "n9-429-delay-seconds",
        expectedPhysicalAttempts = 2,
        expectedTerminals = mapOf("SUCCESS" to 1),
        expectedRefreshOperations = 0,
        expectedProviderWaits = listOf(2_000L),
        expectedBindingRevisions = listOf("binding-1", "binding-1"),
    ),
    N9_429_HTTP_DATE(
        scenarioId = "N9_429_HTTP_DATE",
        profileId = "N9",
        providerVariant = "HTTP_429_RETRY_AFTER_HTTP_DATE",
        caseId = "n9-429-http-date",
        expectedPhysicalAttempts = 2,
        expectedTerminals = mapOf("SUCCESS" to 1),
        expectedRefreshOperations = 0,
        expectedProviderWaits = listOf(2_000L),
        expectedBindingRevisions = listOf("binding-1", "binding-1"),
    ),
    N10_BINDING_EXPIRED_REFRESH(
        scenarioId = "N10_BINDING_EXPIRED_REFRESH",
        profileId = "N10",
        providerVariant = "BINDING_EXPIRY_REFRESH",
        caseId = "n10-binding-expired-refresh",
        expectedPhysicalAttempts = 2,
        expectedTerminals = mapOf("SUCCESS" to 1),
        expectedRefreshOperations = 1,
        expectedProviderWaits = emptyList(),
        expectedBindingRevisions = listOf("binding-1", "binding-2"),
    ),
    ;

    companion object {
        fun forScenarioId(scenarioId: String): ProviderScenario? =
            entries.firstOrNull { it.scenarioId == scenarioId }
    }
}
