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

data class PlaybackRequirement(
    val trackId: String,
    val representationId: String,
) {
    init {
        require(trackId.isNotBlank()) { "trackId must not be blank" }
        require(representationId.isNotBlank()) {
            "representationId must not be blank"
        }
    }
}

class PlaybackRequirementSet(
    val mediaAssetId: MediaAssetId,
    requirements: Collection<PlaybackRequirement>,
) {
    val requirements: List<PlaybackRequirement>
    val requiredRepresentations: Map<String, String>

    init {
        require(!mediaAssetId.isLegacyUnscoped) {
            "legacy unscoped media asset id cannot be a playback target"
        }
        require(requirements.isNotEmpty()) {
            "at least one playback requirement is required"
        }

        val ordered = requirements.sortedBy(PlaybackRequirement::trackId)
        require(
            ordered.map(PlaybackRequirement::trackId).distinct().size ==
                ordered.size,
        ) {
            "a track may have only one active required representation"
        }

        this.requirements = ordered.toList()
        this.requiredRepresentations = ordered.associate {
            it.trackId to it.representationId
        }
    }

    constructor(
        mediaAssetId: MediaAssetId,
        requiredRepresentations: Map<String, String>,
    ) : this(
        mediaAssetId = mediaAssetId,
        requirements = requiredRepresentations.map { (trackId, representationId) ->
            PlaybackRequirement(trackId, representationId)
        },
    )
}

data class CoverageSnapshot(
    val mediaAssetId: MediaAssetId,
    val requiredRepresentations: Map<String, String>,
    val perTrackPublishedIntervals: Map<String, List<MediaInterval>>,
    val playableIntervals: List<MediaInterval>,
    val durablePlayableEndUs: Long?,
    val durableReserveUs: Long,
)
