package io.github.definitelystable.spongetube.core.engine

import io.github.definitelystable.spongetube.core.storage.CommittedExtent
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentStore
import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import java.util.Collections
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
    suspend fun refresh(): CoverageRefreshResult = refreshMutex.withLock {
        val loaded = loadCommittedExtents().map(CommittedExtent::defensiveCopy)
        val next = CoverageProjection.build(loaded)
        projection = next
        CoverageRefreshResult(
            loadedExtentCount = loaded.size,
            readyExtentCount = next.readyExtentCount,
            normalizedIntervalCount = next.normalizedIntervalCount,
        )
    }

    /**
     * Internal bridge seam for resolving semantic coverage to immutable extents.
     *
     * The result is served from the same projection as snapshot(); it never
     * queries Room and never exposes filesystem paths.
     */
    internal fun readyExtentRefs(
        mediaAssetId: MediaAssetId,
        trackId: String,
        representationId: String,
    ): List<ReadyExtentRef> =
        projection.readyExtentRefs(
            CoverageKey(
                mediaAssetId = mediaAssetId,
                trackId = trackId,
                representationId = representationId,
            ),
        )

    /**
     * Internal bridge seam: classifies one immutable extent identity against
     * the current atomic projection.
     *
     * READY means PUBLISHED + VALID with a satisfied dependency closure.
     * PUBLISHED_NOT_READY means a committed row exists but at least one
     * dependency is not READY; fetching the extent itself again would collide
     * with the committed row, so callers must satisfy the listed dependencies.
     * ABSENT means no committed row is visible in this projection.
     *
     * Pure in-memory lookup: no Room/SQLite access and no graph walk.
     */
    internal fun resolveExtent(extentId: ExtentId): ExtentResolution =
        projection.resolveExtent(extentId)

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
    private val readyExtentsByKey: Map<CoverageKey, List<ReadyExtentRef>>,
    private val readyExtentIds: Set<ExtentId>,
    private val publishedDependencies: Map<ExtentId, List<ExtentId>>,
    val readyExtentCount: Int,
    val normalizedIntervalCount: Int,
) {
    fun readyExtentRefs(key: CoverageKey): List<ReadyExtentRef> =
        readyExtentsByKey[key].orEmpty()

    fun resolveExtent(extentId: ExtentId): ExtentResolution {
        if (extentId in readyExtentIds) {
            return ExtentResolution.Ready
        }
        val dependencies = publishedDependencies[extentId]
            ?: return ExtentResolution.Absent
        return ExtentResolution.PublishedNotReady(
            missingDependencyIds = Collections.unmodifiableList(
                dependencies.filterNot { it in readyExtentIds },
            ),
        )
    }

    fun snapshot(
        requirements: PlaybackRequirementSet,
        playheadUs: Long,
    ): CoverageSnapshot {
        val perTrack = Collections.unmodifiableMap(
            requirements.requirements.associate { requirement ->
                requirement.trackId to intervalsByKey[
                    CoverageKey(
                        mediaAssetId = requirements.mediaAssetId,
                        trackId = requirement.trackId,
                        representationId = requirement.representationId,
                    )
                ].orEmpty()
            },
        )

        val playable = CoverageAlgebra.intersectNormalizedRequired(
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
            playheadUs = playheadUs,
            requiredRepresentations = requirements.requiredRepresentations,
            perTrackPublishedIntervals = perTrack,
            playableIntervals = Collections.unmodifiableList(playable),
            durablePlayableEndUs = containing?.endUs,
            durableReserveUs =
                containing?.let { it.endUs - playheadUs } ?: 0L,
        )
    }

    companion object {
        val EMPTY = CoverageProjection(
            intervalsByKey = emptyMap(),
            readyExtentsByKey = emptyMap(),
            readyExtentIds = emptySet(),
            publishedDependencies = emptyMap(),
            readyExtentCount = 0,
            normalizedIntervalCount = 0,
        )

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
            val backing =
                linkedMapOf<CoverageKey, MutableList<ReadyExtentRef>>()
            for (extent in candidates) {
                if (extent.extentId !in ready) {
                    continue
                }

                val interval = extent.mediaIntervalOrNull() ?: continue
                val key = extent.coverageKey()
                raw.getOrPut(key) { mutableListOf() }
                    .add(interval)
                backing.getOrPut(key) { mutableListOf() }
                    .add(
                        ReadyExtentRef(
                            extentId = extent.extentId,
                            dependencyExtentIds = Collections.unmodifiableList(
                                extent.dependencyExtentIds.toList(),
                            ),
                            mediaStartUs = interval.startUs,
                            mediaEndUs = interval.endUs,
                            byteStart = extent.byteStart,
                            byteEndExclusive = extent.byteEndExclusive,
                            length = extent.length,
                        ),
                    )
            }

            val normalized = Collections.unmodifiableMap(
                raw.mapValues { (_, intervals) ->
                    Collections.unmodifiableList(
                        CoverageAlgebra.normalize(intervals),
                    )
                },
            )
            val readyExtents = Collections.unmodifiableMap(
                backing.mapValues { (_, refs) ->
                    Collections.unmodifiableList(
                        refs.sortedWith(
                            compareBy<ReadyExtentRef> { it.mediaStartUs }
                                .thenBy { it.mediaEndUs }
                                .thenBy { it.extentId.value },
                        ),
                    )
                },
            )
            return CoverageProjection(
                intervalsByKey = normalized,
                readyExtentsByKey = readyExtents,
                readyExtentIds = Collections.unmodifiableSet(ready.toSet()),
                publishedDependencies = Collections.unmodifiableMap(
                    rows.mapValues { (_, extent) ->
                        Collections.unmodifiableList(
                            extent.dependencyExtentIds.toList(),
                        )
                    },
                ),
                readyExtentCount = ready.size,
                normalizedIntervalCount = normalized.values.sumOf { it.size },
            )
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
    ): List<MediaInterval> =
        intersectNormalizedRequired(
            perTrack = perTrack.mapValues { (_, intervals) ->
                normalize(intervals)
            },
            requiredTrackIds = requiredTrackIds,
        )

    fun intersectNormalizedRequired(
        perTrack: Map<String, List<MediaInterval>>,
        requiredTrackIds: List<String>,
    ): List<MediaInterval> {
        require(requiredTrackIds.isNotEmpty()) {
            "at least one required track is required"
        }
        require(requiredTrackIds.distinct().size == requiredTrackIds.size) {
            "required track ids must be unique"
        }

        var result = perTrack[requiredTrackIds.first()].orEmpty()
        for (trackId in requiredTrackIds.drop(1)) {
            result = intersectNormalizedTwo(
                result,
                perTrack[trackId].orEmpty(),
            )
            if (result.isEmpty()) {
                break
            }
        }
        return result
    }

    private fun intersectNormalizedTwo(
        left: List<MediaInterval>,
        right: List<MediaInterval>,
    ): List<MediaInterval> {
        val result = mutableListOf<MediaInterval>()
        var leftIndex = 0
        var rightIndex = 0

        while (leftIndex < left.size && rightIndex < right.size) {
            val start = maxOf(
                left[leftIndex].startUs,
                right[rightIndex].startUs,
            )
            val end = minOf(
                left[leftIndex].endUs,
                right[rightIndex].endUs,
            )
            if (start < end) {
                result += MediaInterval(start, end)
            }

            if (left[leftIndex].endUs <= right[rightIndex].endUs) {
                leftIndex += 1
            } else {
                rightIndex += 1
            }
        }

        return result.toList()
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
