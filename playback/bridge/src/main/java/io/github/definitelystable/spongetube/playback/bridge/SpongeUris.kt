package io.github.definitelystable.spongetube.playback.bridge

import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

/**
 * `sponge://<authority>/<resourceKey>` addressing for Sponge-managed media.
 *
 * The bridge accepts only this scheme and only its own authority. Any other
 * URI (http, https, file, content, a different authority) is refused: Media3
 * must never reach remote or local media through a path that bypasses
 * PlaybackBridge/FetchBroker.
 */
object SpongeUris {
    const val SCHEME: String = "sponge"

    fun uri(
        authority: String,
        resourceKey: String,
    ): String {
        require(AUTHORITY.matches(authority)) { "invalid sponge authority: $authority" }
        require(resourceKey.isNotBlank() && !resourceKey.startsWith("/")) {
            "resource key must be a relative path"
        }
        return "$SCHEME://$authority/$resourceKey"
    }

    @Throws(SpongeUnsupportedUriException::class)
    fun resourceKey(
        uri: String,
        expectedAuthority: String,
    ): String {
        val parsed = try {
            URI(uri)
        } catch (error: URISyntaxException) {
            throw SpongeUnsupportedUriException(uri, "malformed URI", error)
        }
        if (parsed.scheme != SCHEME) {
            throw SpongeUnsupportedUriException(uri, "scheme ${parsed.scheme} is not $SCHEME")
        }
        if (parsed.rawAuthority != expectedAuthority) {
            throw SpongeUnsupportedUriException(uri, "unexpected authority")
        }
        if (parsed.rawQuery != null || parsed.rawFragment != null) {
            throw SpongeUnsupportedUriException(uri, "query/fragment are not supported")
        }
        val key = parsed.path?.removePrefix("/").orEmpty()
        if (key.isBlank()) {
            throw SpongeUnsupportedUriException(uri, "empty resource key")
        }
        return key
    }

    private val AUTHORITY = Regex("^[a-z0-9][a-z0-9.-]{0,62}$")
}

class SpongeUnsupportedUriException(
    uri: String,
    reason: String,
    cause: Throwable? = null,
) : IOException("PlaybackBridge refused $uri: $reason", cause)
