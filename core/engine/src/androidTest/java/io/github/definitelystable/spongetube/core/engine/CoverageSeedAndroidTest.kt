package io.github.definitelystable.spongetube.core.engine

import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import io.github.definitelystable.spongetube.core.storage.ExtentIntegrityException
import io.github.definitelystable.spongetube.core.storage.ExtentStore
import io.github.definitelystable.spongetube.testsupport.fixture.f1.F1FetchUnit
import io.github.definitelystable.spongetube.testsupport.fixture.f1.F1FixtureAssets
import io.github.definitelystable.spongetube.testsupport.fixture.f1.F1FixtureCatalog
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoverageSeedAndroidTest {
    private lateinit var context: Context
    private lateinit var fixture: F1FixtureAssets

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        fixture = F1FixtureAssets(context.assets)
        cleanStoreRoot()
    }

    @After
    fun tearDown() {
        cleanStoreRoot()
    }

    @Test
    fun canonicalSeedsPublishThroughRealStoreAndExportOracleEvidence() =
        runBlocking {
            val catalog = fixture.catalog().units.map(::SeedUnit)

            for (case in canonicalCases(catalog)) {
                cleanStoreRoot()

                val store = ExtentStore.open(context)
                val runtimeArtifact: Map<String, Any?>
                try {
                    publishSeed(store, case)

                    val index = CoverageIndex(store)
                    val refresh = index.refresh()
                    assertTrue(
                        case.seedId + " loaded extents",
                        refresh.loadedExtentCount > 0,
                    )

                    val snapshot = index.snapshot(
                        requirements = PlaybackRequirementSet(
                            mediaAssetId = ASSET,
                            requiredRepresentations = REQUIRED,
                        ),
                        playheadUs = 0,
                    )

                    assertRuntime(case, snapshot)

                    if (case.seedId == "S30_PARTIAL_TAIL") {
                        assertFalse(
                            store.committedExtents().any {
                                it.extentId.value == "f1:video:0:3"
                            },
                        )
                    }

                    runtimeArtifact = CoverageEvidenceSnapshot(
                        eventSequence = 0,
                        eventElapsedRealtimeNs = 0,
                        sessionId = "m1-c-" + case.seedId,
                        coverage = snapshot,
                        playerBufferedAheadUs = null,
                    ).toArtifactMap()
                } finally {
                    store.close()
                }

                if (Build.VERSION.SDK_INT >= 36) {
                    writeEvidence(case.seedId, runtimeArtifact)
                }
            }

        }

    private fun assertRuntime(
        case: SeedCase,
        snapshot: CoverageSnapshot,
    ) {
        assertEquals(
            case.seedId + " video coverage",
            case.videoIntervals,
            snapshot.perTrackPublishedIntervals.getValue("video-main"),
        )
        assertEquals(
            case.seedId + " audio coverage",
            case.audioIntervals,
            snapshot.perTrackPublishedIntervals.getValue("audio-main"),
        )
        assertEquals(
            case.seedId + " playable coverage",
            case.playableIntervals,
            snapshot.playableIntervals,
        )
        assertEquals(
            case.seedId + " playable end",
            case.durablePlayableEndUs,
            snapshot.durablePlayableEndUs,
        )
        assertEquals(
            case.seedId + " reserve",
            case.durableReserveUs,
            snapshot.durableReserveUs,
        )
    }

    private suspend fun publishSeed(
        store: ExtentStore,
        case: SeedCase,
    ) {
        for (unit in case.units) {
            val bytes = fixture.verifiedBytes(unit.unit.resourcePath)
            val spec = unit.unit.toExtentSpec()

            if (unit.partialWrite) {
                expectThrows<ExtentIntegrityException> {
                    store.writeExtent(spec) {
                        write(
                            bytes,
                            length = maxOf(1, bytes.size / 2),
                        )
                    }
                }
            } else {
                store.writeExtent(spec) {
                    write(bytes)
                }
            }
        }
    }

    private fun canonicalCases(
        catalog: List<SeedUnit>,
    ): List<SeedCase> {
        val s0 = positive(catalog, "S0", 0)
        val s10 = positive(catalog, "S10", 10_000_000)
        val s30 = positive(catalog, "S30", 30_000_000)
        val s60 = positive(catalog, "S60", 60_000_000)
        val s120 = positive(catalog, "S120", 120_000_000)

        return listOf(
            s0.expected(
                video = emptyList(),
                audio = emptyList(),
                playable = emptyList(),
                end = null,
                reserve = 0,
            ),
            s10.expected(
                video = listOf(MediaInterval(0, 10_000_000)),
                audio = listOf(MediaInterval(0, 19_946_666)),
                playable = listOf(MediaInterval(0, 10_000_000)),
                end = 10_000_000,
                reserve = 10_000_000,
            ),
            s30.expected(
                video = listOf(MediaInterval(0, 30_000_000)),
                audio = listOf(MediaInterval(0, 39_936_000)),
                playable = listOf(MediaInterval(0, 30_000_000)),
                end = 30_000_000,
                reserve = 30_000_000,
            ),
            s60.expected(
                video = listOf(MediaInterval(0, 60_000_000)),
                audio = listOf(MediaInterval(0, 69_952_000)),
                playable = listOf(MediaInterval(0, 60_000_000)),
                end = 60_000_000,
                reserve = 60_000_000,
            ),
            s120.expected(
                video = listOf(MediaInterval(0, 120_000_000)),
                audio = listOf(MediaInterval(0, 129_941_333)),
                playable = listOf(MediaInterval(0, 120_000_000)),
                end = 120_000_000,
                reserve = 120_000_000,
            ),
            s30.without("f1:video:0:2")
                .copy(seedId = "S30_VIDEO_HOLE")
                .expected(
                    video = listOf(
                        MediaInterval(0, 10_000_000),
                        MediaInterval(20_000_000, 30_000_000),
                    ),
                    audio = listOf(MediaInterval(0, 39_936_000)),
                    playable = listOf(
                        MediaInterval(0, 10_000_000),
                        MediaInterval(20_000_000, 30_000_000),
                    ),
                    end = 10_000_000,
                    reserve = 10_000_000,
                ),
            s30.without("f1:audio:1:2")
                .copy(seedId = "S30_AUDIO_HOLE")
                .expected(
                    video = listOf(MediaInterval(0, 30_000_000)),
                    audio = listOf(
                        MediaInterval(0, 9_941_333),
                        MediaInterval(19_946_666, 39_936_000),
                    ),
                    playable = listOf(
                        MediaInterval(0, 9_941_333),
                        MediaInterval(19_946_666, 30_000_000),
                    ),
                    end = 9_941_333,
                    reserve = 9_941_333,
                ),
            s30.without("f1:video:0:init")
                .copy(seedId = "S30_MISSING_INIT")
                .expected(
                    video = emptyList(),
                    audio = listOf(MediaInterval(0, 39_936_000)),
                    playable = emptyList(),
                    end = null,
                    reserve = 0,
                ),
            s30.partial("f1:video:0:3")
                .copy(seedId = "S30_PARTIAL_TAIL")
                .expected(
                    video = listOf(MediaInterval(0, 20_000_000)),
                    audio = listOf(MediaInterval(0, 39_936_000)),
                    playable = listOf(MediaInterval(0, 20_000_000)),
                    end = 20_000_000,
                    reserve = 20_000_000,
                ),
            s30.wrongRepresentation(
                ids = setOf("f1:video:0:2", "f1:video:0:3"),
            )
                .copy(seedId = "S30_WRONG_REPRESENTATION")
                .expected(
                    video = listOf(MediaInterval(0, 10_000_000)),
                    audio = listOf(MediaInterval(0, 39_936_000)),
                    playable = listOf(MediaInterval(0, 10_000_000)),
                    end = 10_000_000,
                    reserve = 10_000_000,
                ),
        )
    }

    private fun positive(
        catalog: List<SeedUnit>,
        seedId: String,
        targetUs: Long,
    ): SeedCase =
        SeedCase(
            seedId = seedId,
            units = catalog.filter { unit ->
                val startUs = unit.mediaStartUs
                startUs == null || startUs < targetUs
            },
        )

    private fun SeedCase.without(extentId: String): SeedCase =
        copy(units = units.filterNot { it.extentId == extentId })

    private fun SeedCase.partial(extentId: String): SeedCase =
        copy(
            units = units.map { unit ->
                if (unit.extentId == extentId) {
                    unit.copy(partialWrite = true)
                } else {
                    unit
                }
            },
        )

    private fun SeedCase.wrongRepresentation(
        ids: Set<String>,
    ): SeedCase {
        val originalInit = units.single {
            it.extentId == "f1:video:0:init"
        }
        val wrongInit = SeedUnit(
            originalInit.unit.copy(
                extentId = "f1:video:alt:init",
                representationId = "f1-video-alt",
            ),
        )

        return copy(
            units = buildList {
                add(wrongInit)
                for (unit in units) {
                    if (unit.extentId in ids) {
                        val number = unit.extentId.substringAfterLast(':')
                        add(
                            unit.copy(
                                unit = unit.unit.copy(
                                    extentId = "f1:video:alt:$number",
                                    representationId = "f1-video-alt",
                                    dependencyExtentIds = listOf(
                                        wrongInit.extentId,
                                    ),
                                ),
                            ),
                        )
                    } else {
                        add(unit)
                    }
                }
            },
        )
    }

    private fun SeedCase.expected(
        video: List<MediaInterval>,
        audio: List<MediaInterval>,
        playable: List<MediaInterval>,
        end: Long?,
        reserve: Long,
    ): SeedCase =
        copy(
            videoIntervals = video,
            audioIntervals = audio,
            playableIntervals = playable,
            durablePlayableEndUs = end,
            durableReserveUs = reserve,
        )

    private fun writeEvidence(
        seedId: String,
        runtimeArtifact: Map<String, Any?>,
    ) {
        val output = PlatformTestStorageRegistry.getInstance()
        val storeRoot = File(context.filesDir, "sponge")
        check(storeRoot.isDirectory) {
            "ExtentStore root missing for evidence: $storeRoot"
        }

        storeRoot.walkTopDown()
            .filter(File::isFile)
            .forEach { source ->
                val relative = source.relativeTo(storeRoot)
                    .invariantSeparatorsPath
                output.openOutputFile(
                    "m1-c-evidence/$seedId/storage/$relative",
                ).use { destination ->
                    source.inputStream().use { input ->
                        input.copyTo(destination)
                    }
                }
            }

        output.openOutputFile(
            "m1-c-evidence/$seedId/runtime-coverage.json",
        ).bufferedWriter().use { writer ->
            writer.write(JSONObject(runtimeArtifact).toString(2))
            writer.newLine()
        }
    }

    private fun cleanStoreRoot() {
        File(context.filesDir, "sponge").deleteRecursively()
    }

    private data class SeedUnit(
        val unit: F1FetchUnit,
        val partialWrite: Boolean = false,
    ) {
        val extentId: String
            get() = unit.extentId

        val mediaStartUs: Long?
            get() = unit.mediaStartUs
    }

    private data class SeedCase(
        val seedId: String,
        val units: List<SeedUnit>,
        val videoIntervals: List<MediaInterval> = emptyList(),
        val audioIntervals: List<MediaInterval> = emptyList(),
        val playableIntervals: List<MediaInterval> = emptyList(),
        val durablePlayableEndUs: Long? = null,
        val durableReserveUs: Long = 0,
    )

    private companion object {
        val ASSET = F1FixtureCatalog.ASSET
        val REQUIRED = F1FixtureCatalog.REQUIRED_REPRESENTATIONS
    }
}

private suspend inline fun <reified T : Throwable> expectThrows(
    crossinline block: suspend () -> Unit,
): T {
    try {
        block()
    } catch (error: Throwable) {
        if (error is T) {
            return error
        }
        throw AssertionError(
            "expected " + T::class.java.name +
                ", got " + error::class.java.name,
            error,
        )
    }

    throw AssertionError("expected " + T::class.java.name)
}
