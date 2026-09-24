package io.github.definitelystable.spongetube.core.engine

import java.io.IOException
import java.net.HttpURLConnection
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
 * Minimal deterministic-origin range executor for M1-E (plan D6).
 *
 * One call = one physical origin request for exactly the FetchUnit range.
 * Accepts only `206` with a matching `Content-Range`; a `200` full body to a
 * range request is never appended (RANGE_REJECTED). The Media Lab request id
 * (`X-Sponge-Lab-Request`) becomes the attempt transport correlation id.
 *
 * This is not the production transport: HttpEngine/OkHttp/Cronet selection is
 * M2 and nothing here is a performance claim.
 */
internal class HttpRangeFetchExecutor(
    private val targetFor: (FetchRequest) -> HttpRangeTarget?,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val openConnection: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) : FetchAttemptExecutor {
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
    ): FetchAttemptDisposition {
        val target = targetFor(request)
            ?: return failure(
                FetchOutcomeKind.TERMINAL_TRANSPORT_FAILURE,
                null,
            )
        val spec = request.extentSpec
        val start = spec.byteStart ?: 0L
        val endExclusive = spec.byteEndExclusive ?: (start + spec.expectedLength)
        if (endExclusive > target.resourceLength) {
            return failure(FetchOutcomeKind.RANGE_REJECTED, null)
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
                    transfer(
                        connection = connection,
                        start = start,
                        endExclusive = endExclusive,
                        resourceLength = target.resourceLength,
                        emitChunk = emitChunk,
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
        emitChunk: suspend (FetchNetworkChunk) -> Unit,
    ): FetchAttemptDisposition {
        val status = try {
            connection.responseCode
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            return retryable(null)
        }
        val correlation = connection.getHeaderField(LAB_REQUEST_HEADER)

        when {
            status == HttpURLConnection.HTTP_PARTIAL -> Unit
            status == HttpURLConnection.HTTP_OK ->
                return failure(FetchOutcomeKind.RANGE_REJECTED, correlation)
            status == HTTP_REQUEST_TIMEOUT ||
                status == HTTP_TOO_MANY_REQUESTS ||
                status >= 500 -> return retryable(correlation)
            else -> return failure(
                FetchOutcomeKind.TERMINAL_TRANSPORT_FAILURE,
                correlation,
            )
        }

        val contentRange = ContentRange.parse(
            connection.getHeaderField("Content-Range"),
        )
        if (
            contentRange == null ||
            contentRange.start != start ||
            contentRange.endInclusive != endExclusive - 1 ||
            (contentRange.total != null && contentRange.total != resourceLength)
        ) {
            return failure(FetchOutcomeKind.RANGE_REJECTED, correlation)
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
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            return retryable(correlation)
        }

        if (position != endExclusive) {
            return retryable(correlation)
        }
        return FetchAttemptDisposition.Success(
            transportCorrelationId = correlation,
        )
    }

    private fun retryable(
        transportCorrelationId: String?,
    ): FetchAttemptDisposition =
        FetchAttemptDisposition.Failure(
            kind = FetchOutcomeKind.RETRYABLE_TRANSPORT_FAILURE,
            retryable = true,
            transportCorrelationId = transportCorrelationId,
        )

    private fun failure(
        kind: FetchOutcomeKind,
        transportCorrelationId: String?,
    ): FetchAttemptDisposition =
        FetchAttemptDisposition.Failure(
            kind = kind,
            retryable = false,
            transportCorrelationId = transportCorrelationId,
        )

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
        private const val DEFAULT_CHUNK_SIZE = 16 * 1024
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
