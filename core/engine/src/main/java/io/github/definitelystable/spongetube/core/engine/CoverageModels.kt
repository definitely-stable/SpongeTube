package io.github.definitelystable.spongetube.core.engine

import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import java.util.Collections

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

        this.requirements = Collections.unmodifiableList(ordered.toList())
        this.requiredRepresentations = Collections.unmodifiableMap(
            ordered.associate {
                it.trackId to it.representationId
            },
        )
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

data class CoverageRefreshResult(
    val loadedExtentCount: Int,
    val readyExtentCount: Int,
    val normalizedIntervalCount: Int,
) {
    init {
        require(loadedExtentCount >= 0)
        require(readyExtentCount >= 0)
        require(normalizedIntervalCount >= 0)
        require(readyExtentCount <= loadedExtentCount)
    }
}

internal data class ReadyExtentRef(
    val extentId: ExtentId,
    val dependencyExtentIds: List<ExtentId>,
    val mediaStartUs: Long,
    val mediaEndUs: Long,
    val byteStart: Long?,
    val byteEndExclusive: Long?,
    val length: Long,
) {
    init {
        require(mediaStartUs >= 0)
        require(mediaEndUs > mediaStartUs)
        require(length > 0)
        require((byteStart == null) == (byteEndExclusive == null))
        if (byteStart != null && byteEndExclusive != null) {
            require(byteStart >= 0)
            require(byteEndExclusive > byteStart)
        }
    }
}

/**
 * Classification of one ExtentId against a single CoverageIndex projection.
 */
internal sealed interface ExtentResolution {
    /** PUBLISHED + VALID and every dependency is READY. */
    data object Ready : ExtentResolution

    /**
     * A committed row exists, but it cannot contribute until the listed
     * dependencies become READY. An empty list means the row can never become
     * READY through dependency repair (for example a dependency of another
     * representation) and callers must fail closed.
     */
    data class PublishedNotReady(
        val missingDependencyIds: List<ExtentId>,
    ) : ExtentResolution

    /** No committed PUBLISHED + VALID row in this projection. */
    data object Absent : ExtentResolution
}

@ConsistentCopyVisibility
data class CoverageSnapshot internal constructor(
    val mediaAssetId: MediaAssetId,
    val playheadUs: Long,
    val requiredRepresentations: Map<String, String>,
    val perTrackPublishedIntervals: Map<String, List<MediaInterval>>,
    val playableIntervals: List<MediaInterval>,
    val durablePlayableEndUs: Long?,
    val durableReserveUs: Long,
)
