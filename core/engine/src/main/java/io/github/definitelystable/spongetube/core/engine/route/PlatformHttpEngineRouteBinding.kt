package io.github.definitelystable.spongetube.core.engine.route

import android.annotation.TargetApi
import android.net.http.HttpEngine
import android.net.http.UrlRequest
import java.net.URL
import java.util.concurrent.Executor

/**
 * API 34+ request-scoped exact-route capability, never persisted or exported.
 *
 * An evaluator cannot obtain the raw Network or silently fall back to the
 * ambient process route. This is intentionally narrower than a network API.
 * Only AndroidRouteExecutionBinding can implement it for production routes.
 */
@TargetApi(34)
internal interface PlatformHttpEngineRouteBinding : RouteExecutionBinding {
    fun newBoundRequest(
        engine: HttpEngine,
        url: URL,
        executor: Executor,
        callback: UrlRequest.Callback,
    ): UrlRequest.Builder
}
