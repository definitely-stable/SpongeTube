package io.github.definitelystable.spongetube.core.engine

import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingSnapshot
import java.net.HttpURLConnection
import java.net.URL

/**
 * Opaque process-local binding for one exact platform route.
 *
 * This is execution context only. It must never participate in FetchKey,
 * ExtentSpec, persisted media identity or portable evidence.
 */
internal interface FetchRouteExecutionBinding

/**
 * HttpURLConnection capability used only by the M2-F HttpRange proof seam.
 * M2-G remains responsible for production transport selection.
 */
internal fun interface HttpUrlConnectionRouteExecutionBinding : FetchRouteExecutionBinding {
    fun openConnection(url: URL): HttpURLConnection
}

/**
 * Physical-attempt execution context. Neither field is immutable work identity.
 *
 * A route-bound owner must fail closed when its executor cannot honor
 * [routeBinding]; it must never fall back to the ambient default network.
 */
internal data class FetchAttemptExecutionContext(
    val deliveryBinding: DeliveryBindingSnapshot? = null,
    val routeBinding: FetchRouteExecutionBinding? = null,
) {
    val isBound: Boolean
        get() = deliveryBinding != null || routeBinding != null
}
