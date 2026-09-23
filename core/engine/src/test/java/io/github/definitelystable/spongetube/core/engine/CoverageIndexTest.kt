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
    fun normalizeMergesOverlapAdjacencyNestedAndUnorderedInput() {
        assertEquals(
            listOf(
                MediaInterval(0, 25),
                MediaInterval(30, 50),
            ),
            CoverageAlgebra.normalize(
                listOf(
                    MediaInterval(10, 20),
                    MediaInterval(0, 10),
                    MediaInterval(5, 25),
                    MediaInterval(30, 40),
                    MediaInterval(35, 50),
                ),
            ),
        )
    }

    @Test
    fun oneRequirementSetUsesExactRepresentation() = runTest {
        val index = indexOf(
            init("v-init", "video", "v1"),
            media("v0", "video", "v1", 0, 20, "v-init"),
            media("v-wrong", "video", "v2", 20, 40),
        )

        val snapshot = index.snapshot(
            PlaybackRequirementSet(
                ASSET,
                mapOf("video" to "v1"),
            ),
            playheadUs = 5,
        )

        assertEquals(
            listOf(MediaInterval(0, 20)),
            snapshot.playableIntervals,
        )
        assertEquals(20, snapshot.durablePlayableEndUs)
        assertEquals(15, snapshot.durableReserveUs)
    }

    @Test
    fun multipleRequirementsIntersectConservatively() = runTest {
        val index = indexOf(
            init("v-init", "video", "v1"),
            init("a-init", "audio", "a1"),
            media("v0", "video", "v1", 0, 60, "v-init"),
            media("a0", "audio", "a1", 0, 70, "a-init"),
        )

        val snapshot = snapshot(index, 0)
        assertEquals(
            listOf(MediaInterval(0, 60)),
            snapshot.playableIntervals,
        )
        assertEquals(60, snapshot.durableReserveUs)
    }

    @Test
    fun reserveStopsAtFirstHoleAndLaterIslandIsQueryable() = runTest {
        val index = indexOf(
            init("v-init", "video", "v1"),
            init("a-init", "audio", "a1"),
            media("v0", "video", "v1", 0, 30, "v-init"),
            media("a0", "audio", "a1", 0, 30, "a-init"),
            media("v1", "video", "v1", 40, 90, "v-init"),
            media("a1", "audio", "a1", 40, 90, "a-init"),
        )

        assertEquals(20, snapshot(index, 10).durableReserveUs)
        assertEquals(0, snapshot(index, 35).durableReserveUs)
        assertEquals(45, snapshot(index, 45).durableReserveUs)
    }

    @Test
    fun halfOpenBoundaryAtEndHasNoReserve() = runTest {
        val index = indexOf(
            init("v-init", "video", "v1"),
            init("a-init", "audio", "a1"),
            media("v0", "video", "v1", 0, 10, "v-init"),
            media("a0", "audio", "a1", 0, 10, "a-init"),
        )

        assertEquals(10, snapshot(index, 0).durableReserveUs)
        assertEquals(0, snapshot(index, 10).durableReserveUs)
        assertEquals(null, snapshot(index, 10).durablePlayableEndUs)
    }

    @Test
    fun wrongAssetAndWrongRepresentationCannotFillCoverage() = runTest {
        val index = indexOf(
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
                asset = OTHER_ASSET,
            ),
        )

        assertEquals(
            listOf(MediaInterval(0, 10)),
            snapshot(index, 0).playableIntervals,
        )
    }

    @Test
    fun missingTransitiveAndCyclicDependenciesAreRejected() = runTest {
        val index = indexOf(
            init("a-init", "audio", "a1"),
            media("a0", "audio", "a1", 0, 40, "a-init"),
            media("missing", "video", "v1", 0, 10, "never"),
            init("v-init", "video", "v1"),
            init("v-index", "video", "v1", "v-init"),
            media("transitive", "video", "v1", 10, 20, "v-index"),
            media("cycle-a", "video", "v1", 20, 30, "cycle-b"),
            init("cycle-b", "video", "v1", "cycle-a"),
        )

        val result = snapshot(index, 0)
        assertEquals(
            listOf(MediaInterval(10, 20)),
            result.perTrackPublishedIntervals.getValue("video"),
        )
        assertEquals(emptyList<MediaInterval>(), result.playableIntervals)
    }

    @Test
    fun crossAssetTrackAndRepresentationDependenciesAreRejected() = runTest {
        val index = indexOf(
            init("a-init", "audio", "a1"),
            media("a0", "audio", "a1", 0, 30, "a-init"),
            init(
                "other-asset-init",
                "video",
                "v1",
                asset = OTHER_ASSET,
            ),
            init("other-track-init", "audio", "v1"),
            init("other-rep-init", "video", "v2"),
            media(
                "cross-asset",
                "video",
                "v1",
                0,
                10,
                "other-asset-init",
            ),
            media(
                "cross-track",
                "video",
                "v1",
                10,
                20,
                "other-track-init",
            ),
            media(
                "cross-rep",
                "video",
                "v1",
                20,
                30,
                "other-rep-init",
            ),
        )

        assertEquals(
            emptyList<MediaInterval>(),
            snapshot(index, 0).playableIntervals,
        )
    }

    @Test
    fun legacyUnscopedRowsNeverBecomeNamedCoverage() = runTest {
        val legacy = MediaAssetId(MediaAssetId.LEGACY_UNSCOPED_VALUE)
        val index = indexOf(
            init("legacy-init", "video", "v1", asset = legacy),
            media(
                "legacy-media",
                "video",
                "v1",
                0,
                30,
                "legacy-init",
                asset = legacy,
            ),
        )

        val named = index.snapshot(
            PlaybackRequirementSet(
                ASSET,
                mapOf("video" to "v1"),
            ),
            0,
        )
        assertEquals(emptyList<MediaInterval>(), named.playableIntervals)

        assertThrows(IllegalArgumentException::class.java) {
            PlaybackRequirementSet(
                legacy,
                mapOf("video" to "v1"),
            )
        }
    }

    @Test
    fun invalidPersistedMediaShapeIsConservativelyIgnored() = runTest {
        val invalid = extent(
            id = "invalid",
            track = "video",
            representation = "v1",
            start = 20,
            end = 10,
        )
        val index = indexOf(invalid)

        val snapshot = index.snapshot(
            PlaybackRequirementSet(
                ASSET,
                mapOf("video" to "v1"),
            ),
            0,
        )
        assertEquals(emptyList<MediaInterval>(), snapshot.playableIntervals)
    }

    @Test
    fun failedRefreshKeepsPreviousImmutableProjection() = runTest {
        var fail = false
        var extents = listOf(
            init("v-init", "video", "v1"),
            init("a-init", "audio", "a1"),
            media("v0", "video", "v1", 0, 10, "v-init"),
            media("a0", "audio", "a1", 0, 10, "a-init"),
        )
        val index = CoverageIndex.forTest {
            if (fail) {
                error("simulated refresh failure")
            }
            extents
        }

        index.refresh()
        assertEquals(10, snapshot(index, 0).durableReserveUs)

        fail = true
        val failure = runCatching { index.refresh() }.exceptionOrNull()
        assertEquals(IllegalStateException::class.java, failure?.javaClass)

        extents = emptyList()
        assertEquals(10, snapshot(index, 0).durableReserveUs)
    }

    @Test
    fun returnedSnapshotCannotMutateInstalledProjection() = runTest {
        val index = indexOf(
            init("v-init", "video", "v1"),
            init("a-init", "audio", "a1"),
            media("v0", "video", "v1", 0, 20, "v-init"),
            media("a0", "audio", "a1", 0, 20, "a-init"),
        )

        val first = snapshot(index, 0)
        @Suppress("UNCHECKED_CAST")
        val video = first.perTrackPublishedIntervals
            .getValue("video") as MutableList<MediaInterval>

        assertThrows(UnsupportedOperationException::class.java) {
            video.clear()
        }

        assertEquals(
            listOf(MediaInterval(0, 20)),
            snapshot(index, 0)
                .perTrackPublishedIntervals
                .getValue("video"),
        )
    }

    @Test
    fun evidenceSnapshotBindsRuntimePlayheadAndAssetExactly() = runTest {
        val index = indexOf(
            init("v-init", "video", "v1"),
            init("a-init", "audio", "a1"),
            media("v0", "video", "v1", 0, 20, "v-init"),
            media("a0", "audio", "a1", 0, 20, "a-init"),
        )
        val coverage = snapshot(index, 5)
        val artifact = CoverageEvidenceSnapshot(
            eventSequence = 7,
            eventElapsedRealtimeNs = 123,
            sessionId = "session-1",
            coverage = coverage,
            playerBufferedAheadUs = 2_000_000,
        ).toArtifactMap()

        assertEquals(2, artifact["schemaVersion"])
        assertEquals("asset-f1", artifact["mediaAssetId"])
        assertEquals(5L, artifact["playheadUs"])
        assertEquals(15L, artifact["durableReserveUs"])
        assertEquals(
            listOf("audio", "video"),
            artifact["requiredTrackIds"],
        )
    }

    @Test
    fun duplicateTrackRequirementsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackRequirementSet(
                ASSET,
                listOf(
                    PlaybackRequirement("video", "v1"),
                    PlaybackRequirement("video", "v2"),
                ),
            )
        }
    }

    private suspend fun indexOf(
        vararg extents: CommittedExtent,
    ): CoverageIndex {
        val index = CoverageIndex.forTest { extents.toList() }
        index.refresh()
        return index
    }

    private fun snapshot(
        index: CoverageIndex,
        playheadUs: Long,
    ): CoverageSnapshot =
        index.snapshot(
            PlaybackRequirementSet(
                ASSET,
                mapOf(
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
        val OTHER_ASSET = MediaAssetId("asset-other")
    }
}
