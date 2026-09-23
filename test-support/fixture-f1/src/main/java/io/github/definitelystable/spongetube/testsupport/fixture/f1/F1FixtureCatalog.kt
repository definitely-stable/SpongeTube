package io.github.definitelystable.spongetube.testsupport.fixture.f1

import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentSpec
import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import io.github.definitelystable.spongetube.core.storage.Sha256Digest
import java.io.ByteArrayInputStream
import java.util.Collections
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Immutable size/digest fact for one F1 resource from `manifest.json`. */
data class F1ResourceFact(
    val length: Long,
    val sha256: String,
) {
    init {
        require(length > 0) { "resource length must be > 0" }
        require(SHA256.matches(sha256)) { "sha256 must be lowercase hex" }
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * One complete F1 FetchUnit: exactly one fixture resource published as one
 * immutable extent.
 *
 * This is the single identity catalog for F1. M1-C seeds and M1-E playback
 * plans both derive their ExtentSpec/FetchKey from it so that the same
 * resource is never published under two identities (M1-E plan, fact F3).
 */
data class F1FetchUnit(
    val extentId: String,
    /** Fixture-root relative path, e.g. `F1/segment-0-00003.m4s`. */
    val resourcePath: String,
    val trackId: String,
    val representationId: String,
    val mediaStartUs: Long?,
    val mediaEndUs: Long?,
    val dependencyExtentIds: List<String>,
    val length: Long,
    val sha256: String,
) {
    /** Resource file name inside the fixture directory. */
    val resourceName: String
        get() = resourcePath.substringAfter('/')

    val isInit: Boolean
        get() = mediaStartUs == null

    /** Opaque single-flight identity: `fixture:F1/<track>/<rep>/<resource>`. */
    val fetchKeyValue: String
        get() = "fixture:F1/$trackId/$representationId/" +
            resourceName.substringBeforeLast('.')

    fun toExtentSpec(): ExtentSpec =
        ExtentSpec(
            mediaAssetId = F1FixtureCatalog.ASSET,
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
}

/** The complete, ordered F1 FetchUnit catalog. */
class F1Catalog internal constructor(
    units: List<F1FetchUnit>,
) {
    val units: List<F1FetchUnit> = Collections.unmodifiableList(units.toList())

    private val byExtentId = units.associateBy(F1FetchUnit::extentId)

    init {
        require(byExtentId.size == units.size) { "duplicate F1 extent id" }
        require(units.map(F1FetchUnit::fetchKeyValue).distinct().size == units.size) {
            "duplicate F1 fetch key"
        }
    }

    fun unit(extentId: String): F1FetchUnit =
        requireNotNull(byExtentId[extentId]) { "unknown F1 extent id: $extentId" }

    fun unitsForTrack(trackId: String): List<F1FetchUnit> =
        units.filter { it.trackId == trackId }
}

object F1FixtureCatalog {
    val ASSET: MediaAssetId = MediaAssetId("fixture:F1")
    const val MANIFEST_PATH: String = "F1/manifest.mpd"
    const val VIDEO_TRACK: String = "video-main"
    const val AUDIO_TRACK: String = "audio-main"

    val REQUIRED_REPRESENTATIONS: Map<String, String> = mapOf(
        VIDEO_TRACK to "f1-video-0",
        AUDIO_TRACK to "f1-audio-1",
    )

    /**
     * Builds the catalog from verified MPD bytes and fixture resource facts.
     *
     * Deterministic: identical inputs always yield the identical ordered unit
     * list (video adaptation first, init before media segments), which is the
     * ordering the M1-C seed artifacts were produced with.
     */
    fun build(
        mpdBytes: ByteArray,
        resources: Map<String, F1ResourceFact>,
    ): F1Catalog {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val document = factory.newDocumentBuilder().parse(
            ByteArrayInputStream(mpdBytes),
        )
        val adaptations = document.getElementsByTagNameNS("*", "AdaptationSet")

        val units = buildList {
            for (index in 0 until adaptations.length) {
                val adaptation = adaptations.item(index) as Element
                val kind = adaptation.getAttribute("contentType")
                val trackId = when (kind) {
                    "video" -> VIDEO_TRACK
                    "audio" -> AUDIO_TRACK
                    else -> continue
                }

                val representations = adaptation.getElementsByTagNameNS(
                    "*",
                    "Representation",
                )
                require(representations.length == 1) {
                    "F1 must declare exactly one representation per adaptation"
                }
                val representation = representations.item(0) as Element
                val repId = representation.getAttribute("id")
                val representationId = "f1-$kind-$repId"

                val template = representation
                    .getElementsByTagNameNS("*", "SegmentTemplate")
                    .item(0) as Element
                val timescale = template.getAttribute("timescale").toLong()
                require(timescale > 0)

                val initPath = "F1/" + template.getAttribute("initialization")
                    .replace("\$RepresentationID\$", repId)
                val initFact = fact(resources, initPath)
                val initId = "f1:$kind:$repId:init"
                add(
                    F1FetchUnit(
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
                        val startUs = startTicks * 1_000_000L / timescale
                        val endUs = endTicks * 1_000_000L / timescale
                        previousEndUs?.let { previous ->
                            require(startUs == previous) {
                                "F1 timeline must be contiguous"
                            }
                        }

                        val relativePath = mediaTemplate
                            .replace("\$RepresentationID\$", repId)
                            .replace(
                                "\$Number%05d\$",
                                number.toString().padStart(5, '0'),
                            )
                        val resourcePath = "F1/$relativePath"
                        val segmentFact = fact(resources, resourcePath)

                        add(
                            F1FetchUnit(
                                extentId = "f1:$kind:$repId:$number",
                                resourcePath = resourcePath,
                                trackId = trackId,
                                representationId = representationId,
                                mediaStartUs = startUs,
                                mediaEndUs = endUs,
                                dependencyExtentIds = listOf(initId),
                                length = segmentFact.length,
                                sha256 = segmentFact.sha256,
                            ),
                        )
                        previousEndUs = endUs
                        currentTicks = endTicks
                        number += 1
                    }
                }
            }
        }
        return F1Catalog(units)
    }

    private fun fact(
        resources: Map<String, F1ResourceFact>,
        path: String,
    ): F1ResourceFact =
        requireNotNull(resources[path]) { "fixture manifest has no resource $path" }
}
