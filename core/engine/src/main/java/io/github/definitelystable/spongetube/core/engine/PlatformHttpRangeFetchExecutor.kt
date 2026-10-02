package io.github.definitelystable.spongetube.core.engine

import android.annotation.TargetApi
import android.net.http.HttpException
import android.net.http.NetworkException
import android.net.http.UrlRequest
import android.net.http.UrlResponseInfo
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingSnapshot
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryMaterial
import io.github.definitelystable.spongetube.core.engine.delivery.ProviderWallClock
import io.github.definitelystable.spongetube.core.engine.delivery.RetryAfter
import io.github.definitelystable.spongetube.core.engine.recovery.FailureObservation
import io.github.definitelystable.spongetube.core.engine.recovery.ProviderSignal
import io.github.definitelystable.spongetube.core.engine.recovery.RangeProtocolKind
import io.github.definitelystable.spongetube.core.engine.recovery.TransportIoKind
import io.github.definitelystable.spongetube.core.engine.route.PlatformHttpEngineRouteBinding
import io.github.definitelystable.spongetube.core.engine.route.RouteExecutionBinding
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Evaluation only. All requests use the same RecoveryCoordinator -> FetchBroker
 * owner/publisher path as HttpURLConnection; no independent retry or fallback.
 *
 * One pending read per request: the owner validates/emits each chunk before
 * requesting another. A terminal read detects unannounced response overflow.
 */
@TargetApi(34)
internal class PlatformHttpRangeFetchExecutor(
    private val pool: PlatformHttpEnginePool,
    private val targetFor: (FetchRequest) -> HttpRangeTarget?,
    private val bindingTargetFor: ((FetchRequest, DeliveryMaterial) -> HttpRangeTarget?)? = null,
    private val firstResponseTimeoutMs: Long = 15_000L,
    private val readTimeoutMs: Long = 15_000L,
    private val chunkSize: Int = 16 * 1024,
    private val providerSignalFor:
        (statusCode: Int, header: (String) -> String?) -> ProviderSignal =
        { _, _ -> ProviderSignal.NONE },
    private val providerWallClock: ProviderWallClock = ProviderWallClock.SYSTEM,
    private val onProtocolObserved: (String) -> Unit = { },
    private val onTerminalObserved: (PlatformHttpTerminal) -> Unit = { },
    private val phaseObserver: TransportPhaseObserver = TransportPhaseObserver.NONE,
) : RouteBoundFetchAttemptExecutor {
    init { require(firstResponseTimeoutMs > 0 && readTimeoutMs > 0 && chunkSize > 0) }

    // Route-less evaluation is a contract failure, never the ambient network.
    override suspend fun execute(
        request: FetchRequest, attempt: Int, priority: StateFlow<FetchPriority>,
        emitChunk: suspend (FetchNetworkChunk) -> Unit,
    ): FetchAttemptDisposition = unbound()

    override suspend fun executeCorrelated(
        request: FetchRequest, attempt: Int, priority: StateFlow<FetchPriority>,
        onTransportCorrelation: suspend (String) -> Unit,
        emitChunk: suspend (FetchNetworkChunk) -> Unit,
    ): FetchAttemptDisposition = unbound()

    override suspend fun executeCorrelatedWithAdmission(
        request: FetchRequest, attempt: Int, priority: StateFlow<FetchPriority>,
        onPhysicalAttemptStart: () -> Unit,
        onTransportCorrelation: suspend (String) -> Unit,
        emitChunk: suspend (FetchNetworkChunk) -> Unit,
    ): FetchAttemptDisposition = unbound()

    override suspend fun executeWithRouteBinding(
        request: FetchRequest, attempt: Int, priority: StateFlow<FetchPriority>,
        routeBinding: RouteExecutionBinding,
        deliveryBinding: DeliveryBindingSnapshot?,
        onPhysicalAttemptStart: () -> Unit,
        onTransportCorrelation: suspend (String) -> Unit,
        emitChunk: suspend (FetchNetworkChunk) -> Unit,
    ): FetchAttemptDisposition {
        val exactRoute = routeBinding as? PlatformHttpEngineRouteBinding ?: return unbound()
        val target = if (deliveryBinding == null) {
            targetFor(request)
        } else {
            bindingTargetFor?.invoke(request, deliveryBinding.material)
        } ?: return unbound()
        val spec = request.extentSpec
        val start = spec.byteStart ?: 0L
        val end = spec.byteEndExclusive ?: try {
            Math.addExact(start, spec.expectedLength)
        } catch (_: ArithmeticException) {
            return rangeFailure(RangeProtocolKind.REQUEST_OUTSIDE_RESOURCE)
        }
        if (start < 0 || end <= start || end > target.resourceLength ||
            target.url.protocol !in setOf("http", "https")
        ) return rangeFailure(RangeProtocolKind.REQUEST_OUTSIDE_RESOURCE)

        currentCoroutineContext().ensureActive()
        val events = Channel<Event>(Channel.UNLIMITED)
        val terminal = CompletableDeferred<Unit>()
        // Exactly one outstanding read. Reusing one direct buffer avoids one
        // off-heap allocation per network chunk without changing backpressure.
        val readBuffer = ByteBuffer.allocateDirect(chunkSize)
        val callback = object : UrlRequest.Callback {
            private fun confirm(kind: PlatformHttpTerminal) {
                try {
                    onTerminalObserved(kind)
                } finally {
                    // Single callback executor: ack runs AFTER callback returns.
                    pool.completeAfterCallback(terminal)
                }
            }
            override fun onRedirectReceived(
                request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String,
            ) { events.trySend(Event.Redirect(info)) }

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                events.trySend(Event.Headers(info))
            }

            override fun onReadCompleted(
                request: UrlRequest, info: UrlResponseInfo, buffer: ByteBuffer,
            ) {
                buffer.flip()
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                events.trySend(Event.Bytes(bytes))
            }

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                events.trySend(Event.Done)
                confirm(PlatformHttpTerminal.SUCCEEDED)
            }

            override fun onFailed(
                request: UrlRequest, info: UrlResponseInfo?, error: HttpException,
            ) {
                events.trySend(Event.Failed(error))
                confirm(PlatformHttpTerminal.FAILED)
            }

            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                events.trySend(Event.Cancelled)
                confirm(PlatformHttpTerminal.CANCELED)
            }
        }

        // All local preflight completes before FetchBroker charges REMOTE_ATTEMPT.
        // Only AndroidRouteExecutionBinding can supply the approved Network.
        val underlying = try {
            withContext(pool.dispatcher) {
                exactRoute.newBoundRequest(
                    pool.engine, target.url, pool.callbackExecutor, callback,
                )
                    .setHttpMethod("GET")
                    .addHeader("Range", "bytes=" + start + "-" + (end - 1))
                    .addHeader("Accept-Encoding", "identity")
                    .addHeader(HttpRangeFetchExecutor.FETCH_KEY_HEADER, request.fetchKey.value)
                    .addHeader(HttpRangeFetchExecutor.ATTEMPT_HEADER, attempt.toString())
                    .apply {
                        deliveryBinding?.let {
                            addHeader(HttpRangeFetchExecutor.BINDING_REVISION_HEADER, it.revision.value)
                        }
                    }
                    .setCacheDisabled(true)
                    .build()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return unbound()
        }
        if (!pool.track(underlying, terminal)) return transport(TransportIoKind.IO)

        var startInvoked = false
        var sawHeaders = false
        var firstBodyObserved = false
        var position = start
        var correlation: String? = null
        try {
            val started = try {
                withContext(pool.dispatcher) {
                    currentCoroutineContext().ensureActive()
                    pool.startTracked(
                        underlying, terminal, onPhysicalAttemptStart,
                        markStartInvoked = { startInvoked = true },
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // A rejected start is internal contract failure, never a
                // transport timeout or an authorized logical retry.
                return failure(FailureObservation.InternalFailure)
            }
            if (!started) return failure(FailureObservation.InternalFailure)
            while (true) {
                val event = try {
                    withTimeout(if (sawHeaders) readTimeoutMs else firstResponseTimeoutMs) {
                        events.receive()
                    }
                } catch (_: TimeoutCancellationException) {
                    return transport(
                        if (sawHeaders) TransportIoKind.READ_TIMEOUT else TransportIoKind.CONNECT_TIMEOUT,
                        correlation,
                    )
                }
                when (event) {
                    is Event.Headers, is Event.Redirect -> {
                        val info = when (event) {
                            is Event.Headers -> event.info
                            is Event.Redirect -> event.info
                            else -> error("unreachable")
                        }
                        correlation = header(info, HttpRangeFetchExecutor.LAB_REQUEST_HEADER)
                        correlation?.let { onTransportCorrelation(it) }
                        // UrlRequest.followRedirect() is deliberately never called.
                        if (event is Event.Redirect) {
                            return failure(httpResponse(info, deliveryBinding), correlation)
                        }
                        observePhase(
                            request.fetchKey,
                            attempt,
                            TransportPhaseKind.RESPONSE_HEADERS,
                        )
                        sawHeaders = true
                        // Descriptive observation only: never a ranking input.
                        onProtocolObserved(normalizedPlatformProtocol(info.negotiatedProtocol))
                        if (info.wasCached()) return transport(TransportIoKind.IO, correlation)
                        when (info.httpStatusCode) {
                            206 -> Unit
                            200 -> return rangeFailure(
                                RangeProtocolKind.FULL_BODY_FOR_RANGE_REQUEST, correlation,
                            )
                            in 100..599 -> return failure(
                                httpResponse(info, deliveryBinding), correlation,
                            )
                            else -> return transport(TransportIoKind.IO, correlation)
                        }
                        val invalid = validatePlatformPartialRange(
                            start = start,
                            endExclusive = end,
                            resourceLength = target.resourceLength,
                            contentRange = header(info, "Content-Range"),
                            contentLength = header(info, "Content-Length"),
                        )
                        if (invalid != null) return rangeFailure(invalid, correlation)
                        withContext(pool.dispatcher) {
                            underlying.read(readBuffer.apply { clear() })
                        }
                    }
                    is Event.Bytes -> {
                        if (!sawHeaders) {
                            return rangeFailure(RangeProtocolKind.RESPONSE_LENGTH_MISMATCH, correlation)
                        }
                        if (event.bytes.size.toLong() > end - position) {
                            return rangeFailure(
                                RangeProtocolKind.RESPONSE_BYTES_OUTSIDE_RANGE, correlation,
                            )
                        }
                        if (event.bytes.isNotEmpty()) {
                            if (!firstBodyObserved) {
                                firstBodyObserved = true
                                observePhase(
                                    request.fetchKey,
                                    attempt,
                                    TransportPhaseKind.FIRST_BODY_BYTES,
                                )
                            }
                            emitChunk(FetchNetworkChunk(
                                byteStart = position,
                                bytes = event.bytes,
                                transportCorrelationId = correlation,
                            ))
                            position += event.bytes.size
                        }
                        // Read once more after the expected extent ends. An
                        // oversized body must never be published as success.
                        withContext(pool.dispatcher) {
                            underlying.read(readBuffer.apply { clear() })
                        }
                    }
                    Event.Done -> return if (position == end) {
                        observePhase(
                            request.fetchKey,
                            attempt,
                            TransportPhaseKind.RESPONSE_BODY_COMPLETE,
                        )
                        FetchAttemptDisposition.Success(transportCorrelationId = correlation)
                    } else transport(TransportIoKind.PREMATURE_EOF, correlation)
                    is Event.Failed -> return transport(
                        errorKind(event.error, sawHeaders), correlation,
                    )
                    Event.Cancelled -> {
                        currentCoroutineContext().ensureActive()
                        return transport(TransportIoKind.IO, correlation)
                    }
                }
            }
        } finally {
            if (!startInvoked) terminal.complete(Unit)
            if (startInvoked && !terminal.isCompleted) {
                withContext(NonCancellable) {
                    withContext(pool.dispatcher) { underlying.cancel() }
                    // If start() failed after being invoked and the platform
                    // never sends a callback, preserve the tracked leak as an
                    // explicit shutdown failure rather than synthesizing an ack.
                    withTimeoutOrNull(CANCEL_ACK_TIMEOUT_MS) { terminal.await() }
                }
            }
            events.close()
        }
    }

    private fun observePhase(
        fetchKey: FetchKey,
        attempt: Int,
        kind: TransportPhaseKind,
    ) {
        // Measurement must never change transport correctness or recovery.
        runCatching {
            phaseObserver.onPhase(
                TransportPhaseObservation(
                    fetchKey = fetchKey,
                    attempt = attempt,
                    kind = kind,
                ),
            )
        }
    }

    private fun header(info: UrlResponseInfo, name: String): String? =
        info.headers.asMap.entries.firstOrNull {
            it.key.equals(name, ignoreCase = true)
        }?.value?.singleOrNull()

    private fun httpResponse(
        info: UrlResponseInfo, delivery: DeliveryBindingSnapshot?,
    ): FailureObservation = FailureObservation.HttpResponse(
        statusCode = info.httpStatusCode,
        retryAfter = RetryAfter.parse(
            header(info, "Retry-After"), providerWallClock.nowUtcEpochMs(),
        ),
        providerSignal = providerSignalFor(info.httpStatusCode) { header(info, it) },
        deliveryBindingRevision = delivery?.revision,
    )

    private fun errorKind(error: HttpException, sawHeaders: Boolean): TransportIoKind =
        when ((error as? NetworkException)?.errorCode) {
            NetworkException.ERROR_CONNECTION_TIMED_OUT -> TransportIoKind.CONNECT_TIMEOUT
            // Generic ERROR_TIMED_OUT follows connection establishment on
            // Android: report a request/read timeout, not vague I/O.
            NetworkException.ERROR_TIMED_OUT -> TransportIoKind.READ_TIMEOUT
            NetworkException.ERROR_CONNECTION_RESET,
            NetworkException.ERROR_CONNECTION_CLOSED -> TransportIoKind.CONNECTION_RESET
            else -> TransportIoKind.IO
        }

    private fun unbound(): FetchAttemptDisposition =
        transport(TransportIoKind.TARGET_UNRESOLVED)

    private fun transport(kind: TransportIoKind, correlation: String? = null): FetchAttemptDisposition =
        failure(FailureObservation.TransportIo(kind), correlation)

    private fun rangeFailure(
        kind: RangeProtocolKind, correlation: String? = null,
    ): FetchAttemptDisposition =
        failure(FailureObservation.RangeProtocolFailure(kind), correlation)

    private fun failure(
        observation: FailureObservation, correlation: String? = null,
    ): FetchAttemptDisposition =
        FetchAttemptDisposition.Failure(
            observation = observation, transportCorrelationId = correlation,
        )

    private sealed interface Event {
        data class Headers(val info: UrlResponseInfo) : Event
        data class Redirect(val info: UrlResponseInfo) : Event
        data class Bytes(val bytes: ByteArray) : Event
        data class Failed(val error: HttpException) : Event
        data object Cancelled : Event
        data object Done : Event
    }

    private companion object { const val CANCEL_ACK_TIMEOUT_MS = 15_000L }
}

/** Never retain raw protocol strings in portable evidence. */
internal fun normalizedPlatformProtocol(raw: String?): String {
    val value = raw?.lowercase(java.util.Locale.ROOT) ?: return "UNKNOWN"
    return when {
        value == "http/1.1" -> "HTTP_1_1"
        value == "h2" || value == "http/2" -> "HTTP_2"
        value == "h3" || value.startsWith("h3-") || value == "http/3" -> "HTTP_3"
        else -> "UNKNOWN"
    }
}

/** Observed platform terminal callback kind, never inferred from pool size. */
internal enum class PlatformHttpTerminal { SUCCEEDED, FAILED, CANCELED }

