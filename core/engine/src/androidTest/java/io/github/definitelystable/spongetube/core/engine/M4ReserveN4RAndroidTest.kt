package io.github.definitelystable.spongetube.core.engine

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryAttemptGate
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryAttemptPermit
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryBudgetEventKind
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryEvidenceRecorder
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryPermitReason
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryPolicy
import io.github.definitelystable.spongetube.core.engine.recovery.toArtifactMap
import io.github.definitelystable.spongetube.core.storage.ExtentStore
import io.github.definitelystable.spongetube.testsupport.fixture.f1.F1FixtureAssets
import io.github.definitelystable.spongetube.testsupport.fixture.f1.F1FixtureCatalog
import io.github.definitelystable.spongetube.testsupport.playback.f1.F1PlaybackPlanFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M4-A1b N4R: real HTTP body stalls in Media Lab, then bounded recovery.
 *
 * The physical Media Lab gate is closed before any demand starts. An
 * admission barrier (separate from the server gate) guarantees that
 * PLAYBACK joins RESERVE's *RecoveryChain* on the first audio init.
 * The first two physical requests time out while the server body is
 * blocked; opening the server gate at the third RecoveryAttemptGate
 * permit lets this SAME chain recover, without a second logical retry
 * owner or exposure of in-flight bytes.
 *
 * Exported evidence is checked by an independent host oracle against
 * Media Lab gate events, physical HTTP requests, Room SQLite and SHA-256
 * of the actual published F1 bytes. It is not a VPN/route-switch claim.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(SpongeBridgeApi::class)
class M4ReserveN4RAndroidTest {
    private lateinit var context: Context

    @Before
    fun before() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        cleanStore()
    }

    @After
    fun after() {
        cleanStore()
    }

    @Test
    fun stalledHttpBodyRecoversOneChainAndRetainsDurableAudioVideoReserve() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val origin = args.getString(ORIGIN_ARGUMENT)?.trimEnd('/')
        val control = args.getString(CONTROL_ARGUMENT)?.trimEnd('/')
        assumeTrue(
            "N4R requires $ORIGIN_ARGUMENT and $CONTROL_ARGUMENT",
            !origin.isNullOrBlank() && !control.isNullOrBlank(),
        )
        val baseUrl = checkNotNull(origin)
        val controlUrl = checkNotNull(control)
        withContext(Dispatchers.IO) {
            gateCommand(controlUrl, "open", "m4-n4r-reset")
            gateCommand(controlUrl, "close", "m4-n4r-close")
        }

        val fixture = F1FixtureAssets(context.assets)
        val factory = F1PlaybackPlanFactory(fixture)
        val catalog = factory.catalog
        val audioInit = catalog.units.single {
            it.trackId == F1FixtureCatalog.AUDIO_TRACK && it.isInit
        }
        val audioMedia = catalog.units.single {
            it.trackId == F1FixtureCatalog.AUDIO_TRACK && it.mediaStartUs == 0L
        }
        val videoInit = catalog.units.single {
            it.trackId == F1FixtureCatalog.VIDEO_TRACK && it.isInit
        }
        val videoMedia = catalog.units.single {
            it.trackId == F1FixtureCatalog.VIDEO_TRACK && it.mediaStartUs == 0L
        }
        val expectedUnits = listOf(audioInit, audioMedia, videoInit, videoMedia)
        val expectedKeys = expectedUnits.map { it.fetchKeyValue }
        val expectedEndUs = minOf(
            checkNotNull(audioMedia.mediaEndUs),
            checkNotNull(videoMedia.mediaEndUs),
        )
        assertTrue(expectedEndUs >= TARGET_RESERVE_US)

        val permitCount = AtomicInteger()
        val firstAttemptEntered = CompletableDeferred<Unit>()
        val admitFirstAttempt = CompletableDeferred<Unit>()
        val recorder = RecoveryEvidenceRecorder(SESSION_ID, SESSION_ID)
        val fetchEvents = CopyOnWriteArrayList<Map<String, Any?>>()
        val requirements = PlaybackRequirementSet(
            F1FixtureCatalog.ASSET,
            F1FixtureCatalog.REQUIRED_REPRESENTATIONS,
        )
        val store = ExtentStore.open(context)
        val runtime = PlaybackBridgeRuntime.openInternal(
            store = store,
            plan = factory.plan(),
            transport = FixtureTransportSession(baseUrl),
            config = PlaybackBridgeConfig(
                sessionId = SESSION_ID,
                connectTimeoutMs = 10_000,
                readTimeoutMs = 1_500,
                transportGate = PlaybackTransportGate { key, attempt ->
                    if (key.value == audioInit.fetchKeyValue && permitCount.get() == 1) {
                        check(attempt == 1)
                        firstAttemptEntered.complete(Unit)
                        admitFirstAttempt.await()
                    }
                },
            ),
            fetchEventListener = PlaybackFetchEvidenceListener { fetchEvents += it },
            recoveryEvidence = recorder,
            attemptGate = RecoveryAttemptGate {
                val ordinal = permitCount.incrementAndGet()
                if (ordinal == OPEN_BEFORE_PERMIT) {
                    withContext(Dispatchers.IO) {
                        gateCommand(controlUrl, "open", "m4-n4r-restore")
                    }
                }
                RecoveryAttemptPermit(
                    routeEpoch = null,
                    reason = RecoveryPermitReason("HARNESS_BODY_GATE"),
                )
            },
        )
        var resultSnapshot: Map<String, Any?>? = null
        var resultSummary: JSONObject? = null
        try {
            assertEquals(0L, runtime.coverageIndex.snapshot(requirements, 0).durableReserveUs)
            val reserve = async(Dispatchers.Default) {
                runtime.fillReserveOnce(requirements, 0, TARGET_RESERVE_US, 4)
            }
            withTimeout(TEST_TIMEOUT_MS) { firstAttemptEntered.await() }
            val read = async(Dispatchers.IO) {
                runtime.newReadSession().use { session ->
                    session.open(
                        audioInit.resourceName,
                        0L,
                        PlaybackReadSession.LENGTH_UNSET,
                    )
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = session.read(buffer, 0, buffer.size)
                        if (count == PlaybackReadSession.END_OF_INPUT) break
                        check(count > 0)
                        bytes.write(buffer, 0, count)
                    }
                    bytes.toByteArray()
                }
            }

            try {
                withTimeout(TEST_TIMEOUT_MS) {
                    while (
                        recorder.budgetEvents().count {
                            it.fetchKey == audioInit.fetchKeyValue &&
                                it.kind == RecoveryBudgetEventKind.CONSUMER_JOINED
                        } != 2
                    ) {
                        delay(10)
                    }
                }
                assertTrue(
                    recorder.budgetEvents().any {
                        it.fetchKey == audioInit.fetchKeyValue &&
                            it.kind == RecoveryBudgetEventKind.PRIORITY_RAISED
                    },
                )
            } finally {
                admitFirstAttempt.complete(Unit)
            }

            // This condition is observed while the physical HTTP response
            // body is blocked by the Media Lab server, not the client hook.
            val blocked = withTimeout(TEST_TIMEOUT_MS) {
                var state: JSONObject
                do {
                    state = withContext(Dispatchers.IO) { gateState(controlUrl) }
                    if (state.getString("state") == "CLOSED" &&
                        state.getInt("activeBlockedRequests") > 0) {
                        break
                    }
                    delay(20)
                } while (true)
                state
            }
            assertEquals("CLOSED", blocked.getString("state"))
            assertEquals(0L, runtime.coverageIndex.snapshot(requirements, 0).durableReserveUs)
            assertTrue(store.committedExtents().isEmpty())

            assertArrayEquals(
                fixture.verifiedBytes(audioInit.resourcePath),
                withTimeout(TEST_TIMEOUT_MS) { read.await() },
            )
            val firstPass = withTimeout(TEST_TIMEOUT_MS) { reserve.await() }
            assertEquals(ReserveExecutionStop.TARGET_REACHED, firstPass.stop)
            assertEquals(0L, firstPass.initialReserveUs)
            assertEquals(expectedEndUs, firstPass.finalReserveUs)
            assertEquals(expectedKeys, firstPass.requestedFetchKeys.map { it.value })
            assertEquals(null, firstPass.terminalFetchOutcome)

            assertEquals(
                listOf("READ_TIMEOUT", "READ_TIMEOUT"),
                recorder.failures().map { it.observation.toArtifactMap()["kind"] },
            )
            assertEquals(EXPECTED_PHYSICAL_ATTEMPTS, permitCount.get())
            assertEquals(
                EXPECTED_PHYSICAL_ATTEMPTS,
                fetchEvents.count { it["event"] == "ATTEMPT_STARTED" },
            )

            val replay = runtime.fillReserveOnce(
                requirements,
                0L,
                TARGET_RESERVE_US,
                4,
            )
            assertEquals(ReserveExecutionStop.TARGET_REACHED, replay.stop)
            assertTrue(replay.requestedFetchKeys.isEmpty())
            assertEquals(expectedEndUs, replay.initialReserveUs)
            assertEquals(expectedEndUs, replay.finalReserveUs)

            assertEquals(
                expectedUnits.map { it.extentId }.toSet(),
                store.committedExtents().map { it.extentId.value }.toSet(),
            )
            val coverage = runtime.coverageIndex.snapshot(requirements, 0L)
            assertEquals(expectedEndUs, coverage.durableReserveUs)
            resultSnapshot = CoverageEvidenceSnapshot(
                eventSequence = 0,
                eventElapsedRealtimeNs = 0,
                sessionId = SESSION_ID,
                coverage = coverage,
                playerBufferedAheadUs = null,
            ).toArtifactMap()
            resultSummary = JSONObject()
                .put("schemaVersion", 1)
                .put("sessionId", SESSION_ID)
                .put("mediaAssetId", F1FixtureCatalog.ASSET.value)
                .put("targetReserveUs", TARGET_RESERVE_US)
                .put("initialReserveUs", firstPass.initialReserveUs)
                .put("blockedReserveUs", 0)
                .put("finalReserveUs", firstPass.finalReserveUs)
                .put("expectedPlayableEndUs", expectedEndUs)
                .put("physicalAttempts", permitCount.get())
                .put("readTimeoutCount", recorder.failures().size)
                .put("requestedFetchKeys", JSONArray(expectedKeys))
                .put("replayRequestedFetchKeys", JSONArray(emptyList<String>()))
                .put("playbackJoinedFetchKey", audioInit.fetchKeyValue)
                .put("committedExtentCount", store.committedExtents().size)
                .put("blockedBodyObserved", true)
        } finally {
            admitFirstAttempt.complete(Unit)
            runtime.shutdown()
            store.close()
            withContext(Dispatchers.IO) {
                runCatching { gateCommand(controlUrl, "open", "m4-n4r-cleanup") }
            }
        }

        val reopened = ExtentStore.open(context)
        try {
            val index = CoverageIndex(reopened)
            index.refresh()
            assertEquals(expectedEndUs, index.snapshot(requirements, 0).durableReserveUs)
        } finally {
            reopened.close()
        }

        writeEvidence(
            checkNotNull(resultSnapshot),
            checkNotNull(resultSummary),
            fetchEvents.toList(),
            recorder.budgetArtifact(RecoveryPolicy.DEFAULT_POLICY_ID),
            recorder.failureArtifact(RecoveryPolicy.DEFAULT_POLICY_ID),
        )
    }

    private fun gateCommand(base: String, action: String, id: String) {
        val conn = URL("$base/__lab/gate/media/$action")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.useCaches = false
            conn.setRequestProperty("X-Sponge-Gate-Command", id)
            check(conn.responseCode == HttpURLConnection.HTTP_OK) {
                "gate $action returned ${conn.responseCode}"
            }
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun gateState(base: String): JSONObject {
        val conn = URL("$base/__lab/gate/media")
            .openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.useCaches = false
            check(conn.responseCode == HttpURLConnection.HTTP_OK)
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun writeEvidence(
        coverage: Map<String, Any?>,
        summary: JSONObject,
        fetchEvents: List<Map<String, Any?>>,
        budget: Map<String, Any?>,
        failures: Map<String, Any?>,
    ) {
        val output = PlatformTestStorageRegistry.getInstance()
        val root = File(context.filesDir, "sponge")
        check(root.isDirectory)
        root.walkTopDown().filter(File::isFile).forEach { source ->
            val relative = source.relativeTo(root).invariantSeparatorsPath
            output.openOutputFile("m4-a1b-n4r-evidence/storage/$relative")
                .use { destination ->
                    source.inputStream().use { it.copyTo(destination) }
                }
        }
        fun write(name: String, body: String) {
            output.openOutputFile("m4-a1b-n4r-evidence/$name")
                .bufferedWriter().use { it.write(body + "\n") }
        }
        write("runtime-coverage.json", JSONObject(coverage).toString(2))
        write("reserve-summary.json", summary.toString(2))
        write("recovery-budget.json", JSONObject(budget).toString(2))
        write("failure-decision-events.json", JSONObject(failures).toString(2))
        output.openOutputFile("m4-a1b-n4r-evidence/fetch-events.jsonl")
            .bufferedWriter().use { writer ->
                fetchEvents.forEach {
                    writer.write(JSONObject(it).toString())
                    writer.newLine()
                }
            }
    }

    private fun cleanStore() {
        File(context.filesDir, "sponge").deleteRecursively()
    }

    private companion object {
        const val SESSION_ID = "m4-a1b-n4r"
        const val ORIGIN_ARGUMENT = "spongetube.m4n4r.originBaseUrl"
        const val CONTROL_ARGUMENT = "spongetube.m4n4r.controlBaseUrl"
        const val TARGET_RESERVE_US = 8_000_000L
        const val TEST_TIMEOUT_MS = 90_000L
        const val OPEN_BEFORE_PERMIT = 3
        const val EXPECTED_PHYSICAL_ATTEMPTS = 6
    }
}
