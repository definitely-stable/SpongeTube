package io.github.definitelystable.spongetube.measurement

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption

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

        Files.writeString(
            file.toPath(),
            payload,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
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
