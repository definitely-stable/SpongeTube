package io.github.definitelystable.spongetube.core.engine

import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentSpec
import java.util.Collections

/**
 * One immutable, bounded planning result. No request is opened by the planner.
 * The execution layer must independently enforce its route, resource and
 * recovery budgets when it acquires these FetchKeys.
 */
internal data class ReserveDemandPlan(
    val durableReserveUs: Long,
    val targetEndUs: Long,
    val fetchKeys: List<FetchKey>,
    val truncated: Boolean,
)

/** Snapshot and readiness view captured from one CoverageIndex projection. */
internal class ReserveCoverageView(
    val snapshot: CoverageSnapshot,
    private val resolve: (ExtentSpec) -> ExtentResolution,
) {
    fun resolveExtent(spec: ExtentSpec): ExtentResolution = resolve(spec)
}

/**
 * M4-A0: maps missing time-addressable media work in the selected plan to
 * provider-independent immutable FetchKeys.
 *
 * This is intentionally not a scheduler or a fetcher: no I/O, no transport
 * policy, no heuristically chosen reserve horizon and no background work.
 * Untimed media units cannot be selected until an owning adapter provides
 * a trustworthy media-time mapping. Initialization units are admitted only
 * as explicit dependencies of selected timed units.
 */
@OptIn(SpongeBridgeApi::class)
internal object ReserveDemandPlanner {
    private const val MAX_FETCH_UNITS = 128

    fun plan(
        playbackPlan: PlaybackPlan,
        requirements: PlaybackRequirementSet,
        coverageIndex: CoverageIndex,
        playheadUs: Long,
        targetReserveUs: Long,
        maxFetchUnits: Int,
    ): ReserveDemandPlan {
        require(playbackPlan.mediaAssetId == requirements.mediaAssetId) {
            "playback plan and requirements must refer to the same asset"
        }
        require(playheadUs >= 0) { "playheadUs must be nonnegative" }
        require(targetReserveUs > 0) { "targetReserveUs must be positive" }
        require(maxFetchUnits in 1..MAX_FETCH_UNITS) {
            "maxFetchUnits must be in 1.." + MAX_FETCH_UNITS
        }

        // Saturate rather than overflow a media timeline near Long.MAX_VALUE.
        val targetEndUs = if (playheadUs > Long.MAX_VALUE - targetReserveUs) {
            Long.MAX_VALUE
        } else {
            playheadUs + targetReserveUs
        }
        val coverage = coverageIndex.captureReserveView(requirements, playheadUs)
        val durableReserveUs = coverage.snapshot.durableReserveUs
        if (durableReserveUs >= targetReserveUs) {
            return ReserveDemandPlan(
                durableReserveUs = durableReserveUs,
                targetEndUs = targetEndUs,
                fetchKeys = emptyList(),
                truncated = false,
            )
        }

        val selectedRepresentations = requirements.requiredRepresentations
        val candidates = playbackPlan.resources
            .filterIsInstance<ExtentPlaybackResource>()
            .flatMap(ExtentPlaybackResource::units)
            .filter { unit ->
                val spec = unit.extentSpec
                val start = spec.mediaStartUs
                val end = spec.mediaEndUs
                start != null && end != null &&
                    start < targetEndUs &&
                    end > playheadUs &&
                    selectedRepresentations[spec.trackId] == spec.representationId
            }
            .sortedWith(
                compareBy<PlaybackFetchUnit> { it.extentSpec.mediaStartUs!! }
                    .thenBy { it.extentSpec.mediaEndUs!! }
                    .thenBy { it.extentSpec.trackId }
                    .thenBy { it.extentId.value },
            )

        val pending = linkedMapOf<ExtentId, FetchKey>()
        val visited = mutableSetOf<ExtentId>()
        val visiting = mutableSetOf<ExtentId>()

        fun visit(unit: PlaybackFetchUnit) {
            if (unit.extentId in visited) return
            check(visiting.add(unit.extentId)) {
                "dependency cycle in reserve plan at " + unit.extentId.value
            }

            val result = coverage.resolveExtent(unit.extentSpec)
            check(result != ExtentResolution.IdentityConflict) {
                "committed extent has incompatible immutable identity: " +
                    unit.extentId.value
            }
            if (result != ExtentResolution.Ready) {
                for (dependencyId in unit.extentSpec.dependencyExtentIds) {
                    val dependency = checkNotNull(playbackPlan.unitForExtent(dependencyId)) {
                        "missing dependency from playback plan: " + dependencyId.value
                    }
                    check(
                        dependency.extentSpec.mediaAssetId == unit.extentSpec.mediaAssetId &&
                            dependency.extentSpec.trackId == unit.extentSpec.trackId &&
                            dependency.extentSpec.representationId ==
                            unit.extentSpec.representationId,
                    ) {
                        "cross-identity dependency in reserve plan: " +
                            dependencyId.value
                    }
                    visit(dependency)
                }
                when (result) {
                    ExtentResolution.Absent -> {
                        // Map.putIfAbsent requires Android API 24; minSdk is 23.
                        if (unit.extentId !in pending) {
                            pending[unit.extentId] = unit.fetchKey
                        }
                    }
                    is ExtentResolution.PublishedNotReady ->
                        check(result.missingDependencyIds.isNotEmpty()) {
                            "published extent is unrepairable: " + unit.extentId.value
                        }
                    ExtentResolution.IdentityConflict,
                    ExtentResolution.Ready -> error("unreachable resolution")
                }
            }
            visiting.remove(unit.extentId)
            visited.add(unit.extentId)
        }

        for (unit in candidates) {
            visit(unit)
        }

        return ReserveDemandPlan(
            durableReserveUs = durableReserveUs,
            targetEndUs = targetEndUs,
            fetchKeys = Collections.unmodifiableList(
                pending.values.take(maxFetchUnits).toList(),
            ),
            truncated = pending.size > maxFetchUnits,
        )
    }
}
