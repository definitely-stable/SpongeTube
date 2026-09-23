package io.github.definitelystable.spongetube.playback.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SpongeUrisTest {
    @Test
    fun spongeUriRoundTripsResourceKey() {
        val uri = SpongeUris.uri(AUTHORITY, "segment-0-00003.m4s")

        assertEquals("sponge://fixture-f1/segment-0-00003.m4s", uri)
        assertEquals("segment-0-00003.m4s", SpongeUris.resourceKey(uri, AUTHORITY))
    }

    @Test
    fun remoteAndLocalSchemesAreRefused() {
        listOf(
            "http://127.0.0.1:18080/fixtures/F1/segment-0-00003.m4s",
            "https://example.invalid/F1/manifest.mpd",
            "file:///sdcard/F1/manifest.mpd",
            "content://media/external/1",
            "asset:///F1/manifest.mpd",
        ).forEach { uri ->
            assertThrows(SpongeUnsupportedUriException::class.java) {
                SpongeUris.resourceKey(uri, AUTHORITY)
            }
        }
    }

    @Test
    fun foreignAuthorityQueryAndEmptyKeyAreRefused() {
        listOf(
            "sponge://other/segment-0-00003.m4s",
            "sponge://fixture-f1/segment-0-00003.m4s?x=1",
            "sponge://fixture-f1/",
            "sponge://fixture-f1",
        ).forEach { uri ->
            assertThrows(SpongeUnsupportedUriException::class.java) {
                SpongeUris.resourceKey(uri, AUTHORITY)
            }
        }
    }

    private companion object {
        const val AUTHORITY = "fixture-f1"
    }
}
