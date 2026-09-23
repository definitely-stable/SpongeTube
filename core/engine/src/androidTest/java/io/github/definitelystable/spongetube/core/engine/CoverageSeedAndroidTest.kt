package io.github.definitelystable.spongetube.core.engine

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentIntegrityException
import io.github.definitelystable.spongetube.core.storage.ExtentSpec
import io.github.definitelystable.spongetube.core.storage.ExtentStore
import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import io.github.definitelystable.spongetube.core.storage.Sha256Digest
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.w3c.dom.Element

@RunWith(AndroidJUnit4::class)
class CoverageSeedAndroidTest {
    private lateinit var context: Context
    private lateinit var resources: Map<String, ResourceFact>

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        resources = loadResourceFacts()
        cleanStoreRoot()
    }

    @After
    fun tearDown() {
        cleanStoreRoot()
    }

    @Test
    fun canonicalSeedsPublishThroughRealStoreAndMatchExactCoverage() =
        runBlocking {
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
                        requirements = PlaybackRequirementSet(
                            mediaAssetId = ASSET,
                            requiredRepresentations = REQUIRED,
                        ),
                        playheadUs = 0,
                    )

                    assertEquals(
                        case.seedId + " video coverage",
                        case.videoIntervals,
                        snapshot.perTrackPublishedIntervals.getValue(
                            "video-main",
                        ),
                    )
                    assertEquals(
                        case.seedId + " audio coverage",
                        case.audioIntervals,
                        snapshot.perTrackPublishedIntervals.getValue(
                            "audio-main",
                        ),
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

                    if (case.seedId == "S30_PARTIAL_TAIL") {
                        assertFalse(
                            store.committedExtents().any {
                                it.extentId.value == "f1:video:0:3"
                            },
                        )
                    }
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
            val bytes = verifiedResourceBytes(unit.resourcePath)
            val spec = unit.toExtentSpec()

            if (unit.partialWrite) {
                expectThrows<ExtentIntegrityException> {
                    store.writeExtent(spec) {
                        write(bytes, length = maxOf(1, bytes.size / 2))
                    }
                }
            } else {
                store.writeExtent(spec) {
                    write(bytes)
                }
            }
        }
    }

    private fun SeedUnit.toExtentSpec(): ExtentSpec =
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
            expectedSha256 = Sha256Digest(sha256),
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
                        val number = unit.extentId.substringAfterLast(':')
                        add(
                            unit.copy(
                                extentId = "f1:video:alt:$number",
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

    private fun loadCatalog(): List<SeedUnit> {
        val mpdBytes = verifiedResourceBytes("F1/manifest.mpd")
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val document = factory.newDocumentBuilder().parse(
            ByteArrayInputStream(mpdBytes),
        )
        val adaptations = document.getElementsByTagNameNS(
            "*",
            "AdaptationSet",
        )

        return buildList {
            for (index in 0 until adaptations.length) {
                val adaptation = adaptations.item(index) as Element
                val kind = adaptation.getAttribute("contentType")
                val trackId = when (kind) {
                    "video" -> "video-main"
                    "audio" -> "audio-main"
                    else -> continue
                }

                val representations = adaptation.getElementsByTagNameNS(
                    "*",
                    "Representation",
                )
                require(representations.length == 1)
                val representation = representations.item(0) as Element
                val repId = representation.getAttribute("id")
                val representationId = "f1-$kind-$repId"

                val template = representation
                    .getElementsByTagNameNS("*", "SegmentTemplate")
                    .item(0) as Element
                val timescale = template.getAttribute("timescale").toLong()
                require(timescale > 0)

                val initPath = "F1/" +
                    template.getAttribute("initialization")
                        .replace("\$RepresentationID\$", repId)
                val initFact = resources.getValue(initPath)
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
                        length = initFact.length,
                        sha256 = initFact.sha256,
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
                var previousEndUs: Long? = null

                for (segmentIndex in 0 until segments.length) {
                    val segment = segments.item(segmentIndex) as Element
                    if (segment.hasAttribute("t")) {
                        currentTicks = segment.getAttribute("t").toLong()
                    }
                    val duration = segment.getAttribute("d").toLong()
                    val repeatCount = segment.getAttribute("r")
                        .ifBlank { "0" }
                        .toInt()
                    require(duration > 0)
                    require(repeatCount >= 0)

                    repeat(repeatCount + 1) {
                        val startTicks = currentTicks
                        val endTicks = startTicks + duration
                        val startUs =
                            startTicks * 1_000_000L / timescale
                        val endUs =
                            endTicks * 1_000_000L / timescale
                        previousEndUs?.let { previous ->
                            require(startUs == previous)
                        }

                        val relativePath = mediaTemplate
                            .replace("\$RepresentationID\$", repId)
                            .replace(
                                "\$Number%05d\$",
                                number.toString().padStart(5, '0'),
                            )
                        val resourcePath = "F1/$relativePath"
                        val fact = resources.getValue(resourcePath)

                        add(
                            SeedUnit(
                                extentId = "f1:$kind:$repId:$number",
                                resourcePath = resourcePath,
                                trackId = trackId,
                                representationId = representationId,
                                mediaStartUs = startUs,
                                mediaEndUs = endUs,
                                dependencyExtentIds = listOf(initId),
                                length = fact.length,
                                sha256 = fact.sha256,
                            ),
                        )
                        previousEndUs = endUs
                        currentTicks = endTicks
                        number += 1
                    }
                }
            }
        }
    }

    private fun loadResourceFacts(): Map<String, ResourceFact> {
        val raw = context.assets.open("manifest.json").bufferedReader().use {
            it.readText()
        }
        val root = JSONObject(raw)
        val fixtures = root.getJSONArray("fixtures")
        var fixture: JSONObject? = null
        for (index in 0 until fixtures.length()) {
            val candidate = fixtures.getJSONObject(index)
            if (candidate.getString("fixtureId") == "F1") {
                fixture = candidate
                break
            }
        }
        val f1 = checkNotNull(fixture)
        val result = linkedMapOf<String, ResourceFact>()
        val array = f1.getJSONArray("resources")
        for (index in 0 until array.length()) {
            val resource = array.getJSONObject(index)
            val path = resource.getString("relativePath")
            result[path] = ResourceFact(
                length = resource.getLong("sizeBytes"),
                sha256 = resource.getString("sha256"),
            )
        }
        return result
    }

    private fun verifiedResourceBytes(path: String): ByteArray {
        val fact = resources.getValue(path)
        val bytes = context.assets.open(path).use { it.readBytes() }
        assertEquals(path + " length", fact.length, bytes.size.toLong())
        assertEquals(path + " sha256", fact.sha256, sha256(bytes))
        return bytes
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val alphabet = "0123456789abcdef"
        val chars = CharArray(digest.size * 2)
        digest.forEachIndexed { index, value ->
            val unsigned = value.toInt() and 0xff
            chars[index * 2] = alphabet[unsigned ushr 4]
            chars[index * 2 + 1] = alphabet[unsigned and 0x0f]
        }
        return chars.concatToString()
    }

    private fun cleanStoreRoot() {
        File(context.filesDir, "sponge").deleteRecursively()
    }

    private data class ResourceFact(
        val length: Long,
        val sha256: String,
    )

    private data class SeedUnit(
        val extentId: String,
        val resourcePath: String,
        val trackId: String,
        val representationId: String,
        val mediaStartUs: Long?,
        val mediaEndUs: Long?,
        val dependencyExtentIds: List<String>,
        val length: Long,
        val sha256: String,
        val partialWrite: Boolean = false,
    )

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
