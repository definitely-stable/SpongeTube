package io.github.definitelystable.spongetube.core.engine

import io.github.definitelystable.spongetube.core.storage.CommittedExtent
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentSpec
import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import io.github.definitelystable.spongetube.core.storage.Sha256Digest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(SpongeBridgeApi::class)
class ReserveDemandPlannerTest {
    @Test
    fun schedulesOnlyMissingAudioVideoInsideTargetAfterDurablePrefix() = runTest {
        val vi = unit("vi", "video", "v1")
        val ai = unit("ai", "audio", "a1")
        val v0 = unit("v0", "video", "v1", 0, 10, listOf("vi"))
        val a0 = unit("a0", "audio", "a1", 0, 10, listOf("ai"))
        val v1 = unit("v1", "video", "v1", 10, 20, listOf("vi"))
        val a1 = unit("a1", "audio", "a1", 10, 20, listOf("ai"))
        val v2 = unit("v2", "video", "v1", 20, 30, listOf("vi"))
        val a2 = unit("a2", "audio", "a1", 20, 30, listOf("ai"))

        val index = indexOf(vi, ai, v0, a0)
        val result = ReserveDemandPlanner.plan(
            playbackPlan = planOf(vi, ai, v0, a0, v1, a1, v2, a2),
            requirements = avRequirements,
            coverageIndex = index,
            playheadUs = 0,
            targetReserveUs = 20,
            maxFetchUnits = 8,
        )

        assertEquals(10, result.durableReserveUs)
        assertEquals(20, result.targetEndUs)
        assertEquals(listOf(FetchKey("fetch:a1"), FetchKey("fetch:v1")), result.fetchKeys)
        assertFalse(result.truncated)
    }

    @Test
    fun durableReserveAtTargetCreatesNoDemandEvenWithLaterIslands() = runTest {
        val v = unit("v", "video", "v1", 0, 20)
        val a = unit("a", "audio", "a1", 0, 20)
        val future = unit("future", "video", "v1", 40, 50)
        val result = ReserveDemandPlanner.plan(
            planOf(v, a, future),
            avRequirements,
            indexOf(v, a),
            0,
            15,
            8,
        )
        assertEquals(20, result.durableReserveUs)
        assertTrue(result.fetchKeys.isEmpty())
        assertFalse(result.truncated)
    }

    @Test
    fun publishedMediaMissingInitSchedulesOnlyDependencyNotDuplicateParent() = runTest {
        val init = unit("init", "video", "v1")
        val media = unit("media", "video", "v1", 0, 10, listOf("init"))
        val index = indexOf(media)
        val result = ReserveDemandPlanner.plan(
            planOf(init, media),
            videoRequirements,
            index,
            0,
            10,
            8,
        )
        assertEquals(listOf(FetchKey("fetch:init")), result.fetchKeys)
        assertEquals(0, result.durableReserveUs)
    }

    @Test
    fun sharedDependencyIsQueuedOnceAndCapIsStrict() = runTest {
        val init = unit("init", "video", "v1")
        val first = unit("first", "video", "v1", 0, 10, listOf("init"))
        val second = unit("second", "video", "v1", 10, 20, listOf("init"))
        val index = indexOf()
        val plan = planOf(init, first, second)
        val limited = ReserveDemandPlanner.plan(
            plan, videoRequirements, index, 0, 20, 2,
        )
        assertEquals(
            listOf(FetchKey("fetch:init"), FetchKey("fetch:first")),
            limited.fetchKeys,
        )
        assertTrue(limited.truncated)
        val full = ReserveDemandPlanner.plan(
            plan, videoRequirements, index, 0, 20, 8,
        )
        assertEquals(
            listOf(
                FetchKey("fetch:init"),
                FetchKey("fetch:first"),
                FetchKey("fetch:second"),
            ),
            full.fetchKeys,
        )
        assertFalse(full.truncated)
    }

    @Test
    fun ignoresWrongTrackRepresentationAndUntimedMedia() = runTest {
        val correct = unit("correct", "video", "v1", 0, 10)
        val alt = unit("alt", "video", "v2", 0, 10)
        val audio = unit("audio", "audio", "a1", 0, 10)
        val untimed = unit("untimed", "video", "v1")
        val result = ReserveDemandPlanner.plan(
            planOf(correct, alt, audio, untimed),
            videoRequirements,
            indexOf(),
            0,
            10,
            8,
        )
        assertEquals(listOf(FetchKey("fetch:correct")), result.fetchKeys)
    }

    @Test
    fun orderPrefersEarliestHoleRatherThanFarFutureIsland() = runTest {
        val first = unit("first", "video", "v1", 0, 10)
        val late = unit("late", "video", "v1", 40, 50)
        val middle = unit("middle", "video", "v1", 10, 20)
        val result = ReserveDemandPlanner.plan(
            planOf(late, middle, first),
            videoRequirements,
            indexOf(late),
            0,
            50,
            8,
        )
        assertEquals(
            listOf(FetchKey("fetch:first"), FetchKey("fetch:middle")),
            result.fetchKeys,
        )
    }

    @Test
    fun identityConflictFailsClosedInsteadOfRefetchingSameId() = runTest {
        val expected = unit("media", "video", "v1", 0, 10)
        val different = unit("media", "video", "v2", 0, 10)
        val index = indexOf(different)
        assertThrows(IllegalStateException::class.java) {
            ReserveDemandPlanner.plan(
                planOf(expected), videoRequirements, index, 0, 10, 8,
            )
        }
    }

    @Test
    fun dependencyCyclesFailClosedBeforeScheduling() = runTest {
        val first = unit("first", "video", "v1", dependencies = listOf("second"))
        val second = unit("second", "video", "v1", dependencies = listOf("first"))
        val media = unit("media", "video", "v1", 0, 10, listOf("first"))
        val index = indexOf()
        assertThrows(IllegalStateException::class.java) {
            ReserveDemandPlanner.plan(
                planOf(first, second, media),
                videoRequirements,
                index,
                0,
                10,
                8,
            )
        }
    }

    @Test
    fun snapshotViewDoesNotMixTwoCoverageGenerations() = runTest {
        val media = unit("media", "video", "v1", 0, 10)
        var current = emptyList<CommittedExtent>()
        val index = CoverageIndex.forTest { current }
        index.refresh()
        val captured = index.captureReserveView(videoRequirements, 0)
        current = listOf(committed(media))
        index.refresh()
        assertEquals(0, captured.snapshot.durableReserveUs)
        assertEquals(
            ExtentResolution.Absent,
            captured.resolveExtent(media.extentSpec),
        )
        assertEquals(
            ExtentResolution.Ready,
            index.resolveExtent(media.extentSpec),
        )
    }

    @Test
    fun horizonSaturatesAtLongMaxValueWithoutOverflow() = runTest {
        val nearEnd = unit(
            "near-end", "video", "v1",
            Long.MAX_VALUE - 8,
            Long.MAX_VALUE,
        )
        val result = ReserveDemandPlanner.plan(
            planOf(nearEnd),
            videoRequirements,
            indexOf(),
            Long.MAX_VALUE - 5,
            100,
            8,
        )
        assertEquals(Long.MAX_VALUE, result.targetEndUs)
        assertEquals(listOf(FetchKey("fetch:near-end")), result.fetchKeys)
    }

    @Test
    fun inputAndAssetGuardsAreStrict() = runTest {
        val media = unit("media", "video", "v1", 0, 10)
        val plan = planOf(media)
        val index = indexOf()
        assertThrows(IllegalArgumentException::class.java) {
            ReserveDemandPlanner.plan(plan, videoRequirements, index, 0, 0, 8)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReserveDemandPlanner.plan(plan, videoRequirements, index, 0, 10, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReserveDemandPlanner.plan(plan, videoRequirements, index, -1, 10, 8)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReserveDemandPlanner.plan(
                plan,
                PlaybackRequirementSet(MediaAssetId("other"), mapOf("video" to "v1")),
                index,
                0,
                10,
                8,
            )
        }
    }

    private suspend fun indexOf(vararg ready: PlaybackFetchUnit): CoverageIndex =
        CoverageIndex.forTest { ready.map(::committed) }.also { it.refresh() }

    private fun planOf(vararg units: PlaybackFetchUnit): PlaybackPlan =
        PlaybackPlan(
            ASSET,
            units.map { unit ->
                ExtentPlaybackResource(
                    key = unit.extentId.value + ".m4s",
                    length = unit.length,
                    units = listOf(unit),
                )
            },
        )

    private fun unit(
        id: String,
        track: String,
        representation: String,
        start: Long? = null,
        end: Long? = null,
        dependencies: List<String> = emptyList(),
    ): PlaybackFetchUnit =
        PlaybackFetchUnit(
            fetchKey = FetchKey("fetch:" + id),
            extentSpec = ExtentSpec(
                mediaAssetId = ASSET,
                extentId = ExtentId(id),
                trackId = track,
                representationId = representation,
                mediaStartUs = start,
                mediaEndUs = end,
                byteStart = null,
                byteEndExclusive = null,
                dependencyExtentIds = dependencies.map(::ExtentId),
                expectedLength = 100,
            ),
            resourceStart = 0,
            transportKey = "F1/" + id + ".m4s",
        )

    private fun committed(unit: PlaybackFetchUnit): CommittedExtent {
        val spec = unit.extentSpec
        return CommittedExtent(
            mediaAssetId = spec.mediaAssetId,
            extentId = spec.extentId,
            trackId = spec.trackId,
            representationId = spec.representationId,
            mediaStartUs = spec.mediaStartUs,
            mediaEndUs = spec.mediaEndUs,
            byteStart = spec.byteStart,
            byteEndExclusive = spec.byteEndExclusive,
            dependencyExtentIds = spec.dependencyExtentIds,
            length = spec.expectedLength,
            sha256 = Sha256Digest("a".repeat(64)),
        )
    }

    private companion object {
        val ASSET = MediaAssetId("fixture:F1")
        val videoRequirements = PlaybackRequirementSet(
            ASSET, mapOf("video" to "v1"),
        )
        val avRequirements = PlaybackRequirementSet(
            ASSET, mapOf("video" to "v1", "audio" to "a1"),
        )
    }
}
