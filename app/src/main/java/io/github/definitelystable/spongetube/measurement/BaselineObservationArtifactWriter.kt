package io.github.definitelystable.spongetube.measurement

import java.io.File

object BaselineObservationArtifactWriter {

    fun write(
        file: File,
        sessionId: String,
        cacheBytesAtPreparation: Long,
        cacheBytesAtEnd: Long,
    ) {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(cacheBytesAtPreparation >= 0L) {
            "cacheBytesAtPreparation must be >= 0"
        }
        require(cacheBytesAtEnd >= 0L) {
            "cacheBytesAtEnd must be >= 0"
        }

        file.parentFile?.mkdirs()
        val payload = buildString {
            append("{\n")
            append("  \"schemaVersion\": 1,\n")
            append("  \"sessionId\": \"")
            append(jsonEscape(sessionId))
            append("\",\n")
            append("  \"cacheBytesAtPreparation\": ")
            append(cacheBytesAtPreparation)
            append(",\n")
            append("  \"cacheBytesAtEnd\": ")
            append(cacheBytesAtEnd)
            append(",\n")
            append("  \"cacheDeltaBytes\": ")
            append(cacheBytesAtEnd - cacheBytesAtPreparation)
            append("\n}\n")
        }

        check(file.createNewFile()) {
            "baseline observation artifact already exists: $file"
        }
        file.outputStream()
            .bufferedWriter(Charsets.UTF_8)
            .use { writer ->
                writer.write(payload)
            }
    }

    private fun jsonEscape(value: String): String = buildString {
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }
}
