package io.github.definitelystable.spongetube.core.engine

import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingRevision
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingSnapshot
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryMaterial
import io.github.definitelystable.spongetube.core.engine.delivery.ProviderWallClock
import io.github.definitelystable.spongetube.core.engine.delivery.RetryAfter
import io.github.definitelystable.spongetube.core.engine.recovery.FailureObservation
import io.github.definitelystable.spongetube.core.engine.recovery.ProviderSignal
import io.github.definitelystable.spongetube.core.engine.recovery.RangeProtocolKind
import io.github.definitelystable.spongetube.core.engine.recovery.TransportIoKind
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class HttpRangeTarget(
    val url: URL,
    val resourceLength: Long,
) {
    init {
        require(resourceLength > 0) { "resourceLength must be > 0" }
    }
}

/**
 * Minimal deterministic-origin range executor for M1-E (plan D6), extended by
 * M2-D with delivery-binding awareness.
 *
 * One call = one physical origin request for exactly the FetchUnit range.
 * Accepts only `206` with a matching `Content-Range`; a `200` full body to a
 * range request is never appended. The Media Lab request id
 * (`X-Sponge-Lab-Request`) becomes the attempt transport correlation id.
 *
 * Since M2-C the executor only reports what it observed as a typed
 * [FailureObservation]: a received HTTP status is a provider-plane
 * [FailureObservation.HttpResponse], never a transport failure, and nothing
 * here decides retryability. Exception messages are never inspected.
 *
 * Since M2-D an owner that carries a delivery binding resolves its target
 * through the injected `bindingTargetFor` mapping and sends the selected
 * revision as `X-Sponge-Binding-Revision`; every received status that becomes
 * an [FailureObservation.HttpResponse] additionally carries the normalized
 * `Retry-After` observation (parsed, never retained raw), the injected
 * provider signal and the selected revision. The unbound path parses the same
 * observations with a null revision.
 *
 * This is not the production transport: HttpEngine/OkHttp/Cronet selection and
 * the lab provider mapping are injected. Nothing here is a performance claim.
 */
internal class HttpRangeFetchExecutor(
    private val targetFor: (FetchRequest) -> HttpRangeTarget?,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val openConnection: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
    private val bindingTargetFor: ((FetchRequest, DeliveryMaterial) -> HttpRangeTarget?)? = null,
    private val providerSignalFor:
        (statusCode: Int, header: (String) -> String?) -> ProviderSignal =
        { _, _ -> ProviderSignal.NONE },
    private val providerWallClock: ProviderWallClock = ProviderWallClock.SYSTEM,
) : DeliveryBoundFetchAttemptExecutor {
    init {
        require(connectTimeoutMs > 0)
        require(readTimeoutMs > 0)
        require(chunkSize > 0)
    }

    override suspend fun execute(
        request: FetchRequest,
        attempt: Int,
        priority: StateFlow<FetchPriority>,
        emitChunk: suspend (FetchNetworkChunk) -> Unit,
    ): FetchAttemptDisposition =
        executeCorrelated(
            request = request,
            attempt = attempt,
            priority = priority,
            onTransportCorrelation = { },
            emitChunk = emitChunk,
        )

    override suspend fun executeCorrelated(
        request: FetchRequest,
        attempt: Int,
        priority: StateFlow<FetchPriority>,
        onTransportCorrelation: suspend (String) -> Unit,
        emitChunk: suspend (FetchNetworkChunk) -> Unit,
    ): FetchAttemptDisposition =
        executeCorrelatedWithAdmission(
            request = request,
            attempt = attempt,
            priority = priority,
            onPhysicalAttemptStart = { },
            onTransportCorrelation = onTransportCorrelation,
            emitChunk = emitChunk,
        )

    override suspend fun executeCorrelatedWithAdmission(
        request: FetchRequest,
        attempt: Int,
        priority: StateFlow<FetchPriority>,
        onPhysicalAttemptStart: () -> Unit,
        onTransportCorrelation: suspend (String) -> Unit,
        emitChunk: suspend (FetchNetworkChunk) -> Unit,
    ): FetchAttemptDisposition =
        executeWithTarget(
            request = request,
            attempt = attempt,
            onPhysicalAttemptStart = onPhysicalAttemptStart,
            onTransportCorrelation = onTransportCorrelation,
            emitChunk = emitChunk,
            resolveTarget = { targetFor(request) },
            bindingRevision = null,
        )

    override suspend fun executeWithDeliveryBinding(
        request: FetchRequest,
        attempt: Int,
        priority: StateFlow<FetchPriority>,
        deliveryBinding: DeliveryBindingSnapshot,
        onPhysicalAttemptStart: () -> Unit,
        onTransportCorrelation: suspend (String) -> Unit,
        emitChunk: suspend (FetchNetworkChunk) -> Unit,
    ): FetchAttemptDisposition =
        executeWithTarget(
            request = request,
            attempt = attempt,
            onPhysicalAttemptStart = onPhysicalAttemptStart,
            onTransportCorrelation = onTransportCorrelation,
            emitChunk = emitChunk,
            resolveTarget = { bindingTargetFor?.invoke(request, deliveryBinding.material) },
            bindingRevision = deliveryBinding.revision,
        )

    /**
     * One physical origin request shared by the bound and unbound paths; only
     * target resolution and the selected revision differ. Local preflight
     * ([resolveTarget] and the range checks) happens before admission: no
     * socket exists yet, so it never consumes REMOTE_ATTEMPT.
     */
    private suspend fun executeWithTarget(
        request: FetchRequest,
        attempt: Int,
        onPhysicalAttemptStart: () -> Unit,
        onTransportCorrelation: suspend (String) -> Unit,
        emitChunk: suspend (FetchNetworkChunk) -> Unit,
        resolveTarget: () -> HttpRangeTarget?,
        bindingRevision: DeliveryBindingRevision?,
    ): FetchAttemptDisposition {
        // These checks are local preflight. They must not consume
        // REMOTE_ATTEMPT because no socket/request exists yet.
        val target = resolveTarget()
            ?: return failure(
                FailureObservation.TransportIo(TransportIoKind.TARGET_UNRESOLVED),
                null,
            )
        val spec = request.extentSpec
        val start = spec.byteStart ?: 0L
        val endExclusive = spec.byteEndExclusive ?: (start + spec.expectedLength)
        if (endExclusive > target.resourceLength) {
            return failure(
                FailureObservation.RangeProtocolFailure(
                    RangeProtocolKind.REQUEST_OUTSIDE_RESOURCE,
                ),
                null,
            )
        }

        return withContext(Dispatchers.IO) {
            val connection = openConnection(target.url).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Range", "bytes=$start-${endExclusive - 1}")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty(FETCH_KEY_HEADER, request.fetchKey.value)
                setRequestProperty(ATTEMPT_HEADER, attempt.toString())
                if (bindingRevision != null) {
                    setRequestProperty(BINDING_REVISION_HEADER, bindingRevision.value)
                }
            }
            // Non-suspending admission is immediately adjacent to physical
            // transport start. A successful preflight therefore has exactly
            // one budget charge; failed preflight has none.
            onPhysicalAttemptStart()
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
                    transfer(
                        connection = connection,
                        start = start,
                        endExclusive = endExclusive,
                        resourceLength = target.resourceLength,
                        onTransportCorrelation = onTransportCorrelation,
                        emitChunk = emitChunk,
                        bindingRevision = bindingRevision,
                    )
                } finally {
                    watchdog.cancel()
                }
            }
        }
    }

    private suspend fun transfer(
        connection: HttpURLConnection,
        start: Long,
        endExclusive: Long,
        resourceLength: Long,
        onTransportCorrelation: suspend (String) -> Unit,
        emitChunk: suspend (FetchNetworkChunk) -> Unit,
        bindingRevision: DeliveryBindingRevision?,
    ): FetchAttemptDisposition {
        try {
            connection.connect()
        } catch (_: SocketTimeoutException) {
            currentCoroutineContext().ensureActive()
            return transport(TransportIoKind.CONNECT_TIMEOUT, null)
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            return transport(TransportIoKind.IO, null)
        }
        val status = try {
            connection.responseCode
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            return transport(error.transportKind(), null)
        }
        val correlation = connection.getHeaderField(LAB_REQUEST_HEADER)
        if (!correlation.isNullOrBlank()) {
            onTransportCorrelation(correlation)
        }

        when {
            status == HttpURLConnection.HTTP_PARTIAL -> Unit
            status == HttpURLConnection.HTTP_OK -> return failure(
                FailureObservation.RangeProtocolFailure(
                    RangeProtocolKind.FULL_BODY_FOR_RANGE_REQUEST,
                ),
                correlation,
            )
            status in 100..599 -> return failure(
                FailureObservation.HttpResponse(
                    statusCode = status,
                    retryAfter = RetryAfter.parse(
                        connection.getHeaderField(RETRY_AFTER_HEADER),
                        providerWallClock.nowUtcEpochMs(),
                    ),
                    providerSignal = providerSignalFor(status) { name ->
                        connection.getHeaderField(name)
                    },
                    deliveryBindingRevision = bindingRevision,
                ),
                correlation,
            )
            // Not a parseable HTTP status line.
            else -> return transport(TransportIoKind.IO, correlation)
        }

        val rawContentRange = connection.getHeaderField("Content-Range")
            ?: return rangeFailure(
                RangeProtocolKind.CONTENT_RANGE_MISSING,
                correlation,
            )
        val contentRange = ContentRange.parse(rawContentRange)
        if (
            contentRange == null ||
            contentRange.start != start ||
            contentRange.endInclusive != endExclusive - 1 ||
            (contentRange.total != null && contentRange.total != resourceLength)
        ) {
            return rangeFailure(
                RangeProtocolKind.CONTENT_RANGE_MISMATCH,
                correlation,
            )
        }
        val rawContentLength = connection.getHeaderField("Content-Length")
        val contentLength = rawContentLength?.trim()?.toLongOrNull()
        if (
            rawContentLength != null &&
            (contentLength == null ||
                contentLength < 0 ||
                contentLength != endExclusive - start)
        ) {
            return rangeFailure(
                RangeProtocolKind.RESPONSE_LENGTH_MISMATCH,
                correlation,
            )
        }

        var position = start
        try {
            connection.inputStream.use { input ->
                val buffer = ByteArray(chunkSize)
                while (position < endExclusive) {
                    currentCoroutineContext().ensureActive()
                    val want = minOf(
                        buffer.size.toLong(),
                        endExclusive - position,
                    ).toInt()
                    val read = input.read(buffer, 0, want)
                    if (read < 0) {
                        break
                    }
                    if (read == 0) {
                        continue
                    }
                    emitChunk(
                        FetchNetworkChunk(
                            byteStart = position,
                            bytes = buffer.copyOf(read),
                            transportCorrelationId = correlation,
                        ),
                    )
                    position += read
                }
            }
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            return transport(error.transportKind(), correlation)
        }

        if (position != endExclusive) {
            return transport(TransportIoKind.PREMATURE_EOF, correlation)
        }
        return FetchAttemptDisposition.Success(
            transportCorrelationId = correlation,
        )
    }

    private fun transport(
        kind: TransportIoKind,
        transportCorrelationId: String?,
    ): FetchAttemptDisposition =
        failure(FailureObservation.TransportIo(kind), transportCorrelationId)

    private fun rangeFailure(
        kind: RangeProtocolKind,
        transportCorrelationId: String?,
    ): FetchAttemptDisposition =
        failure(FailureObservation.RangeProtocolFailure(kind), transportCorrelationId)

    private fun failure(
        observation: FailureObservation,
        transportCorrelationId: String?,
    ): FetchAttemptDisposition =
        FetchAttemptDisposition.Failure(
            observation = observation,
            transportCorrelationId = transportCorrelationId,
        )

    /** Type-based only; exception messages are never inspected. */
    private fun IOException.transportKind(): TransportIoKind =
        when (this) {
            is SocketTimeoutException -> TransportIoKind.READ_TIMEOUT
            is SocketException -> TransportIoKind.CONNECTION_RESET
            else -> TransportIoKind.IO
        }

    internal data class ContentRange(
        val start: Long,
        val endInclusive: Long,
        val total: Long?,
    ) {
        companion object {
            private val PATTERN = Regex("^bytes (\\d+)-(\\d+)/(\\d+|\\*)$")

            fun parse(header: String?): ContentRange? {
                val match = PATTERN.matchEntire(header?.trim() ?: return null)
                    ?: return null
                val (first, last, total) = match.destructured
                val parsed = ContentRange(
                    start = first.toLongOrNull() ?: return null,
                    endInclusive = last.toLongOrNull() ?: return null,
                    total = if (total == "*") null else total.toLongOrNull() ?: return null,
                )
                return parsed.takeIf { it.endInclusive >= it.start }
            }
        }
    }

    companion object {
        const val LAB_REQUEST_HEADER = "X-Sponge-Lab-Request"
        const val FETCH_KEY_HEADER = "X-Sponge-Fetch-Key"
        const val ATTEMPT_HEADER = "X-Sponge-Attempt"
        const val BINDING_REVISION_HEADER = "X-Sponge-Binding-Revision"
        private const val RETRY_AFTER_HEADER = "Retry-After"
        private const val DEFAULT_CHUNK_SIZE = 16 * 1024
    }
}
