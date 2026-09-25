package io.github.definitelystable.spongetube.playback.bridge

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.definitelystable.spongetube.core.engine.FetchKey
import io.github.definitelystable.spongetube.core.engine.PlaybackBridgeEventKind
import io.github.definitelystable.spongetube.core.engine.PlaybackTransportGate
import io.github.definitelystable.spongetube.core.engine.SpongeBridgeApi
import io.github.definitelystable.spongetube.testsupport.fixture.f1.F1FetchUnit
import io.github.definitelystable.spongetube.testsupport.fixture.f1.F1FixtureAssets
import io.github.definitelystable.spongetube.testsupport.playback.f1.F1PlaybackPlanFactory
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M1-E acceptance E1-E6 over the real ExtentStore, FetchBroker, ExoPlayer and
 * the Media Lab origin. Skipped unless the origin argument is supplied (CI
 * android-smoke M1-E step). Evidence is verified on the host by
 * scripts/ci/verify-m1-e-evidence.sh.
 */
@RunWith(AndroidJUnit4::class)
@UnstableApi
@OptIn(SpongeBridgeApi::class)
class PlaybackBridgeAndroidTest {
    private lateinit var context: Context
    private lateinit var fixture: F1FixtureAssets
    private lateinit var plans: F1PlaybackPlanFactory
    private lateinit var originBaseUrl: String

    @Before
    fun setUp() {
        val baseUrl = InstrumentationRegistry.getArguments()
            .getString(ORIGIN_BASE_URL_ARGUMENT)
            ?.trimEnd('/')
        assumeTrue(
            "M1-E bridge acceptance requires $ORIGIN_BASE_URL_ARGUMENT",
            !baseUrl.isNullOrBlank(),
        )
        originBaseUrl = checkNotNull(baseUrl)
        context = InstrumentationRegistry.getInstrumentation().targetContext
        fixture = F1FixtureAssets(context.assets)
        plans = F1PlaybackPlanFactory(fixture)
        cleanStoreRoot()
    }

    @After
    fun tearDown() {
        if (this::context.isInitialized) {
            cleanStoreRoot()
        }
    }

    @Test
    fun dataSourceHonorsMedia3OpenRangeContract() {
        val target = plans.catalog.unit("f1:video:0:1")
        val scenario = scenario("DATASOURCE_CONTRACT", plans.seed(10_000_000L))
        run(scenario) {
            val source = SpongeDataSource(runtime, AUTHORITY)
            val uri = SpongeUris.uri(AUTHORITY, target.resourceName)
            val buffer = ByteArray(16)

            val beyondEofLength = 10L
            val nearEnd = DataSpec.Builder()
                .setUri(uri)
                .setPosition(target.length - 2)
                .setLength(beyondEofLength)
                .build()
            assertEquals(beyondEofLength, source.open(nearEnd))
            assertEquals(2, source.read(buffer, 0, buffer.size))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, buffer.size))
            source.close()

            val atEofLength = 5L
            val atEof = DataSpec.Builder()
                .setUri(uri)
                .setPosition(target.length)
                .setLength(atEofLength)
                .build()
            assertEquals(atEofLength, source.open(atEof))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, buffer.size))
            source.close()

            val pastEof = DataSpec.Builder()
                .setUri(uri)
                .setPosition(target.length + 1)
                .setLength(1)
                .build()
            val failure = try {
                source.open(pastEof)
                null
            } catch (error: IOException) {
                error
            } finally {
                source.close()
            }
            assertTrue(
                "position > EOF must use Media3 position-out-of-range semantics",
                DataSourceException.isCausedByPositionOutOfRange(checkNotNull(failure)),
            )
        }
    }

    /** E1 / M1-ACC-09: cached seeks inside S120 need no remote request. */
    @Test
    fun e1CachedSeekInsidePublishedCoverageIsFullyLocal() {
        val scenario = scenario("E1", plans.seed(120_000_000L))
        run(scenario) {
            startPlayer()
            awaitPosition(2_000)
            marker(SEEK_ISSUED)
            seekTo(30_000)
            awaitPosition(31_000)
            seekTo(60_000)
            awaitPosition(61_000)
            // Paused buffering stops at ~110 s, still inside S120.
            quiesce()
            marker(SEEK_SETTLED)
        }

        assertTrue(scenario.events(PlaybackBridgeEventKind.LOCAL_SERVE).isNotEmpty())
        assertTrue(scenario.events(PlaybackBridgeEventKind.MISS).isEmpty())
        assertTrue(scenario.events(PlaybackBridgeEventKind.JOIN).isEmpty())
        assertTrue(scenario.fetchEvents.isEmpty())
    }

    /** E2 / M1-ACC-10: seek into missing coverage fetches only via FetchBroker. */
    @Test
    fun e2SeekIntoMissingCoverageFetchesThroughBroker() {
        val scenario = scenario("E2", plans.seed(30_000_000L))
        run(scenario) {
            startPlayer()
            awaitPosition(2_000)
            quiesce()
            marker(SEEK_TO_MISSING_ISSUED)
            resume()
            seekTo(90_000)
            awaitPosition(91_000)
            marker(SEEK_TO_MISSING_SETTLED)
            quiesce()
        }

        val issued = scenario.events(PlaybackBridgeEventKind.HARNESS_MARKER)
            .single { it.markerName == "SEEK_TO_MISSING_ISSUED" }
        val settled = scenario.events(PlaybackBridgeEventKind.HARNESS_MARKER)
            .single { it.markerName == "SEEK_TO_MISSING_SETTLED" }
        val misses = scenario.events(PlaybackBridgeEventKind.MISS)
            .filter {
                issued.eventSequence < it.eventSequence &&
                    it.eventSequence < settled.eventSequence
            }
        assertTrue("seek window must contain a bridge miss", misses.isNotEmpty())
        assertTrue(misses.none { it.extentId in scenario.seededExtentIds })

        val completed = scenario.fetchRows("OWNER_COMPLETED")
            .map { it["fetchId"] }
            .toSet()
        assertTrue(misses.all { it.fetchId in completed })

        val low = checkNotNull(issued.fetchEventSequenceWatermark)
        val high = checkNotNull(settled.fetchEventSequenceWatermark)
        val missFetchIds = misses.mapNotNull { it.fetchId }.toSet()
        val attemptsInWindow = scenario.fetchRows("ATTEMPT_STARTED").filter { row ->
            val sequence = (row["eventSequence"] as Number).toLong()
            sequence >= low && sequence < high && row["fetchId"] in missFetchIds
        }
        assertTrue("seek miss must own a broker attempt in the seek window", attemptsInWindow.isNotEmpty())

        assertTrue(
            "seek miss must become local coverage before seek settles",
            misses.any { miss ->
                scenario.events(PlaybackBridgeEventKind.LOCAL_SERVE).any { local ->
                    local.readId == miss.readId &&
                        local.extentId == miss.extentId &&
                        miss.eventSequence < local.eventSequence &&
                        local.eventSequence < settled.eventSequence
                }
            },
        )
    }

    /** E3 / M1-ACC-08: playback joins an in-flight RESERVE fetch. */
    @Test
    fun e3PlaybackJoinsInFlightReserveFetch() {
        val target = plans.catalog.unit("f1:video:0:4")
        val release = CompletableDeferred<Unit>()
        val gate = PlaybackTransportGate { fetchKey, _ ->
            if (fetchKey.value == target.fetchKeyValue) {
                release.await()
            }
        }
        val scenario = scenario("E3", plans.seed(30_000_000L), gate = gate)
        var reserveFetchId: String? = null
        run(scenario) {
            val reserve = runBlocking {
                runtime.acquireReserve(FetchKey(target.fetchKeyValue), "harness-reserve")
            }
            reserveFetchId = reserve.fetchId
            // M2-C: the broker consumer is the RecoveryChain; the verifier
            // joins the reserve lease to its owner through this fetchId.
            extra["reserveFetchId"] = reserve.fetchId
            extra["reserveRecoveryChainId"] = reserve.recoveryChainId
            startPlayer()
            awaitCondition(60_000, "bridge JOIN on ${target.extentId}") {
                events(PlaybackBridgeEventKind.JOIN).any { it.extentId == target.extentId }
            }
            release.complete(Unit)
            assertEquals("SUCCESS", runBlocking { reserve.awaitOutcome() })
            reserve.close()
            awaitCondition(60_000, "local serve of ${target.extentId}") {
                events(PlaybackBridgeEventKind.LOCAL_SERVE).any { it.extentId == target.extentId }
            }
            awaitPosition(2_000)
            quiesce()
        }

        val join = scenario.events(PlaybackBridgeEventKind.JOIN)
            .single { it.extentId == target.extentId }
        assertEquals(reserveFetchId, join.fetchId)
        assertEquals(
            1,
            scenario.fetchRows("ATTEMPT_STARTED").count { it["fetchId"] == reserveFetchId },
        )
        assertEquals(
            1,
            scenario.fetchRows("PRIORITY_RAISED").count { it["fetchId"] == reserveFetchId },
        )
    }

    /** E4: ranged FetchUnits inside one segment; reads cross extent boundaries. */
    @Test
    fun e4RangedExtentsPlayAcrossBoundaries() {
        val split = "f1:video:0:2"
        val scenario = scenario(
            "E4",
            plans.seed(10_000_000L),
            splitExtentIds = setOf(split),
        )
        run(scenario) {
            extra["splitExtentId"] = split
            startPlayer()
            awaitPosition(1_000)
            quiesce()
            resume()
            seekTo(15_000)
            awaitPosition(21_000)
            quiesce()
        }

        val partMisses = scenario.events(PlaybackBridgeEventKind.MISS)
            .filter { it.extentId?.startsWith("$split@") == true }
        assertEquals(3, partMisses.size)
        val servedByRead = scenario.events(PlaybackBridgeEventKind.LOCAL_SERVE)
            .filter { it.extentId?.startsWith("$split@") == true }
            .groupBy { it.readId }
        assertTrue(servedByRead.values.any { it.size == 3 })
    }

    /** E5: S30_MISSING_INIT fetches only the missing init; segments stay local. */
    @Test
    fun e5MissingInitFetchesOnlyDependency() {
        val seeded = plans.seed(30_000_000L).filterNot { it.extentId == "f1:video:0:init" }
        val scenario = scenario("E5", seeded)
        run(scenario) {
            startPlayer()
            awaitPosition(5_000)
            quiesce()
        }

        val initMisses = scenario.events(PlaybackBridgeEventKind.MISS)
            .filter { it.extentId == "f1:video:0:init" }
        assertEquals(1, initMisses.size)
        assertTrue(
            scenario.events(PlaybackBridgeEventKind.LOCAL_SERVE)
                .any { it.extentId == "f1:video:0:1" },
        )
        assertTrue(
            scenario.events(PlaybackBridgeEventKind.MISS)
                .none { it.extentId in scenario.seededExtentIds },
        )
    }

    /** E6: player.release() during a blocked miss finishes promptly and cleanly. */
    @Test
    fun e6ReleaseDuringBlockedMissUnblocksLoaderAndClosesStore() {
        val gate = PlaybackTransportGate { _, _ -> awaitCancellation() }
        val scenario = scenario("E6", plans.seed(10_000_000L), gate = gate)
        run(scenario) {
            startPlayer()
            awaitCondition(60_000, "a blocked bridge miss") {
                events(PlaybackBridgeEventKind.MISS).isNotEmpty()
            }
            val blockedReads = events(PlaybackBridgeEventKind.MISS).mapNotNull { it.readId }.toSet()
            val started = SystemClock.elapsedRealtime()
            val releaseMs = releasePlayer()
            awaitCondition(RELEASE_BUDGET_MS, "blocked reads closed after release") {
                val closed = events(PlaybackBridgeEventKind.CLOSE).mapNotNull { it.readId }.toSet()
                closed.containsAll(blockedReads)
            }
            val closeMs = SystemClock.elapsedRealtime() - started
            awaitCondition(RELEASE_BUDGET_MS, "blocked owners cancelled") {
                val registered = fetchRows("OWNER_REGISTERED").map { it["fetchId"] }.toSet()
                val cancelled = fetchRows("OWNER_CANCELLED").map { it["fetchId"] }.toSet()
                cancelled.containsAll(registered)
            }
            extra["releaseMs"] = releaseMs
            extra["blockedReadsClosedMs"] = closeMs
            assertTrue("release took ${releaseMs}ms", releaseMs < RELEASE_BUDGET_MS)
            assertTrue("reads closed after ${closeMs}ms", closeMs < RELEASE_BUDGET_MS)
        }

        assertTrue(scenario.fetchRows("ATTEMPT_COMPLETED").isEmpty())
        assertTrue(
            scenario.fetchRows("OWNER_CANCELLED")
                .all { it["outcome"] == "CANCELLED_NO_CONSUMERS" },
        )
    }

    private fun scenario(
        caseId: String,
        seeded: List<F1FetchUnit>,
        splitExtentIds: Set<String> = emptySet(),
        gate: PlaybackTransportGate? = null,
    ): BridgeScenario =
        BridgeScenario(
            context = context,
            fixture = fixture,
            caseId = caseId,
            seeded = seeded,
            plan = plans.plan(splitExtentIds),
            originBaseUrl = originBaseUrl,
            gate = gate,
        )

    /** Runs the body; on success exports evidence (store closed), else aborts. */
    private fun run(scenario: BridgeScenario, body: BridgeScenario.() -> Unit) {
        try {
            scenario.body()
        } catch (error: Throwable) {
            scenario.abort()
            throw error
        }
        scenario.finish()
        assertTrue(
            "${scenario.caseId} player errors: ${scenario.playerErrors}",
            scenario.playerErrors.isEmpty(),
        )
    }

    private fun cleanStoreRoot() {
        File(context.filesDir, "sponge").deleteRecursively()
    }

    private companion object {
        const val ORIGIN_BASE_URL_ARGUMENT = "spongetube.m1e.originBaseUrl"
        const val SEEK_ISSUED = "SEEK_ISSUED"
        const val SEEK_SETTLED = "SEEK_SETTLED"
        const val SEEK_TO_MISSING_ISSUED = "SEEK_TO_MISSING_ISSUED"
        const val SEEK_TO_MISSING_SETTLED = "SEEK_TO_MISSING_SETTLED"
        const val RELEASE_BUDGET_MS = 1_000L
    }
}
