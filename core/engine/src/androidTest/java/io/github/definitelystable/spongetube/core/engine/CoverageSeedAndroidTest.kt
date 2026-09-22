package io.github.definitelystable.spongetube.core.engine

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentIntegrityException
import io.github.definitelystable.spongetube.core.storage.ExtentSpec
import io.github.definitelystable.spongetube.core.storage.ExtentStore
import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.w3c.dom.Element

@RunWith(AndroidJUnit4::class)
class CoverageSeedAndroidTest {
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

    @Test
    fun canonicalPositiveAndNegativeSeedsMatchExactCoverage() = runBlocking {
        val catalog = loadCatalog()
        val cases = canonicalCases(catalog)

        for (case in cases) {
            cleanStoreRoot()
            val store = ExtentStore.open(context)
            try {
                publishSeed(store, case)
                val index = CoverageIndex(store)
                index.refresh()

                val snapshot = index.snapshot(
                    plan = CoveragePlan(
                        mediaAssetId = ASSET,
                        requiredRepresentations = REQUIRED,
                    ),
                    playheadUs = 0,
                )

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
                    case.seedId + " reserve",
                    case.durableReserveUs,
                    snapshot.durableReserveUs,
                )
            } finally {
                store.close()
            }
        }
    }

    private suspend fun publishSeed(
        store: ExtentStore,
        case: SeedCase,
    ) {
        for (unit in case.units) {
            val bytes = context.assets.open(unit.resourcePath).use {
                it.readBytes()
            }
            val spec = unit.toExtentSpec(bytes.size.toLong())

            if (unit.partialWrite) {
                expectThrows<ExtentIntegrityException> {
                    store.writeExtent(spec) {
                        write(bytes, length = bytes.size / 2)
                    }
                }
            } else {
                store.writeExtent(spec) {
                    write(bytes)
                }
            }
        }
    }

    private fun SeedUnit.toExtentSpec(length: Long): ExtentSpec =
        ExtentSpec(
            mediaAssetId = ASSET,
            extentId = ExtentId(extentId),
            trackId = trackId,
            representationId = representationId,
            mediaStartUs = mediaStartUs,
            mediaEndUs = mediaEndUs,
            byteStart = null,
            byteEndExclusive = null,
            dependencyExtentIds = dependencyExtentIds.map(::ExtentId),
            expectedLength = length,
            expectedSha256 = null,
        )

    private fun canonicalCases(
        catalog: List<SeedUnit>,
    ): List<SeedCase> {
        val s0 = positive(catalog, "S0", 0)
        val s10 = positive(catalog, "S10", 10_000_000)
        val s30 = positive(catalog, "S30", 30_000_000)
        val s60 = positive(catalog, "S60", 60_000_000)
        val s120 = positive(catalog, "S120", 120_000_000)

        return listOf(
            s0.copy(
                videoIntervals = emptyList(),
                audioIntervals = emptyList(),
                playableIntervals = emptyList(),
                durableReserveUs = 0,
            ),
            s10.copy(
                videoIntervals = listOf(MediaInterval(0, 10_000_000)),
                audioIntervals = listOf(MediaInterval(0, 19_946_666)),
                playableIntervals = listOf(MediaInterval(0, 10_000_000)),
                durableReserveUs = 10_000_000,
            ),
            s30.copy(
                videoIntervals = listOf(MediaInterval(0, 30_000_000)),
                audioIntervals = listOf(MediaInterval(0, 39_936_000)),
                playableIntervals = listOf(MediaInterval(0, 30_000_000)),
                durableReserveUs = 30_000_000,
            ),
            s60.copy(
                videoIntervals = listOf(MediaInterval(0, 60_000_000)),
                audioIntervals = listOf(MediaInterval(0, 69_930_666)),
                playableIntervals = listOf(MediaInterval(0, 60_000_000)),
                durableReserveUs = 60_000_000,
            ),
            s120.copy(
                videoIntervals = listOf(MediaInterval(0, 120_000_000)),
                audioIntervals = listOf(MediaInterval(0, 129_920_000)),
                playableIntervals = listOf(MediaInterval(0, 120_000_000)),
                durableReserveUs = 120_000_000,
            ),
            s30.without("f1:video:0:2").copy(
                seedId = "S30_VIDEO_HOLE",
                videoIntervals = listOf(
                    MediaInterval(0, 10_000_000),
                    MediaInterval(20_000_000, 30_000_000),
                ),
                audioIntervals = listOf(MediaInterval(0, 39_936_000)),
                playableIntervals = listOf(
                    MediaInterval(0, 10_000_000),
                    MediaInterval(20_000_000, 30_000_000),
                ),
                durableReserveUs = 10_000_000,
            ),
            s30.without("f1:audio:1:2").copy(
                seedId = "S30_AUDIO_HOLE",
                videoIntervals = listOf(MediaInterval(0, 30_000_000)),
                audioIntervals = listOf(
                    MediaInterval(0, 9_941_333),
                    MediaInterval(19_946_666, 39_936_000),
                ),
                playableIntervals = listOf(
                    MediaInterval(0, 9_941_333),
                    MediaInterval(19_946_666, 30_000_000),
                ),
                durableReserveUs = 9_941_333,
            ),
            s30.without("f1:video:0:init").copy(
                seedId = "S30_MISSING_INIT",
                videoIntervals = emptyList(),
                audioIntervals = listOf(MediaInterval(0, 39_936_000)),
                playableIntervals = emptyList(),
                durableReserveUs = 0,
            ),
            s30.partial("f1:video:0:3").copy(
                seedId = "S30_PARTIAL_TAIL",
                videoIntervals = listOf(MediaInterval(0, 20_000_000)),
                audioIntervals = listOf(MediaInterval(0, 39_936_000)),
                playableIntervals = listOf(MediaInterval(0, 20_000_000)),
                durableReserveUs = 20_000_000,
            ),
            s30.wrongRepresentation(
                ids = setOf("f1:video:0:2", "f1:video:0:3"),
            ).copy(
                seedId = "S30_WRONG_REPRESENTATION",
                videoIntervals = listOf(MediaInterval(0, 10_000_000)),
                audioIntervals = listOf(MediaInterval(0, 39_936_000)),
                playableIntervals = listOf(MediaInterval(0, 10_000_000)),
                durableReserveUs = 10_000_000,
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
                unit.mediaStartUs == null || unit.mediaStartUs < targetUs
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
        val wrongInit = originalInit.copy(
            extentId = "f1:video:alt:init",
            representationId = "f1-video-alt",
        )
        return copy(
            units = buildList {
                add(wrongInit)
                for (unit in units) {
                    if (unit.extentId in ids) {
                        add(
                            unit.copy(
                                representationId = "f1-video-alt",
                                dependencyExtentIds = listOf(
                                    wrongInit.extentId,
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

    private fun loadCatalog(): List<SeedUnit> =
        context.assets.open("manifest.mpd").use { input ->
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val document = factory.newDocumentBuilder().parse(input)
            val adaptations = document.getElementsByTagNameNS(
                "*",
                "AdaptationSet",
            )
            buildList {
                for (i in 0 until adaptations.length) {
                    val adaptation = adaptations.item(i) as Element
                    val kind = adaptation.getAttribute("contentType")
                    val trackId = when (kind) {
                        "video" -> "video-main"
                        "audio" -> "audio-main"
                        else -> continue
                    }
                    val representation = adaptation
                        .getElementsByTagNameNS("*", "Representation")
                        .item(0) as Element
                    val representationId =
                        "f1-" + kind + "-" + representation.getAttribute("id")
                    val repId = representation.getAttribute("id")
                    val template = representation
                        .getElementsByTagNameNS("*", "SegmentTemplate")
                        .item(0) as Element
                    val timescale = template.getAttribute("timescale").toLong()
                    val initPath = template.getAttribute("initialization")
                        .replace("\$RepresentationID\$", repId)
                    val initId = "f1:$kind:$repId:init"

                    add(
                        SeedUnit(
                            extentId = initId,
                            resourcePath = initPath,
                            trackId = trackId,
                            representationId = representationId,
                            mediaStartUs = null,
                            mediaEndUs = null,
                            dependencyExtentIds = emptyList(),
                        ),
                    )

                    val timeline = template
                        .getElementsByTagNameNS("*", "SegmentTimeline")
                        .item(0) as Element
                    val segments = timeline.getElementsByTagNameNS("*", "S")
                    val mediaTemplate = template.getAttribute("media")
                    var number = template.getAttribute("startNumber")
                        .ifBlank { "1" }
                        .toInt()
                    var currentTicks = 0L

                    for (segmentIndex in 0 until segments.length) {
                        val segment = segments.item(segmentIndex) as Element
                        if (segment.hasAttribute("t")) {
                            currentTicks = segment.getAttribute("t").toLong()
                        }
                        val duration = segment.getAttribute("d").toLong()
                        val repeat = segment.getAttribute("r")
                            .ifBlank { "0" }
                            .toInt()
                        require(repeat >= 0)

                        repeat(repeat + 1) {
                            val startTicks = currentTicks
                            val endTicks = startTicks + duration
                            val startUs =
                                startTicks * 1_000_000L / timescale
                            val endUs =
                                endTicks * 1_000_000L / timescale
                            val path = mediaTemplate
                                .replace("\$RepresentationID\$", repId)
                                .replace(
                                    "\$Number%05d\$",
                                    number.toString().padStart(5, '0'),
                                )
                            add(
                                SeedUnit(
                                    extentId = "f1:$kind:$repId:$number",
                                    resourcePath = path,
                                    trackId = trackId,
                                    representationId = representationId,
                                    mediaStartUs = startUs,
                                    mediaEndUs = endUs,
                                    dependencyExtentIds = listOf(initId),
                                ),
                            )
                            currentTicks = endTicks
                            number += 1
                        }
                    }
                }
            }
        }

    private fun cleanStoreRoot() {
        File(context.filesDir, "sponge").deleteRecursively()
    }

    private data class SeedUnit(
        val extentId: String,
        val resourcePath: String,
        val trackId: String,
        val representationId: String,
        val mediaStartUs: Long?,
        val mediaEndUs: Long?,
        val dependencyExtentIds: List<String>,
        val partialWrite: Boolean = false,
    )

    private data class SeedCase(
        val seedId: String,
        val units: List<SeedUnit>,
        val videoIntervals: List<MediaInterval> = emptyList(),
        val audioIntervals: List<MediaInterval> = emptyList(),
        val playableIntervals: List<MediaInterval> = emptyList(),
        val durableReserveUs: Long = 0,
    )

    private companion object {
        val ASSET = MediaAssetId("fixture:F1")
        val REQUIRED = mapOf(
            "video-main" to "f1-video-0",
            "audio-main" to "f1-audio-1",
        )
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
