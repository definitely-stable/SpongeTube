package io.github.definitelystable.spongetube.core.engine

import io.github.definitelystable.spongetube.core.storage.MediaAssetId

data class MediaInterval(
    val startUs: Long,
    val endUs: Long,
) {
    init {
        require(startUs >= 0) { "interval start must be >= 0" }
        require(endUs > startUs) { "interval end must be greater than start" }
    }
}

class CoveragePlan(
    val mediaAssetId: MediaAssetId,
    requiredRepresentations: Map<String, String>,
) {
    val requiredRepresentations: Map<String, String> =
        requiredRepresentations.toSortedMap()

    init {
        require(!mediaAssetId.isLegacyUnscoped) {
            "legacy unscoped media asset id cannot be a coverage target"
        }
        require(this.requiredRepresentations.isNotEmpty()) {
            "at least one required track is required"
        }
        require(this.requiredRepresentations.keys.none(String::isBlank)) {
            "required track ids must not be blank"
        }
        require(this.requiredRepresentations.values.none(String::isBlank)) {
            "required representation ids must not be blank"
        }
    }
}

data class CoverageSnapshot(
    val mediaAssetId: MediaAssetId,
    val requiredRepresentations: Map<String, String>,
    val perTrackPublishedIntervals: Map<String, List<MediaInterval>>,
    val playableIntervals: List<MediaInterval>,
    val durablePlayableEndUs: Long?,
    val durableReserveUs: Long,
)
