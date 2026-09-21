package io.github.definitelystable.spongetube.measurement

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import java.io.File

@UnstableApi
internal object PlaybackStatsCrossCheckWriter {

    fun write(
        file: File,
        sessionId: String,
        listener: PlaybackStatsListener,
        customMetrics: PlaybackMetrics,
    ) {
        val stats = listener.getCombinedPlaybackStats()

        file.parentFile?.mkdirs()
        file.writeText(
            buildString(1024) {
                append("{\n")
                append("  \"schemaVersion\": 1,\n")
                append("  \"sessionId\": ")
                appendJsonString(sessionId)
                append(",\n")
                append("  \"semanticRole\": \"MEDIA3_CROSS_CHECK\",\n")
                append("  \"playbackCount\": ")
                append(stats.playbackCount)
                append(",\n")
                append("  \"foregroundPlaybackCount\": ")
                append(stats.foregroundPlaybackCount)
                append(",\n")
                append("  \"totalRebufferCount\": ")
                append(stats.totalRebufferCount)
                append(",\n")
                append("  \"totalRebufferTimeMs\": ")
                append(stats.getTotalRebufferTimeMs())
                append(",\n")
                append("  \"totalSeekCount\": ")
                append(stats.totalSeekCount)
                append(",\n")
                append("  \"totalSeekTimeMs\": ")
                append(stats.getTotalSeekTimeMs())
                append(",\n")
                append("  \"totalJoinTimeMs\": ")
                append(stats.getTotalJoinTimeMs())
                append(",\n")
                append("  \"validJoinTimeCount\": ")
                append(stats.validJoinTimeCount)
                append(",\n")
                append("  \"totalValidJoinTimeMs\": ")
                append(stats.totalValidJoinTimeMs)
                append(",\n")
                append("  \"fatalErrorCount\": ")
                append(stats.fatalErrorCount)
                append(",\n")
                append("  \"totalDroppedFrames\": ")
                append(stats.totalDroppedFrames)
                append(",\n")
                append("  \"totalAudioUnderruns\": ")
                append(stats.totalAudioUnderruns)
                append(",\n")
                append("  \"customComparison\": {\n")
                append("    \"stallCount\": ")
                append(customMetrics.stallCount)
                append(",\n")
                append("    \"stallTotalNs\": ")
                append(customMetrics.stallTotalNs)
                append("\n")
                append("  }\n")
                append("}\n")
            },
            Charsets.UTF_8,
        )
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }
}
