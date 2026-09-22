package io.github.definitelystable.spongetube.core.engine

import io.github.definitelystable.spongetube.core.storage.CommittedExtent
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import io.github.definitelystable.spongetube.core.storage.Sha256Digest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CoverageIndexTest {
    @Test
    fun normalizeMergesOverlapAndAdjacency() {
        assertEquals(
            listOf(
                MediaInterval(0, 20),
                MediaInterval(30, 50),
            ),
            CoverageCalculator.normalizeIntervals(
                listOf(
                    MediaInterval(10, 20),
                    MediaInterval(0, 10),
                    MediaInterval(30, 40),
                    MediaInterval(35, 50),
                ),
            ),
        )
    }

    @Test
    fun requiredTrackIntersectionIsConservative() {
        assertEquals(
            listOf(MediaInterval(0, 60)),
            CoverageCalculator.intersectRequired(
                mapOf(
                    "video" to listOf(MediaInterval(0, 60)),
                    "audio" to listOf(MediaInterval(0, 70)),
                ),
                listOf("audio", "video"),
            ),
        )
    }

    @Test
    fun reserveStopsAtFirstHoleAndSupportsLaterIsland() = runTest {
        val extents = mutableListOf<CommittedExtent>()
        extents += init("v-init", "video", "v1")
        extents += init("a-init", "audio", "a1")
        extents += media("v0", "video", "v1", 0, 30, "v-init")
        extents += media("a0", "audio", "a1", 0, 30, "a-init")
        extents += media("v1", "video", "v1", 40, 90, "v-init")
        extents += media("a1", "audio", "a1", 40, 90, "a-init")

        val index = CoverageIndex({ extents.toList() })
        index.refresh()

        assertEquals(20, snapshot(index, 10).durableReserveUs)
        assertEquals(0, snapshot(index, 35).durableReserveUs)
        assertEquals(45, snapshot(index, 45).durableReserveUs)
    }

    @Test
    fun wrongRepresentationAndWrongAssetCannotFillHole() = runTest {
        val otherAsset = MediaAssetId("other")
        val extents = listOf(
            init("v-init", "video", "v1"),
            init("a-init", "audio", "a1"),
            media("v-good", "video", "v1", 0, 10, "v-init"),
            media("a-good", "audio", "a1", 0, 30, "a-init"),
            media("v-wrong-rep", "video", "v2", 10, 20),
            media(
                id = "v-wrong-asset",
                track = "video",
                representation = "v1",
                start = 20,
                end = 30,
                asset = otherAsset,
            ),
        )

        val index = CoverageIndex({ extents })
        index.refresh()

        val snapshot = snapshot(index, 0)
        assertEquals(
            listOf(MediaInterval(0, 10)),
            snapshot.playableIntervals,
        )
        assertEquals(10, snapshot.durableReserveUs)
    }

    @Test
    fun missingOrCyclicDependencyRejectsDependentCoverage() = runTest {
        val cyclicA = media(
            "cyclic-a",
            "video",
            "v1",
            10,
            20,
            "cyclic-b",
        )
        val cyclicB = init(
            "cyclic-b",
            "video",
            "v1",
            "cyclic-a",
        )

        val extents = listOf(
            init("a-init", "audio", "a1"),
            media("a0", "audio", "a1", 0, 30, "a-init"),
            media("missing-dep", "video", "v1", 0, 10, "never"),
            cyclicA,
            cyclicB,
        )

        val index = CoverageIndex({ extents })
        index.refresh()

        assertEquals(emptyList<MediaInterval>(), snapshot(index, 0).playableIntervals)
    }

    @Test
    fun playheadAtHalfOpenEndHasZeroReserve() = runTest {
        val extents = listOf(
            init("v-init", "video", "v1"),
            init("a-init", "audio", "a1"),
            media("v0", "video", "v1", 0, 10, "v-init"),
            media("a0", "audio", "a1", 0, 10, "a-init"),
        )
        val index = CoverageIndex({ extents })
        index.refresh()

        assertEquals(10, snapshot(index, 0).durableReserveUs)
        assertEquals(0, snapshot(index, 10).durableReserveUs)
    }

    @Test
    fun crossIdentityDependencyCannotAuthorizeCoverage() = runTest {
        val wrongAssetDependency = init(
            id = "wrong-asset-init",
            track = "video",
            representation = "v1",
            asset = MediaAssetId("other"),
        )
        val wrongRepresentationDependency = init(
            id = "wrong-rep-init",
            track = "video",
            representation = "v2",
        )
        val extents = listOf(
            wrongAssetDependency,
            wrongRepresentationDependency,
            init("a-init", "audio", "a1"),
            media("a0", "audio", "a1", 0, 20, "a-init"),
            media(
                "v-cross-asset",
                "video",
                "v1",
                0,
                10,
                "wrong-asset-init",
            ),
            media(
                "v-cross-rep",
                "video",
                "v1",
                10,
                20,
                "wrong-rep-init",
            ),
        )
        val index = CoverageIndex({ extents })
        index.refresh()

        assertEquals(emptyList<MediaInterval>(), snapshot(index, 0).playableIntervals)
    }

    @Test
    fun legacyUnscopedAssetCannotBeCoverageTarget() {
        assertThrows(IllegalArgumentException::class.java) {
            CoveragePlan(
                mediaAssetId = MediaAssetId(
                    MediaAssetId.LEGACY_UNSCOPED_VALUE,
                ),
                requiredRepresentations = mapOf("video" to "v1"),
            )
        }
    }

    @Test
    fun refreshAtomicallyReplacesCommittedProjection() = runTest {
        var extents = emptyList<CommittedExtent>()
        val index = CoverageIndex({ extents })

        index.refresh()
        assertEquals(0, snapshot(index, 0).durableReserveUs)

        extents = listOf(
            init("v-init", "video", "v1"),
            init("a-init", "audio", "a1"),
            media("v0", "video", "v1", 0, 10, "v-init"),
            media("a0", "audio", "a1", 0, 10, "a-init"),
        )
        index.refresh()

        assertEquals(10, snapshot(index, 0).durableReserveUs)
    }

    private fun snapshot(
        index: CoverageIndex,
        playheadUs: Long,
    ): CoverageSnapshot =
        index.snapshot(
            CoveragePlan(
                mediaAssetId = ASSET,
                requiredRepresentations = mapOf(
                    "video" to "v1",
                    "audio" to "a1",
                ),
            ),
            playheadUs,
        )

    private fun init(
        id: String,
        track: String,
        representation: String,
        vararg dependencies: String,
        asset: MediaAssetId = ASSET,
    ): CommittedExtent =
        extent(
            id = id,
            track = track,
            representation = representation,
            start = null,
            end = null,
            dependencies = dependencies,
            asset = asset,
        )

    private fun media(
        id: String,
        track: String,
        representation: String,
        start: Long,
        end: Long,
        vararg dependencies: String,
        asset: MediaAssetId = ASSET,
    ): CommittedExtent =
        extent(
            id = id,
            track = track,
            representation = representation,
            start = start,
            end = end,
            dependencies = dependencies,
            asset = asset,
        )

    private fun extent(
        id: String,
        track: String,
        representation: String,
        start: Long?,
        end: Long?,
        dependencies: Array<out String> = emptyArray(),
        asset: MediaAssetId = ASSET,
    ): CommittedExtent =
        CommittedExtent(
            mediaAssetId = asset,
            extentId = ExtentId(id),
            trackId = track,
            representationId = representation,
            mediaStartUs = start,
            mediaEndUs = end,
            byteStart = null,
            byteEndExclusive = null,
            dependencyExtentIds = dependencies.map(::ExtentId),
            length = 1,
            sha256 = Sha256Digest("0".repeat(64)),
        )

    private companion object {
        val ASSET = MediaAssetId("asset-f1")
    }
}
