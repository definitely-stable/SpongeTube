package io.github.definitelystable.spongetube.measurement

enum class MetricDerivationStatus {
    COMPLETE,
    PARTIAL,
    INVALID,
}

enum class MetricIssueCode {
    UNSUPPORTED_SCHEMA,
    MIXED_SESSION_IDS,
    NON_CONTIGUOUS_SEQUENCE,
    NON_MONOTONIC_TIMESTAMP,
    MISSING_SESSION_START,
    MISSING_SESSION_END,
    MISSING_PLAY_REQUEST,
    MISSING_FIRST_FRAME,
    FIRST_FRAME_BEFORE_PLAY_REQUEST,
    DUPLICATE_FIRST_FRAME,
    DUPLICATE_REBUFFER_START,
    REBUFFER_END_WITHOUT_START,
    UNCLOSED_REBUFFER,
    DUPLICATE_SEEK_START,
    OVERLAPPING_SEEKS,
    SEEK_COMPLETED_WITHOUT_START,
    FIRST_FRAME_AFTER_SEEK_WITHOUT_START,
    MISSING_FIRST_FRAME_AFTER_SEEK,
}

data class MetricIssue(
    val code: MetricIssueCode,
    val detail: String,
)

data class SeekToFrameSample(
    val operationId: Long,
    val durationNs: Long,
)

data class PlaybackMetrics(
    val status: MetricDerivationStatus,
    val ttffNs: Long?,
    val stallCount: Int,
    val stallTotalNs: Long,
    val seekToFrame: List<SeekToFrameSample>,
    val playbackErrorCodes: List<String>,
    val issues: List<MetricIssue>,
)

object PlaybackMetricReducer {

    fun reduce(events: List<PlaybackEvent>): PlaybackMetrics {
        if (events.isEmpty()) {
            return invalid(
                MetricIssueCode.MISSING_SESSION_START,
                "event stream is empty",
            )
        }

        val structuralIssues = validateStructure(events)
        if (structuralIssues.isNotEmpty()) {
            return PlaybackMetrics(
                status = MetricDerivationStatus.INVALID,
                ttffNs = null,
                stallCount = 0,
                stallTotalNs = 0,
                seekToFrame = emptyList(),
                playbackErrorCodes = events.mapNotNull { it.errorCode },
                issues = structuralIssues,
            )
        }

        val issues = mutableListOf<MetricIssue>()

        if (events.first().type != PlaybackEventType.SESSION_STARTED) {
            issues += MetricIssue(
                MetricIssueCode.MISSING_SESSION_START,
                "first event is ${events.first().type}",
            )
        }
        if (events.last().type != PlaybackEventType.SESSION_ENDED) {
            issues += MetricIssue(
                MetricIssueCode.MISSING_SESSION_END,
                "last event is ${events.last().type}",
            )
        }

        var playRequestedAt: Long? = null
        var firstFrameAt: Long? = null
        var playIntent = false
        var activeSeekOperationId: Long? = null

        var rebufferOpen = false
        var countedStallStartedAt: Long? = null
        var stallCount = 0
        var stallTotalNs = 0L

        val seekStarts = linkedMapOf<Long, Long>()
        val seekSamples = linkedMapOf<Long, SeekToFrameSample>()
        val playbackErrors = mutableListOf<String>()

        fun closeCountedStall(atNs: Long) {
            val start = countedStallStartedAt ?: return
            require(atNs >= start) {
                "validated monotonic stream produced a negative stall duration"
            }
            stallCount += 1
            stallTotalNs = Math.addExact(stallTotalNs, atNs - start)
            countedStallStartedAt = null
        }

        fun maybeStartCountedStall(atNs: Long) {
            if (
                rebufferOpen &&
                countedStallStartedAt == null &&
                firstFrameAt != null &&
                playIntent &&
                activeSeekOperationId == null
            ) {
                countedStallStartedAt = atNs
            }
        }

        for (event in events) {
            when (event.type) {
                PlaybackEventType.PLAY_REQUESTED -> {
                    if (playRequestedAt == null) {
                        playRequestedAt = event.atElapsedRealtimeNs
                    }
                    playIntent = true
                    maybeStartCountedStall(event.atElapsedRealtimeNs)
                }

                PlaybackEventType.PLAY_INTENT_CHANGED -> {
                    playIntent = checkNotNull(event.playIntent)
                    if (playIntent) {
                        maybeStartCountedStall(event.atElapsedRealtimeNs)
                    } else {
                        closeCountedStall(event.atElapsedRealtimeNs)
                    }
                }

                PlaybackEventType.FIRST_FRAME -> {
                    if (firstFrameAt == null) {
                        firstFrameAt = event.atElapsedRealtimeNs
                        maybeStartCountedStall(event.atElapsedRealtimeNs)
                    } else {
                        issues += MetricIssue(
                            MetricIssueCode.DUPLICATE_FIRST_FRAME,
                            "duplicate FIRST_FRAME at sequence ${event.sequence}",
                        )
                    }
                }

                PlaybackEventType.SEEK_STARTED -> {
                    closeCountedStall(event.atElapsedRealtimeNs)
                    val operationId = checkNotNull(event.operationId)

                    if (activeSeekOperationId != null) {
                        issues += MetricIssue(
                            MetricIssueCode.OVERLAPPING_SEEKS,
                            "active=$activeSeekOperationId new=$operationId",
                        )
                    } else {
                        activeSeekOperationId = operationId
                    }

                    if (seekStarts.putIfAbsent(
                            operationId,
                            event.atElapsedRealtimeNs,
                        ) != null
                    ) {
                        issues += MetricIssue(
                            MetricIssueCode.DUPLICATE_SEEK_START,
                            "operationId=$operationId",
                        )
                    }
                }

                PlaybackEventType.SEEK_COMPLETED -> {
                    val operationId = checkNotNull(event.operationId)
                    if (operationId !in seekStarts) {
                        issues += MetricIssue(
                            MetricIssueCode.SEEK_COMPLETED_WITHOUT_START,
                            "operationId=$operationId",
                        )
                    }
                }

                PlaybackEventType.FIRST_FRAME_AFTER_SEEK -> {
                    val operationId = checkNotNull(event.operationId)
                    val startedAt = seekStarts[operationId]

                    if (startedAt == null) {
                        issues += MetricIssue(
                            MetricIssueCode.FIRST_FRAME_AFTER_SEEK_WITHOUT_START,
                            "operationId=$operationId",
                        )
                    } else if (operationId !in seekSamples) {
                        seekSamples[operationId] = SeekToFrameSample(
                            operationId = operationId,
                            durationNs = event.atElapsedRealtimeNs - startedAt,
                        )
                    }

                    if (activeSeekOperationId == operationId) {
                        activeSeekOperationId = null
                        maybeStartCountedStall(event.atElapsedRealtimeNs)
                    }
                }

                PlaybackEventType.BUFFERING_STARTED -> {
                    if (event.bufferingReason == BufferingReason.REBUFFER) {
                        if (rebufferOpen) {
                            issues += MetricIssue(
                                MetricIssueCode.DUPLICATE_REBUFFER_START,
                                "sequence=${event.sequence}",
                            )
                        } else {
                            rebufferOpen = true
                            maybeStartCountedStall(event.atElapsedRealtimeNs)
                        }
                    }
                }

                PlaybackEventType.BUFFERING_ENDED -> {
                    if (event.bufferingReason == BufferingReason.REBUFFER) {
                        if (!rebufferOpen) {
                            issues += MetricIssue(
                                MetricIssueCode.REBUFFER_END_WITHOUT_START,
                                "sequence=${event.sequence}",
                            )
                        } else {
                            closeCountedStall(event.atElapsedRealtimeNs)
                            rebufferOpen = false
                        }
                    }
                }

                PlaybackEventType.PLAYBACK_ERROR -> {
                    playbackErrors += checkNotNull(event.errorCode)
                    closeCountedStall(event.atElapsedRealtimeNs)
                }

                PlaybackEventType.PLAYBACK_ENDED,
                PlaybackEventType.SESSION_ENDED,
                -> closeCountedStall(event.atElapsedRealtimeNs)

                else -> Unit
            }
        }

        if (playRequestedAt == null) {
            issues += MetricIssue(
                MetricIssueCode.MISSING_PLAY_REQUEST,
                "PLAY_REQUESTED was not observed",
            )
        }
        if (firstFrameAt == null) {
            issues += MetricIssue(
                MetricIssueCode.MISSING_FIRST_FRAME,
                "FIRST_FRAME was not observed",
            )
        }
        if (rebufferOpen) {
            issues += MetricIssue(
                MetricIssueCode.UNCLOSED_REBUFFER,
                "REBUFFER remained open at session boundary",
            )
        }

        for ((operationId, _) in seekStarts) {
            if (operationId !in seekSamples) {
                issues += MetricIssue(
                    MetricIssueCode.MISSING_FIRST_FRAME_AFTER_SEEK,
                    "operationId=$operationId",
                )
            }
        }

        val ttff = if (playRequestedAt != null && firstFrameAt != null) {
            if (firstFrameAt < playRequestedAt) {
                issues += MetricIssue(
                    MetricIssueCode.FIRST_FRAME_BEFORE_PLAY_REQUEST,
                    "firstFrame=$firstFrameAt playRequested=$playRequestedAt",
                )
                null
            } else {
                firstFrameAt - playRequestedAt
            }
        } else {
            null
        }

        val invalidSemanticIssue = issues.any { issue ->
            when (issue.code) {
                MetricIssueCode.FIRST_FRAME_BEFORE_PLAY_REQUEST,
                MetricIssueCode.DUPLICATE_FIRST_FRAME,
                MetricIssueCode.DUPLICATE_REBUFFER_START,
                MetricIssueCode.REBUFFER_END_WITHOUT_START,
                MetricIssueCode.DUPLICATE_SEEK_START,
                MetricIssueCode.OVERLAPPING_SEEKS,
                MetricIssueCode.SEEK_COMPLETED_WITHOUT_START,
                MetricIssueCode.FIRST_FRAME_AFTER_SEEK_WITHOUT_START,
                -> true

                else -> false
            }
        }

        val status = when {
            invalidSemanticIssue -> MetricDerivationStatus.INVALID
            issues.isEmpty() -> MetricDerivationStatus.COMPLETE
            else -> MetricDerivationStatus.PARTIAL
        }

        return PlaybackMetrics(
            status = status,
            ttffNs = ttff,
            stallCount = stallCount,
            stallTotalNs = stallTotalNs,
            seekToFrame = seekSamples.values.toList(),
            playbackErrorCodes = playbackErrors.toList(),
            issues = issues.toList(),
        )
    }

    private fun validateStructure(
        events: List<PlaybackEvent>,
    ): List<MetricIssue> {
        val issues = mutableListOf<MetricIssue>()
        val sessionId = events.first().sessionId

        events.forEachIndexed { index, event ->
            if (event.schemaVersion != PLAYBACK_EVENT_SCHEMA_VERSION) {
                issues += MetricIssue(
                    MetricIssueCode.UNSUPPORTED_SCHEMA,
                    "sequence=${event.sequence} schema=${event.schemaVersion}",
                )
            }

            if (event.sessionId != sessionId) {
                issues += MetricIssue(
                    MetricIssueCode.MIXED_SESSION_IDS,
                    "sequence=${event.sequence} session=${event.sessionId}",
                )
            }

            val expectedSequence = index + 1L
            if (event.sequence != expectedSequence) {
                issues += MetricIssue(
                    MetricIssueCode.NON_CONTIGUOUS_SEQUENCE,
                    "expected=$expectedSequence actual=${event.sequence}",
                )
            }

            if (
                index > 0 &&
                event.atElapsedRealtimeNs <
                events[index - 1].atElapsedRealtimeNs
            ) {
                issues += MetricIssue(
                    MetricIssueCode.NON_MONOTONIC_TIMESTAMP,
                    "sequence=${event.sequence}",
                )
            }
        }

        return issues
    }

    private fun invalid(
        code: MetricIssueCode,
        detail: String,
    ): PlaybackMetrics = PlaybackMetrics(
        status = MetricDerivationStatus.INVALID,
        ttffNs = null,
        stallCount = 0,
        stallTotalNs = 0,
        seekToFrame = emptyList(),
        playbackErrorCodes = emptyList(),
        issues = listOf(MetricIssue(code, detail)),
    )
}
