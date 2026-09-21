package io.github.definitelystable.spongetube.medialab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CanonicalFixtureServerTest {

    @TempDir
    Path temp;

    @Test
    void servesFrozenF0AndF1ThroughRealLoopbackOrigin() throws Exception {
        Path root = fixtureRoot();
        MediaLabConfig config = new MediaLabConfig(
                root,
                temp.resolve("canonical.jsonl"),
                "canonical-b3",
                MediaLabProfile.N0,
                0,
                4,
                0,
                1,
                null,
                null,
                MediaLabConfig.DEFAULT_WRITE_QUANTUM_BYTES);

        HttpClient client = HttpClient.newHttpClient();

        try (MediaLabServer server = MediaLabServer.create(config)) {
            server.start();
            URI base = URI.create("http://127.0.0.1:" + server.dataPort());

            HttpResponse<byte[]> f0Range = client.send(
                    HttpRequest.newBuilder(base.resolve("/fixtures/F0/progressive.mp4"))
                            .header("Range", "bytes=0-255")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(206, f0Range.statusCode());
            assertEquals(256, f0Range.body().length);
            assertEquals("video/mp4", f0Range.headers()
                    .firstValue("Content-Type")
                    .orElseThrow());

            HttpResponse<String> mpd = client.send(
                    HttpRequest.newBuilder(base.resolve("/fixtures/F1/manifest.mpd"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, mpd.statusCode());
            assertEquals("application/dash+xml", mpd.headers()
                    .firstValue("Content-Type")
                    .orElseThrow());
            assertTrue(mpd.body().contains("<MPD"));
            assertTrue(mpd.body().contains("mediaPresentationDuration=\"PT3M0.0S\""));

            HttpResponse<byte[]> video = client.send(
                    HttpRequest.newBuilder(base.resolve("/fixtures/F1/segment-0-00001.m4s"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, video.statusCode());
            assertEquals("video/iso.segment", video.headers()
                    .firstValue("Content-Type")
                    .orElseThrow());
            assertTrue(video.body().length > 0);

            HttpResponse<byte[]> audio = client.send(
                    HttpRequest.newBuilder(base.resolve("/fixtures/F1/segment-1-00001.m4s"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, audio.statusCode());
            assertEquals("audio/iso.segment", audio.headers()
                    .firstValue("Content-Type")
                    .orElseThrow());
            assertTrue(audio.body().length > 0);
        }
    }

    private static Path fixtureRoot() {
        String configured = System.getProperty("spongetube.fixtureRoot");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("Missing spongetube.fixtureRoot test property");
        }
        return Path.of(configured);
    }
}
