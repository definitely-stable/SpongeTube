package io.github.definitelystable.spongetube.medialab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CommittedFixturesTest {

    @Test
    void committedCorpusMatchesManifestChecksumsAndBudgets() throws Exception {
        Path root = fixtureRoot();

        FixtureManifestVerifier.verify(root);

        FixtureManifest manifest = FixtureManifest.load(root);
        assertEquals(10_000L, manifest.fixture("F0").durationMs());
        assertEquals(1_054_544L, manifest.fixture("F0").actualBytes());

        FixtureManifest.FixtureMetadata f1 = manifest.fixture("F1");
        assertEquals(180_000L, f1.durationMs());
        assertEquals(13_956_166L, f1.actualBytes());
        assertEquals(18L, f1.resources().stream()
                .filter(resource -> "video-segment".equals(resource.role()))
                .count());
        assertEquals(19L, f1.resources().stream()
                .filter(resource -> "audio-segment".equals(resource.role()))
                .count());

        Long referenceBitrate = f1.referencePlaybackBitrateBps();
        assertTrue(referenceBitrate != null && referenceBitrate > 0);
        assertEquals(
                f1.actualAverageBitrateBps(),
                referenceBitrate.longValue());
        assertEquals(620_180L, referenceBitrate.longValue());
    }

    @Test
    void canonicalCatalogUsesRoleAwareDashMimeTypes() throws Exception {
        FixtureCatalog catalog = FixtureCatalog.load(fixtureRoot());

        assertEquals(
                "application/dash+xml",
                catalog.findRawPath("/fixtures/F1/manifest.mpd").contentType());
        assertEquals(
                "video/mp4",
                catalog.findRawPath("/fixtures/F1/init-0.m4s").contentType());
        assertEquals(
                "audio/mp4",
                catalog.findRawPath("/fixtures/F1/init-1.m4s").contentType());
        assertEquals(
                "video/iso.segment",
                catalog.findRawPath("/fixtures/F1/segment-0-00001.m4s").contentType());
        assertEquals(
                "audio/iso.segment",
                catalog.findRawPath("/fixtures/F1/segment-1-00001.m4s").contentType());
    }

    @Test
    void f0IsFastStartMp4() throws Exception {
        byte[] bytes = Files.readAllBytes(fixtureRoot().resolve("F0/progressive.mp4"));

        int moov = indexOfAscii(bytes, "moov");
        int mdat = indexOfAscii(bytes, "mdat");

        assertTrue(moov > 0, "F0 must contain a moov box");
        assertTrue(mdat > 0, "F0 must contain an mdat box");
        assertTrue(moov < mdat, "F0 must keep moov before mdat for fast start");
    }

    private static Path fixtureRoot() {
        String configured = System.getProperty("spongetube.fixtureRoot");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("Missing spongetube.fixtureRoot test property");
        }
        return Path.of(configured);
    }

    private static int indexOfAscii(byte[] haystack, String needle) {
        byte[] target = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int index = 0; index <= haystack.length - target.length; index++) {
            for (int offset = 0; offset < target.length; offset++) {
                if (haystack[index + offset] != target[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }
}
