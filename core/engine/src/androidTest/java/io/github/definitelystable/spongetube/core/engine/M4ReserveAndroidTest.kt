package io.github.definitelystable.spongetube.core.engine

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryBudgetEventKind
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryEvidenceRecorder
import io.github.definitelystable.spongetube.core.storage.ExtentStore
import io.github.definitelystable.spongetube.testsupport.fixture.f1.F1FixtureAssets
import io.github.definitelystable.spongetube.testsupport.fixture.f1.F1FixtureCatalog
import io.github.definitelystable.spongetube.testsupport.playback.f1.F1PlaybackPlanFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M4-A1b N0: real F1 MPD / HTTP 206 Media Lab / ExtentStore, not a fake origin.
 *
 * At the first broker-owned transport attempt, a test-only admission barrier
 * lets the playback read join the RESERVE RecoveryChain before HTTP starts.
 * The barrier is not an emulation of N4 network loss; a separate N4 test must
 * demonstrate physical route/body loss.
 *
 * The host independently checks Media Lab requests and reconstructs coverage
 * from exported SQLite + immutable bytes (never from this test's assertions).
 */
@RunWith(AndroidJUnit4::class)
@OptIn(SpongeBridgeApi::class)
class M4ReserveAndroidTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        cleanStore()
    }

    @After
    fun tearDown() {
        cleanStore()
    }

    @Test
    fun realF1ReserveJoinsPlaybackAndPersistsBothRequiredTracks() = runBlocking {
        val base = InstrumentationRegistry.getArguments()
            .getString(ORIGIN_BASE_URL_ARGUMENT)
            ?.trimEnd('/')
        assumeTrue(
            "M4-A1b evidence requires $ORIGIN_BASE_URL_ARGUMENT",
            !base.isNullOrBlank(),
        )

        val fixture = F1FixtureAssets(context.assets)
        val factory = F1PlaybackPlanFactory(fixture)
        val catalog = factory.catalog
        val audioInit = catalog.units.single {
            it.trackId == F1FixtureCatalog.AUDIO_TRACK && it.isInit
        }
        val audioFirst = catalog.units.single {
            it.trackId == F1FixtureCatalog.AUDIO_TRACK && it.mediaStartUs == 0L
        }
        val videoInit = catalog.units.single {
            it.trackId == F1FixtureCatalog.VIDEO_TRACK && it.isInit
        }
        val videoFirst = catalog.units.single {
            it.trackId == F1FixtureCatalog.VIDEO_TRACK && it.mediaStartUs == 0L
        }
        val expectedUnits = listOf(audioInit, audioFirst, videoInit, videoFirst)
        val expectedKeys = expectedUnits.map { it.fetchKeyValue }
        val expectedPlayableEndUs = minOf(
            checkNotNull(audioFirst.mediaEndUs),
            checkNotNull(videoFirst.mediaEndUs),
        )
        assertTrue(expectedPlayableEndUs >= TARGET_RESERVE_US)

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fetchEvents = CopyOnWriteArrayList<Map<String, Any?>>()
        val recovery = RecoveryEvidenceRecorder(RUN_ID, SESSION_ID)
        val requirements = PlaybackRequirementSet(
            F1FixtureCatalog.ASSET,
            F1FixtureCatalog.REQUIRED_REPRESENTATIONS,
        )
        val store = ExtentStore.open(context)
        val runtime = PlaybackBridgeRuntime.openInternal(
            store = store,
            plan = factory.plan(),
            transport = FixtureTransportSession(checkNotNull(base)),
            config = PlaybackBridgeConfig(
                sessionId = SESSION_ID,
                transportGate = PlaybackTransportGate { key, attempt ->
                    if (key.value == audioInit.fetchKeyValue) {
                        check(attempt == 1)
                        entered.complete(Unit)
                        release.await()
                    }
                },
            ),
            fetchEventListener = PlaybackFetchEvidenceListener { fetchEvents += it },
            recoveryEvidence = recovery,
        )
        var coverageArtifact: Map<String, Any?>? = null
        var summary: JSONObject? = null
        try {
            assertEquals(
                0L,
                runtime.coverageIndex.snapshot(requirements, 0).durableReserveUs,
            )
            val reserve = async(Dispatchers.Default) {
                runtime.fillReserveOnce(
                    requirements = requirements,
                    playheadUs = 0,
                    targetReserveUs = TARGET_RESERVE_US,
                    maxFetchUnits = 4,
                )
            }
            withTimeout(TIMEOUT_MS) { entered.await() }
            // No network request is opened by the test-only admission barrier.
            val read = async(Dispatchers.IO) {
                runtime.newReadSession().use { session ->
                    session.open(
                        audioInit.resourceName,
                        0,
                        PlaybackReadSession.LENGTH_UNSET,
                    )
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = session.read(buffer, 0, buffer.size)
                        if (count == PlaybackReadSession.END_OF_INPUT) break
                        check(count > 0)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }
            try {
                withTimeout(TIMEOUT_MS) {
                    while (
                        recovery.budgetEvents().count {
                            it.fetchKey == audioInit.fetchKeyValue &&
                                it.kind == RecoveryBudgetEventKind.CONSUMER_JOINED
                        } < 2
                    ) {
                        delay(10)
                    }
                }
                assertTrue(
                    recovery.budgetEvents().any {
                        it.fetchKey == audioInit.fetchKeyValue &&
                            it.kind == RecoveryBudgetEventKind.PRIORITY_RAISED
                    },
                )
            } finally {
                // Always unblock the physical owner, including when an
                // assertion fails; runtime shutdown owns subsequent cleanup.
                release.complete(Unit)
            }
            assertArrayEquals(
                fixture.verifiedBytes(audioInit.resourcePath),
                withTimeout(TIMEOUT_MS) { read.await() },
            )

            val result = withTimeout(TIMEOUT_MS) { reserve.await() }
            assertEquals(ReserveExecutionStop.TARGET_REACHED, result.stop)
            assertEquals(0L, result.initialReserveUs)
            assertEquals(expectedPlayableEndUs, result.finalReserveUs)
            assertEquals(expectedKeys, result.requestedFetchKeys.map { it.value })
            assertFalse(result.planTruncated)
            assertEquals(null, result.terminalFetchOutcome)

            val replay = withTimeout(TIMEOUT_MS) {
                runtime.fillReserveOnce(requirements, 0, TARGET_RESERVE_US, 4)
            }
            assertEquals(ReserveExecutionStop.TARGET_REACHED, replay.stop)
            assertTrue(replay.requestedFetchKeys.isEmpty())
            assertEquals(expectedPlayableEndUs, replay.initialReserveUs)

            val committed = store.committedExtents()
            assertEquals(expectedUnits.map { it.extentId }.toSet(),
                committed.map { it.extentId.value }.toSet())
            val snapshot = runtime.coverageIndex.snapshot(requirements, 0)
            assertEquals(expectedPlayableEndUs, snapshot.durableReserveUs)
            coverageArtifact = CoverageEvidenceSnapshot(
                eventSequence = 0,
                eventElapsedRealtimeNs = 0,
                sessionId = SESSION_ID,
                coverage = snapshot,
                playerBufferedAheadUs = null,
            ).toArtifactMap()

            summary = JSONObject()
                .put("schemaVersion", 1)
                .put("sessionId", SESSION_ID)
                .put("mediaAssetId", F1FixtureCatalog.ASSET.value)
                .put("playheadUs", 0)
                .put("targetReserveUs", TARGET_RESERVE_US)
                .put("initialReserveUs", result.initialReserveUs)
                .put("finalReserveUs", result.finalReserveUs)
                .put("expectedPlayableEndUs", expectedPlayableEndUs)
                .put("requestedFetchKeys", JSONArray(expectedKeys))
                .put("replayRequestedFetchKeys", JSONArray(emptyList<String>()))
                .put("playbackJoinedFetchKey", audioInit.fetchKeyValue)
                .put("committedExtentCount", committed.size)
        } finally {
            release.complete(Unit)
            runtime.shutdown()
            store.close()
        }

        // Prove that the reserve is durable after the original Room/bridge
        // session closes, without opening any origin request on reopen.
        val reopened = ExtentStore.open(context)
        try {
            val afterRestart = CoverageIndex(reopened)
            afterRestart.refresh()
            assertEquals(
                expectedPlayableEndUs,
                afterRestart.snapshot(requirements, 0).durableReserveUs,
            )
        } finally {
            reopened.close()
        }
        writeEvidence(
            checkNotNull(coverageArtifact),
            checkNotNull(summary),
            fetchEvents.toList(),
        )
    }

    private fun writeEvidence(
        runtimeCoverage: Map<String, Any?>,
        summary: JSONObject,
        fetchEvents: List<Map<String, Any?>>,
    ) {
        val output = PlatformTestStorageRegistry.getInstance()
        val storeRoot = File(context.filesDir, "sponge")
        check(storeRoot.isDirectory) { "missing persistent Sponge store" }
        storeRoot.walkTopDown().filter(File::isFile).forEach { source ->
            val relative = source.relativeTo(storeRoot).invariantSeparatorsPath
            output.openOutputFile("m4-a1b-evidence/N0/storage/$relative")
                .use { target -> source.inputStream().use { it.copyTo(target) } }
        }
        output.openOutputFile("m4-a1b-evidence/N0/runtime-coverage.json")
            .bufferedWriter().use { it.write(JSONObject(runtimeCoverage).toString(2) + "\n") }
        output.openOutputFile("m4-a1b-evidence/N0/reserve-summary.json")
            .bufferedWriter().use { it.write(summary.toString(2) + "\n") }
        output.openOutputFile("m4-a1b-evidence/N0/fetch-events.jsonl")
            .bufferedWriter().use { writer ->
                fetchEvents.forEach { event ->
                    writer.write(JSONObject(event).toString())
                    writer.newLine()
                }
            }
    }

    private fun cleanStore() {
        File(context.filesDir, "sponge").deleteRecursively()
    }

    private companion object {
        const val RUN_ID = "m4-a1b-n0"
        const val SESSION_ID = "m4-a1b-n0"
        const val TARGET_RESERVE_US = 8_000_000L
        const val TIMEOUT_MS = 60_000L
        const val ORIGIN_BASE_URL_ARGUMENT = "spongetube.m4.originBaseUrl"
    }
}
