package io.github.definitelystable.spongetube.core.engine.route

import java.net.HttpURLConnection
import java.net.URL

/**
 * Process-local execution capability for one exact platform route.
 *
 * The implementation may wrap an Android Network, but platform objects never
 * escape through this interface, enter stable identity or get serialized into
 * evidence. Opening a URL through this binding must fail rather than fall back
 * to the ambient/default route if the underlying platform route disappears.
 */
internal fun interface RouteExecutionBinding {
    fun openConnection(url: URL): HttpURLConnection
}
