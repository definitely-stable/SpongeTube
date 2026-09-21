package io.github.definitelystable.spongetube.measurement

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BaselineObservationArtifactWriterTest {

    @Test
    fun writesObservedCacheIdentity() {
        val dir = Files.createTempDirectory("m0-cache-observation").toFile()
        val file = dir.resolve("baseline-observations.json")

        BaselineObservationArtifactWriter.write(
            file = file,
            sessionId = "session-1",
            cacheBytesAtPreparation = 10,
            cacheBytesAtEnd = 42,
        )

        val text = file.readText()
        assertTrue(text.contains("\"schemaVersion\": 1"))
        assertTrue(text.contains("\"sessionId\": \"session-1\""))
        assertTrue(text.contains("\"cacheBytesAtPreparation\": 10"))
        assertTrue(text.contains("\"cacheBytesAtEnd\": 42"))
        assertTrue(text.contains("\"cacheDeltaBytes\": 32"))
    }

    @Test
    fun refusesOverwriteSoEvidenceCannotBeSilentlyReplaced() {
        val dir = Files.createTempDirectory("m0-cache-observation").toFile()
        val file = dir.resolve("baseline-observations.json")

        BaselineObservationArtifactWriter.write(
            file = file,
            sessionId = "session-1",
            cacheBytesAtPreparation = 0,
            cacheBytesAtEnd = 1,
        )

        assertThrows(IllegalStateException::class.java) {
            BaselineObservationArtifactWriter.write(
                file = file,
                sessionId = "session-1",
                cacheBytesAtPreparation = 0,
                cacheBytesAtEnd = 2,
            )
        }
    }
}
