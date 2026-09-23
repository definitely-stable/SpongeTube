package io.github.definitelystable.spongetube.testsupport.fixture.f1

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class F1FixtureCatalogTest {
    private val fixtureRoot = locateFixtureRoot()

    @Test
    fun catalogMatchesCanonicalM1cIdentities() {
        val catalog = catalog()

        assertEquals(2 + 18 + 19, catalog.units.size)
        assertEquals("f1:video:0:init", catalog.units.first().extentId)
        assertEquals(19, catalog.unitsForTrack("video-main").size)
        assertEquals(20, catalog.unitsForTrack("audio-main").size)

        val audioFirst = catalog.unit("f1:audio:1:1")
        assertEquals("F1/segment-1-00001.m4s", audioFirst.resourcePath)
        assertEquals(0L, audioFirst.mediaStartUs)
        assertEquals(9_941_333L, audioFirst.mediaEndUs)
        assertEquals(81_811L, audioFirst.length)
        assertEquals(
            "fixture:F1/audio-main/f1-audio-1/segment-1-00001",
            audioFirst.fetchKeyValue,
        )
        assertEquals(listOf("f1:audio:1:init"), audioFirst.dependencyExtentIds)

        val spec = catalog.unit("f1:video:0:3").toExtentSpec()
        assertNull(spec.byteStart)
        assertNull(spec.byteEndExclusive)
        assertEquals(20_000_000L, spec.mediaStartUs)
        assertEquals(30_000_000L, spec.mediaEndUs)
    }

    @Test
    fun mediaTimelinesAreContiguousPerTrack() {
        val catalog = catalog()
        for (track in listOf("video-main", "audio-main")) {
            val media = catalog.unitsForTrack(track).filterNot(F1FetchUnit::isInit)
            assertEquals(0L, media.first().mediaStartUs)
            media.zipWithNext().forEach { (left, right) ->
                assertEquals(left.mediaEndUs, right.mediaStartUs)
            }
        }
        assertEquals(
            180_000_000L,
            catalog.unitsForTrack("video-main").last().mediaEndUs,
        )
    }

    @Test
    fun buildIsDeterministic() {
        assertEquals(catalog().units, catalog().units)
    }

    private fun catalog(): F1Catalog {
        val facts = resourceFacts()
        val mpd = File(fixtureRoot, F1FixtureCatalog.MANIFEST_PATH).readBytes()
        val mpdFact = facts.getValue(F1FixtureCatalog.MANIFEST_PATH)
        assertEquals(mpdFact.length, mpd.size.toLong(), "MPD bytes must be canonical (no CRLF)")
        assertEquals(mpdFact.sha256, sha256Hex(mpd))
        return F1FixtureCatalog.build(mpd, facts)
    }

    private fun resourceFacts(): Map<String, F1ResourceFact> {
        val checksums = File(fixtureRoot, "checksums.sha256").readLines()
        val result = linkedMapOf<String, F1ResourceFact>()
        for (line in checksums) {
            if (line.isBlank()) {
                continue
            }
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            val path = parts[1].removePrefix("*")
            if (!path.startsWith("F1/")) {
                continue
            }
            val file = File(fixtureRoot, path)
            assertTrue(file.isFile, path)
            result[path] = F1ResourceFact(file.length(), parts[0])
        }
        return result
    }

    private fun locateFixtureRoot(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "test-fixtures/media")
            if (File(candidate, "manifest.json").isFile) {
                return candidate
            }
            directory = directory.parentFile
        }
        error("test-fixtures/media not found")
    }
}
