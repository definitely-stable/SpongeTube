package io.github.definitelystable.spongetube.core.engine

import android.annotation.TargetApi
import android.content.Context
import android.net.http.HttpEngine
import android.net.http.UrlRequest
import android.os.Build
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    private val worker = Executors.newSingleThreadExecutor() { task ->
        Thread(task, "sponge-http-engine-callback").apply { isDaemon = true }
    }
    val dispatcher = worker.asCoroutineDispatcher()
    val callbackExecutor = worker
    private val pending = LinkedHashMap<UrlRequest, CompletableDeferred<Unit>>()
    private var closing = false
    private val shutdownResult = CompletableDeferred<Result<Unit>>()

    /**
     * Complete after the TERMINAL callback has returned, not from within it.
     * A single serial callback executor makes this an actual lifecycle barrier.
     * UrlRequest control operations also use this executor.
     */
    fun completeAfterCallback(terminal: CompletableDeferred<Unit>) {
        callbackExecutor.execute { terminal.complete(Unit) }
    }

    /** Non-suspending, atomic owner admission/start versus pool shutdown. */
    fun startTracked(
        request: UrlRequest,
        terminal: CompletableDeferred<Unit>,
        onPhysicalAttemptStart: () -> Unit,
        markStartInvoked: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (closing || pending[request] !== terminal) {
            false
        } else {
            onPhysicalAttemptStart()
            markStartInvoked()
            request.start()
            true
        }
    }

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
            if (closing) null else {
                closing = true
                pending.toMap()
            }
        }
        if (snapshot == null) {
            shutdownResult.await().getOrThrow()
            return
        }
        withContext(NonCancellable) {
            try {
                // Android requires request control on the builder's Executor.
                // startTracked + closing share a lock; no late owner may start.
                withContext(dispatcher) {
                    snapshot.forEach { (request, terminal) ->
                        if (!terminal.isCompleted) request.cancel()
                    }
                }
                withTimeout(SHUTDOWN_TIMEOUT_MS) {
                    snapshot.values.forEach { it.await() }
                }
                withContext(Dispatchers.IO) { engine.shutdown() }
                dispatcher.close()
                shutdownResult.complete(Result.success(Unit))
            } catch (failure: Throwable) {
                shutdownResult.complete(Result.failure(failure))
                // A failed/unknown shutdown must never be called clean.
                throw failure
            }
        }
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
