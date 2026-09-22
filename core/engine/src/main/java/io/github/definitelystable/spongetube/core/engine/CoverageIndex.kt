package io.github.definitelystable.spongetube.core.engine

import io.github.definitelystable.spongetube.core.storage.CommittedExtent
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CoverageIndex private constructor(
    private val loadCommittedExtents: suspend () -> List<CommittedExtent>,
) {
    private val refreshMutex = Mutex()

    @Volatile
    private var committedSnapshot: List<CommittedExtent> = emptyList()

    constructor(extentStore: ExtentStore) : this(extentStore::committedExtents)

    suspend fun refresh(): Int = refreshMutex.withLock {
        val loaded = loadCommittedExtents().map { extent ->
            extent.copy(
                dependencyExtentIds = extent.dependencyExtentIds.toList(),
            )
        }
        committedSnapshot = loaded
        loaded.size
    }

    fun snapshot(
        plan: CoveragePlan,
        playheadUs: Long,
    ): CoverageSnapshot =
        CoverageCalculator.snapshot(
            extents = committedSnapshot,
            plan = plan,
            playheadUs = playheadUs,
        )

    companion object {
        internal fun forTest(
            loader: suspend () -> List<CommittedExtent>,
        ): CoverageIndex = CoverageIndex(loader)
    }
}

internal object CoverageCalculator {
    private enum class VisitState {
        VISITING,
        READY,
        REJECTED,
    }

    fun snapshot(
        extents: List<CommittedExtent>,
        plan: CoveragePlan,
        playheadUs: Long,
    ): CoverageSnapshot {
        require(playheadUs >= 0) { "playhead must be >= 0" }

        val rows = linkedMapOf<ExtentId, CommittedExtent>()
        for (extent in extents) {
            require(rows.put(extent.extentId, extent) == null) {
                "duplicate committed extent id: " + extent.extentId
            }
        }

        val states = mutableMapOf<ExtentId, VisitState>()

        fun resolve(extent: CommittedExtent): Boolean {
            return when (states[extent.extentId]) {
                VisitState.READY -> true
                VisitState.REJECTED -> false
                VisitState.VISITING -> {
                    states[extent.extentId] = VisitState.REJECTED
                    false
                }
                null -> {
                    states[extent.extentId] = VisitState.VISITING

                    val dependenciesReady =
                        extent.dependencyExtentIds.all { dependencyId ->
                            val dependency = rows[dependencyId]
                                ?: return@all false
                            val sameIdentity =
                                dependency.mediaAssetId == extent.mediaAssetId &&
                                    dependency.trackId == extent.trackId &&
                                    dependency.representationId ==
                                    extent.representationId

                            sameIdentity && resolve(dependency)
                        }

                    val ready =
                        dependenciesReady &&
                            states[extent.extentId] != VisitState.REJECTED
                    states[extent.extentId] =
                        if (ready) VisitState.READY else VisitState.REJECTED
                    ready
                }
            }
        }

        val perTrackRaw = plan.requiredRepresentations.keys
            .associateWith { mutableListOf<MediaInterval>() }

        for (extent in extents) {
            val requiredRepresentation =
                plan.requiredRepresentations[extent.trackId] ?: continue
            if (extent.mediaAssetId != plan.mediaAssetId) {
                continue
            }
            if (extent.representationId != requiredRepresentation) {
                continue
            }
            if (!resolve(extent)) {
                continue
            }

            val start = extent.mediaStartUs
            val end = extent.mediaEndUs
            if (start == null || end == null) {
                continue
            }

            perTrackRaw.getValue(extent.trackId)
                .add(MediaInterval(start, end))
        }

        val perTrack = perTrackRaw.mapValues { (_, intervals) ->
            normalizeIntervals(intervals)
        }

        val playable = intersectRequired(
            perTrack = perTrack,
            requiredTrackIds = plan.requiredRepresentations.keys.toList(),
        )

        val containing = playable.firstOrNull { interval ->
            interval.startUs <= playheadUs && playheadUs < interval.endUs
        }

        return CoverageSnapshot(
            mediaAssetId = plan.mediaAssetId,
            requiredRepresentations = plan.requiredRepresentations,
            perTrackPublishedIntervals = perTrack,
            playableIntervals = playable,
            durablePlayableEndUs = containing?.endUs,
            durableReserveUs = containing?.let { it.endUs - playheadUs } ?: 0L,
        )
    }

    internal fun normalizeIntervals(
        intervals: Iterable<MediaInterval>,
    ): List<MediaInterval> {
        val ordered = intervals.sortedWith(
            compareBy<MediaInterval> { it.startUs }
                .thenBy { it.endUs },
        )
        if (ordered.isEmpty()) {
            return emptyList()
        }

        val merged = mutableListOf(ordered.first())
        for (current in ordered.drop(1)) {
            val previous = merged.last()
            if (current.startUs <= previous.endUs) {
                merged[merged.lastIndex] = MediaInterval(
                    previous.startUs,
                    maxOf(previous.endUs, current.endUs),
                )
            } else {
                merged += current
            }
        }
        return merged
    }

    internal fun intersectRequired(
        perTrack: Map<String, List<MediaInterval>>,
        requiredTrackIds: List<String>,
    ): List<MediaInterval> {
        require(requiredTrackIds.isNotEmpty()) {
            "at least one required track is required"
        }

        var result =
            normalizeIntervals(perTrack[requiredTrackIds.first()].orEmpty())
        for (trackId in requiredTrackIds.drop(1)) {
            result = intersectTwo(
                result,
                perTrack[trackId].orEmpty(),
            )
            if (result.isEmpty()) {
                break
            }
        }
        return result
    }

    private fun intersectTwo(
        left: List<MediaInterval>,
        right: List<MediaInterval>,
    ): List<MediaInterval> {
        val a = normalizeIntervals(left)
        val b = normalizeIntervals(right)
        val out = mutableListOf<MediaInterval>()
        var i = 0
        var j = 0

        while (i < a.size && j < b.size) {
            val start = maxOf(a[i].startUs, b[j].startUs)
            val end = minOf(a[i].endUs, b[j].endUs)
            if (start < end) {
                out += MediaInterval(start, end)
            }

            if (a[i].endUs <= b[j].endUs) {
                i += 1
            } else {
                j += 1
            }
        }

        return out
    }
}
