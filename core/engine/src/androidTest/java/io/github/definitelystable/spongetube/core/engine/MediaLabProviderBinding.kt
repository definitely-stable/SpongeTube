package io.github.definitelystable.spongetube.core.engine

import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingRefresher
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingSnapshot
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryMaterial
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryMaterialRefresh
import io.github.definitelystable.spongetube.core.engine.recovery.ProviderSignal
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val LAB_GENERATION = Regex("^gen-[1-9][0-9]*$")

/**
 * Media Lab provider-local delivery material (androidTest only).
 *
 * [generation] selects the provider generation and [resources] maps every
 * provider media path this material can serve to the length the provider
 * serves for it. It is adapter material: opaque to core, never persisted,
 * never part of `FetchKey`/`ExtentSpec` and never evidence. `toString()` keeps
 * only the opaque generation and the resource count.
 */
internal class LabDeliveryMaterial(
    val generation: String,
    val resources: Map<String, Long>,
) : DeliveryMaterial {
    init {
        require(LAB_GENERATION.matches(generation)) {
            "generation must match gen-N: $generation"
        }
        require(resources.isNotEmpty()) { "lab material needs at least one resource" }
        require(resources.all { (path, length) -> path.startsWith("/") && length > 0 }) {
            "lab material resources are absolute provider paths with positive lengths"
        }
    }

    override fun toString(): String =
        "LabDeliveryMaterial(generation=$generation, resources=${resources.size})"
}

/**
 * Lab adapter mapping from one provider-local material to the locator of
 * [resourcePath] inside its generation. Null means the material cannot serve
 * that resource, so the executor fails closed before any physical request.
 */
internal fun labHttpRangeTarget(
    material: DeliveryMaterial,
    originBaseUrl: String,
    resourcePath: String,
): HttpRangeTarget? {
    val lab = material as? LabDeliveryMaterial ?: return null
    val length = lab.resources[resourcePath] ?: return null
    return HttpRangeTarget(
        url = URL("$originBaseUrl/provider/${lab.generation}$resourcePath"),
        resourceLength = length,
    )
}

/**
 * Lab adapter signal: only the explicit stale-binding header on a 4xx refines
 * an otherwise non-retryable rejection. A bare status never does.
 */
internal fun labProviderSignal(
    statusCode: Int,
    header: (String) -> String?,
): ProviderSignal =
    if (statusCode in 400..499 && header(LAB_BINDING_HEADER) == LAB_BINDING_STALE) {
        ProviderSignal.BINDING_STALE_CONFIRMED
    } else {
        ProviderSignal.NONE
    }

/**
 * One provider refresh operation against the Media Lab simulator:
 * `POST <origin>/provider/refresh` with the current generation. A non-200
 * response, an unparseable body or a transport failure is one
 * [DeliveryMaterialRefresh.Failed]; refreshed material that cannot serve every
 * known resource of [current] at its exact length is
 * [DeliveryMaterialRefresh.Incompatible]. The raw response is never retained
 * and no URL or header value reaches evidence.
 *
 * Blocking I/O runs on [Dispatchers.IO] with the same disconnect watchdog as
 * the range executor, so cancelling the caller abandons the operation.
 */
internal class LabRefresher(
    private val originBaseUrl: String,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
) : DeliveryBindingRefresher {
    init {
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be > 0" }
        require(readTimeoutMs > 0) { "readTimeoutMs must be > 0" }
    }

    override suspend fun refresh(
        current: DeliveryBindingSnapshot,
        refreshCorrelationId: String,
    ): DeliveryMaterialRefresh {
        val material = current.material as? LabDeliveryMaterial
            ?: return DeliveryMaterialRefresh.Failed
        return withContext(Dispatchers.IO) {
            val connection = (
                URL("$originBaseUrl$REFRESH_PATH").openConnection()
                    as HttpURLConnection
                ).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                useCaches = false
                doOutput = true
                setRequestProperty(GENERATION_HEADER, material.generation)
            }
            coroutineScope {
                // Blocking socket reads do not observe coroutine cancellation;
                // disconnecting from the cancelled scope aborts them.
                val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        connection.disconnect()
                    }
                }
                try {
                    connection.outputStream.use { it.write(ByteArray(0)) }
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        DeliveryMaterialRefresh.Failed
                    } else {
                        val body = connection.inputStream.use {
                            it.readBytes().toString(Charsets.UTF_8)
                        }
                        parseRefresh(body, material)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Exactly one provider operation, never a retry.
                    DeliveryMaterialRefresh.Failed
                } finally {
                    watchdog.cancel()
                }
            }
        }
    }

    private fun parseRefresh(
        body: String,
        current: LabDeliveryMaterial,
    ): DeliveryMaterialRefresh {
        val json = try {
            JSONObject(body)
        } catch (_: Exception) {
            return DeliveryMaterialRefresh.Failed
        }
        val generation = json.optString(GENERATION_KEY, "")
        if (!LAB_GENERATION.matches(generation)) {
            return DeliveryMaterialRefresh.Failed
        }
        val resourcesJson = json.optJSONObject(RESOURCES_KEY)
            ?: return DeliveryMaterialRefresh.Failed
        val refreshed = linkedMapOf<String, Long>()
        val paths = resourcesJson.keys()
        while (paths.hasNext()) {
            val path = paths.next()
            val length = resourcesJson.optLong(path, -1L)
            if (length <= 0) {
                return DeliveryMaterialRefresh.Failed
            }
            refreshed[path] = length
        }
        // Refreshed material must still serve every resource of the immutable
        // work at its exact length; a differing or missing known resource
        // fails closed instead of changing the work identity.
        for ((path, length) in current.resources) {
            if (refreshed[path] != length) {
                return DeliveryMaterialRefresh.Incompatible
            }
        }
        return DeliveryMaterialRefresh.Material(
            LabDeliveryMaterial(generation = generation, resources = refreshed),
        )
    }

    private companion object {
        const val REFRESH_PATH = "/provider/refresh"
        const val GENERATION_HEADER = "X-Sponge-Provider-Generation"
        const val GENERATION_KEY = "generation"
        const val RESOURCES_KEY = "resources"
    }
}

private const val LAB_BINDING_HEADER = "X-Sponge-Provider-Binding"
private const val LAB_BINDING_STALE = "STALE"
