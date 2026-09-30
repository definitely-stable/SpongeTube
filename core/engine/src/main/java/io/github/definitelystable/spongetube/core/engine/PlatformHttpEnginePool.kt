package io.github.definitelystable.spongetube.core.engine

import android.annotation.TargetApi
import android.content.Context
import android.net.http.HttpEngine
import android.net.http.UrlRequest
import android.os.Build
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Experimental, session-scoped owner of a shared engine and asynchronous callback executor.
 * A request is always bound individually; the engine is NEVER bound to a Network.
 *
 * shutdown() first cancels and joins every terminal callback, then shuts down the engine.
 * Never share this experimental pool with the control backend's connection state.
 */
@TargetApi(34)
internal class PlatformHttpEnginePool private constructor(
    val engine: HttpEngine,
) {
    private val lock = Any()
    private val worker = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "sponge-http-engine-callback").apply { isDaemon = true }
    }
    val dispatcher = worker.asCoroutineDispatcher()
    val callbackExecutor = worker
    private val pending = LinkedHashMap<UrlRequest, CompletableDeferred<Unit>>()
    private var closing = false

    /** Process-local diagnostic: a terminal callback is required before removal. */
    val activeRequestCount: Int
        get() = synchronized(lock) { pending.size }

    fun track(request: UrlRequest, terminal: CompletableDeferred<Unit>): Boolean =
        synchronized(lock) {
            if (closing) {
                false
            } else {
                pending[request] = terminal
                terminal.invokeOnCompletion {
                    synchronized(lock) { pending.remove(request) }
                }
                true
            }
        }

    suspend fun shutdown() {
        val snapshot = synchronized(lock) {
            closing = true
            pending.toMap()
        }
        snapshot.keys.forEach { it.cancel() }
        // Engine.shutdown() explicitly forbids active requests and may block.
        // Do not report clean shutdown if a terminal callback failed to arrive.
        withTimeout(SHUTDOWN_TIMEOUT_MS) {
            snapshot.values.forEach { it.await() }
        }
        withContext(Dispatchers.IO) { engine.shutdown() }
        dispatcher.close()
    }

    val backendVersion: String
        get() = HttpEngine.getVersionString()

    companion object {
        private const val SHUTDOWN_TIMEOUT_MS = 15_000L

        /**
         * G1 canonical comparisons target API 36. API 23/34 remain supported:
         * a missing implementation is unavailable, NOT a measured slow candidate.
         */
        fun createIfAvailable(context: Context): PlatformHttpEnginePool? {
            if (Build.VERSION.SDK_INT < 34) return null
            return try {
                PlatformHttpEnginePool(
                    HttpEngine.Builder(context.applicationContext)
                        .setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISABLED, 0)
                        .build(),
                )
            } catch (_: UnsupportedOperationException) {
                null
            } catch (_: LinkageError) {
                null
            }
        }
    }
}
