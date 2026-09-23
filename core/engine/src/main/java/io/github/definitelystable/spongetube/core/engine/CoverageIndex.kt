package io.github.definitelystable.spongetube.core.engine

import io.github.definitelystable.spongetube.core.storage.CommittedExtent
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentStore
import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CoverageIndex private constructor(
    private val loadCommittedExtents: suspend () -> List<CommittedExtent>,
) {
    private val refreshMutex = Mutex()

    @Volatile
    private var projection = CoverageProjection.EMPTY

    constructor(extentStore: ExtentStore) : this(extentStore::committedExtents)

    /**
     * Rebuilds the entire authoritative in-memory projection.
     *
     * Full rebuild is intentional in M1. The new projection is installed only
     * after dependency validation and interval normalization completes, so a
     * failed refresh leaves the previous projection readable.
     */
    suspend fun refresh(): Int = refreshMutex.withLock {
        val loaded = loadCommittedExtents().map(CommittedExtent::defensiveCopy)
        val next = CoverageProjection.build(loaded)
        projection = next
        loaded.size
    }

    /**
     * Pure in-memory query. No Room/SQLite access and no dependency-graph walk.
     */
    fun snapshot(
        requirements: PlaybackRequirementSet,
        playheadUs: Long,
    ): CoverageSnapshot {
        require(playheadUs >= 0) { "playhead must be >= 0" }
        return projection.snapshot(requirements, playheadUs)
    }

    companion object {
        internal fun forTest(
            loader: suspend () -> List<CommittedExtent>,
        ): CoverageIndex = CoverageIndex(loader)
    }
}

private data class CoverageKey(
    val mediaAssetId: MediaAssetId,
    val trackId: String,
    val representationId: String,
)

private class CoverageProjection private constructor(
    private val intervalsByKey: Map<CoverageKey, List<MediaInterval>>,
) {
    fun snapshot(
        requirements: PlaybackRequirementSet,
        playheadUs: Long,
    ): CoverageSnapshot {
        val perTrack = requirements.requirements.associate { requirement ->
            requirement.trackId to intervalsByKey[
                CoverageKey(
                    mediaAssetId = requirements.mediaAssetId,
                    trackId = requirement.trackId,
                    representationId = requirement.representationId,
                )
            ].orEmpty()
        }

        val playable = CoverageAlgebra.intersectRequired(
            perTrack = perTrack,
            requiredTrackIds = requirements.requirements.map(
                PlaybackRequirement::trackId,
            ),
        )

        val containing = playable.firstOrNull { interval ->
            interval.startUs <= playheadUs && playheadUs < interval.endUs
        }

        return CoverageSnapshot(
            mediaAssetId = requirements.mediaAssetId,
            requiredRepresentations = requirements.requiredRepresentations,
            perTrackPublishedIntervals = perTrack,
            playableIntervals = playable,
            durablePlayableEndUs = containing?.endUs,
            durableReserveUs =
                containing?.let { it.endUs - playheadUs } ?: 0L,
        )
    }

    companion object {
        val EMPTY = CoverageProjection(emptyMap())

        fun build(extents: List<CommittedExtent>): CoverageProjection {
            val rows = LinkedHashMap<ExtentId, CommittedExtent>(extents.size)
            for (extent in extents) {
                require(rows.put(extent.extentId, extent) == null) {
                    "duplicate committed extent id: ${extent.extentId}"
                }
            }

            val candidates = rows.values.filter { extent ->
                extent.isStructurallyValid() &&
                    !extent.mediaAssetId.isLegacyUnscoped &&
                    extent.dependencyExtentIds.all { dependencyId ->
                        val dependency = rows[dependencyId]
                        dependency != null &&
                            dependency.isStructurallyValid() &&
                            dependency.coverageKey() == extent.coverageKey()
                    }
            }

            val candidateIds = candidates
                .mapTo(linkedSetOf(), CommittedExtent::extentId)
            val ready = linkedSetOf<ExtentId>()

            var changed: Boolean
            do {
                changed = false
                for (extent in candidates) {
                    if (extent.extentId in ready) {
                        continue
                    }
                    if (
                        extent.dependencyExtentIds.all { dependencyId ->
                            dependencyId in candidateIds &&
                                dependencyId in ready
                        }
                    ) {
                        ready += extent.extentId
                        changed = true
                    }
                }
            } while (changed)

            val raw = linkedMapOf<CoverageKey, MutableList<MediaInterval>>()
            for (extent in candidates) {
                if (extent.extentId !in ready) {
                    continue
                }

                val interval = extent.mediaIntervalOrNull() ?: continue
                raw.getOrPut(extent.coverageKey()) { mutableListOf() }
                    .add(interval)
            }

            val normalized = raw.mapValues { (_, intervals) ->
                CoverageAlgebra.normalize(intervals)
            }
            return CoverageProjection(normalized)
        }
    }
}

internal object CoverageAlgebra {
    fun normalize(
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
                    startUs = previous.startUs,
                    endUs = maxOf(previous.endUs, current.endUs),
                )
            } else {
                merged += current
            }
        }
        return merged.toList()
    }

    fun intersectRequired(
        perTrack: Map<String, List<MediaInterval>>,
        requiredTrackIds: List<String>,
    ): List<MediaInterval> {
        require(requiredTrackIds.isNotEmpty()) {
            "at least one required track is required"
        }
        require(requiredTrackIds.distinct().size == requiredTrackIds.size) {
            "required track ids must be unique"
        }

        var result = normalize(perTrack[requiredTrackIds.first()].orEmpty())
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
        val a = normalize(left)
        val b = normalize(right)
        val result = mutableListOf<MediaInterval>()
        var leftIndex = 0
        var rightIndex = 0

        while (leftIndex < a.size && rightIndex < b.size) {
            val start = maxOf(
                a[leftIndex].startUs,
                b[rightIndex].startUs,
            )
            val end = minOf(
                a[leftIndex].endUs,
                b[rightIndex].endUs,
            )
            if (start < end) {
                result += MediaInterval(start, end)
            }

            if (a[leftIndex].endUs <= b[rightIndex].endUs) {
                leftIndex += 1
            } else {
                rightIndex += 1
            }
        }

        return result
    }
}

private fun CommittedExtent.coverageKey(): CoverageKey =
    CoverageKey(
        mediaAssetId = mediaAssetId,
        trackId = trackId,
        representationId = representationId,
    )

private fun CommittedExtent.isStructurallyValid(): Boolean {
    if (trackId.isBlank() || representationId.isBlank()) {
        return false
    }

    val start = mediaStartUs
    val end = mediaEndUs
    return when {
        start == null && end == null -> true
        start == null || end == null -> false
        start < 0L -> false
        end <= start -> false
        else -> true
    }
}

private fun CommittedExtent.mediaIntervalOrNull(): MediaInterval? {
    val start = mediaStartUs ?: return null
    val end = mediaEndUs ?: return null
    if (start < 0L || end <= start) {
        return null
    }
    return MediaInterval(start, end)
}

private fun CommittedExtent.defensiveCopy(): CommittedExtent =
    copy(
        dependencyExtentIds = dependencyExtentIds.toList(),
    )
