package io.github.definitelystable.spongetube.measurement

import java.io.File

internal object PlaybackMetricsArtifactWriter {

    fun write(
        file: File,
        sessionId: String,
        metrics: PlaybackMetrics,
    ) {
        file.parentFile?.mkdirs()
        file.writeText(
            buildString(1024) {
                append("{\n")
                append("  \"schemaVersion\": 1,\n")
                append("  \"sessionId\": ")
                appendJsonString(sessionId)
                append(",\n")
                append("  \"status\": ")
                appendJsonString(metrics.status.name)
                append(",\n")
                append("  \"ttffNs\": ")
                append(metrics.ttffNs ?: "null")
                append(",\n")
                append("  \"stallCount\": ")
                append(metrics.stallCount)
                append(",\n")
                append("  \"stallTotalNs\": ")
                append(metrics.stallTotalNs)
                append(",\n")
                append("  \"progressIntentNs\": ")
                append(metrics.progressIntentNs ?: "null")
                append(",\n")
                append("  \"sessionWallNs\": ")
                append(metrics.sessionWallNs ?: "null")
                append(",\n")
                append("  \"rebufferRatio\": ")
                append(metrics.rebufferRatio ?: "null")
                append(",\n")
                append("  \"seekToFrame\": [")
                metrics.seekToFrame.forEachIndexed { index, sample ->
                    if (index > 0) append(',')
                    append("{\"operationId\":")
                    append(sample.operationId)
                    append(",\"durationNs\":")
                    append(sample.durationNs)
                    append('}')
                }
                append("],\n")
                append("  \"playbackErrorCodes\": [")
                metrics.playbackErrorCodes.forEachIndexed { index, code ->
                    if (index > 0) append(',')
                    appendJsonString(code)
                }
                append("],\n")
                append("  \"issues\": [")
                metrics.issues.forEachIndexed { index, issue ->
                    if (index > 0) append(',')
                    append("{\"code\":")
                    appendJsonString(issue.code.name)
                    append(",\"detail\":")
                    appendJsonString(issue.detail)
                    append('}')
                }
                append("]\n")
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
