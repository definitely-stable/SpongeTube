package io.github.definitelystable.spongetube.core.engine

import java.net.HttpURLConnection
import java.net.URL

/**
 * Opaque execution-only binding to one platform route selected by M2-F.
 *
 * This is never stable media identity and is never serialized. Android keeps
 * the underlying android.net.Network private to its platform adapter. Opening
 * through this binding must target that exact network; if it is gone, the
 * operation fails instead of falling back to the ambient default route.
 *
 * The URLConnection capability is the M2-F baseline proof seam. Production
 * transport selection remains M2-G.
 */
internal fun interface ExternalRouteBinding {
    fun openConnection(url: URL): HttpURLConnection
}
