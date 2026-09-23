package io.github.definitelystable.spongetube.core.engine

data class CoverageEvidenceSnapshot(
    val eventSequence: Long,
    val eventElapsedRealtimeNs: Long,
    val sessionId: String,
    val coverage: CoverageSnapshot,
    val playerBufferedAheadUs: Long? = null,
) {
    init {
        require(eventSequence >= 0) { "eventSequence must be >= 0" }
        require(eventElapsedRealtimeNs >= 0) {
            "eventElapsedRealtimeNs must be >= 0"
        }
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(playerBufferedAheadUs == null || playerBufferedAheadUs >= 0) {
            "playerBufferedAheadUs must be >= 0 when present"
        }
    }

    fun toArtifactMap(): Map<String, Any?> {
        val trackIds = coverage.requiredRepresentations.keys.sorted()
        return linkedMapOf(
            "schemaVersion" to SCHEMA_VERSION,
            "eventSequence" to eventSequence,
            "eventElapsedRealtimeNs" to eventElapsedRealtimeNs,
            "sessionId" to sessionId,
            "mediaAssetId" to coverage.mediaAssetId.value,
            "playheadUs" to coverage.playheadUs,
            "requiredTrackIds" to trackIds,
            "requiredRepresentations" to trackIds.associateWith { trackId ->
                coverage.requiredRepresentations.getValue(trackId)
            },
            "perTrackPublishedIntervals" to trackIds.associateWith { trackId ->
                coverage.perTrackPublishedIntervals
                    .getValue(trackId)
                    .map(MediaInterval::toArtifactMap)
            },
            "playableIntervals" to
                coverage.playableIntervals.map(MediaInterval::toArtifactMap),
            "durablePlayableEndUs" to coverage.durablePlayableEndUs,
            "durableReserveUs" to coverage.durableReserveUs,
            "playerBufferedAheadUs" to playerBufferedAheadUs,
        )
    }

    companion object {
        const val SCHEMA_VERSION = 2
    }
}

private fun MediaInterval.toArtifactMap(): Map<String, Long> =
    linkedMapOf(
        "startUs" to startUs,
        "endUs" to endUs,
    )
