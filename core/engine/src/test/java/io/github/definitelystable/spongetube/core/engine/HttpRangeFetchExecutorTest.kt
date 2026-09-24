package io.github.definitelystable.spongetube.core.engine

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentSpec
import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

// 13
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class HttpRangeFetchExecutorTest {
    private val body = ByteArray(RESOURCE_LENGTH) { (it * 31).toByte() }
    private val rangeHeaders = CopyOnWriteArrayList<String?>()
    private val attemptHeaders = CopyOnWriteArrayList<String?>()
    private val transportCorrelations = CopyOnWriteArrayList<String>()

    @Volatile
    private var mode = Mode.PARTIAL

    private val server: HttpServer = HttpServer.create(
        InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
        0,
    ).apply {
        createContext("/fixtures/") { exchange -> serve(exchange) }
        start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun partialResponseWithMatchingContentRangeSucceeds() {
        val (disposition, received) = execute(start = 100, endExclusive = 1_100)

        assertEquals(
            FetchAttemptDisposition.Success(transportCorrelationId = "lab-1"),
            disposition,
        )
        assertArrayEquals(body.copyOfRange(100, 1_100), received)
        assertEquals("bytes=100-1099", rangeHeaders.single())
        assertEquals("1", attemptHeaders.single())
        assertEquals(listOf("lab-1"), transportCorrelations.toList())
    }

    @Test
    fun wholeResourceUnitRequestsExplicitFullRange() {
        val (disposition, received) = execute(start = null, endExclusive = null)

        assertEquals(FetchAttemptDisposition.Success("lab-1"), disposition)
        assertArrayEquals(body, received)
        assertEquals("bytes=0-${RESOURCE_LENGTH - 1}", rangeHeaders.single())
    }

    @Test
    fun fullBodyToRangeRequestIsRejectedNotAppended() {
        mode = Mode.FULL_200

        val (disposition, received) = execute(start = 100, endExclusive = 1_100)

        assertEquals(
            FetchAttemptDisposition.Failure(
                kind = FetchOutcomeKind.RANGE_REJECTED,
                retryable = false,
                transportCorrelationId = "lab-1",
            ),
            disposition,
        )
        assertEquals(0, received.size)
    }

    @Test
    fun mismatchedContentRangeStartIsRejected() {
        mode = Mode.WRONG_START

        val (disposition, received) = execute(start = 100, endExclusive = 1_100)

        assertEquals(
            FetchAttemptDisposition.Failure(
                kind = FetchOutcomeKind.RANGE_REJECTED,
                retryable = false,
                transportCorrelationId = "lab-1",
            ),
            disposition,
        )
        assertEquals(0, received.size)
    }

    @Test
    fun serverErrorIsRetryableTransportFailure() {
        mode = Mode.SERVER_ERROR

        val (disposition, _) = execute(start = 0, endExclusive = 10)

        assertEquals(
            FetchAttemptDisposition.Failure(
                kind = FetchOutcomeKind.RETRYABLE_TRANSPORT_FAILURE,
                retryable = true,
                transportCorrelationId = "lab-1",
            ),
            disposition,
        )
        assertEquals(listOf("lab-1"), transportCorrelations.toList())
    }

    @Test
    fun contentRangeParserIsStrict() {
        assertEquals(
            HttpRangeFetchExecutor.ContentRange(0, 9, 10),
            HttpRangeFetchExecutor.ContentRange.parse("bytes 0-9/10"),
        )
        assertEquals(
            HttpRangeFetchExecutor.ContentRange(5, 9, null),
            HttpRangeFetchExecutor.ContentRange.parse("bytes 5-9/*"),
        )
        assertNull(HttpRangeFetchExecutor.ContentRange.parse("bytes 9-5/10"))
        assertNull(HttpRangeFetchExecutor.ContentRange.parse("items 0-9/10"))
        assertNull(HttpRangeFetchExecutor.ContentRange.parse(null))
    }

    private fun execute(
        start: Long?,
        endExclusive: Long?,
    ): Pair<FetchAttemptDisposition, ByteArray> {
        val length = if (start == null) RESOURCE_LENGTH.toLong() else endExclusive!! - start
        val request = FetchRequest(
            fetchKey = FetchKey("fixture:TEST/r"),
            extentSpec = ExtentSpec(
                mediaAssetId = MediaAssetId("fixture:TEST"),
                extentId = ExtentId("t:r"),
                trackId = "video",
                representationId = "v1",
                mediaStartUs = null,
                mediaEndUs = null,
                byteStart = start,
                byteEndExclusive = endExclusive,
                expectedLength = length,
            ),
        )
        val executor = HttpRangeFetchExecutor(
            targetFor = {
                HttpRangeTarget(
                    url = URL("http://127.0.0.1:${server.address.port}/fixtures/TEST/r"),
                    resourceLength = RESOURCE_LENGTH.toLong(),
                )
            },
            connectTimeoutMs = 5_000,
            readTimeoutMs = 5_000,
            chunkSize = 256,
        )
        val received = ByteArrayOutputStream()
        var expected = start ?: 0L
        val disposition = runBlocking {
            executor.executeCorrelated(
                request = request,
                attempt = 1,
                priority = MutableStateFlow(FetchPriority.PLAYBACK),
                onTransportCorrelation = { correlation ->
                    transportCorrelations += correlation
                },
            ) { chunk ->
                assertEquals(expected, chunk.byteStart)
                assertEquals("lab-1", chunk.transportCorrelationId)
                expected += chunk.bytes.size
                received.write(chunk.bytes)
            }
        }
        return disposition to received.toByteArray()
    }

    private fun serve(exchange: HttpExchange) {
        try {
            val range = exchange.requestHeaders.getFirst("Range")
            rangeHeaders += range
            attemptHeaders += exchange.requestHeaders.getFirst(HttpRangeFetchExecutor.ATTEMPT_HEADER)
            exchange.responseHeaders.add(HttpRangeFetchExecutor.LAB_REQUEST_HEADER, "lab-" + rangeHeaders.size)
            val match = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range ?: "")
            val first = match!!.groupValues[1].toInt()
            val last = match.groupValues[2].toInt()
            when (mode) {
                Mode.PARTIAL -> {
                    exchange.responseHeaders.add("Content-Range", "bytes $first-$last/$RESOURCE_LENGTH")
                    exchange.sendResponseHeaders(206, (last - first + 1).toLong())
                    exchange.responseBody.write(body, first, last - first + 1)
                }
                Mode.WRONG_START -> {
                    exchange.responseHeaders.add(
                        "Content-Range",
                        "bytes ${first + 1}-$last/$RESOURCE_LENGTH",
                    )
                    exchange.sendResponseHeaders(206, (last - first).toLong())
                    exchange.responseBody.write(body, first + 1, last - first)
                }
                Mode.FULL_200 -> {
                    exchange.sendResponseHeaders(200, RESOURCE_LENGTH.toLong())
                    exchange.responseBody.write(body)
                }
                Mode.SERVER_ERROR -> exchange.sendResponseHeaders(503, -1)
            }
        } finally {
            exchange.close()
        }
    }

    private enum class Mode { PARTIAL, WRONG_START, FULL_200, SERVER_ERROR }

    private companion object {
        const val RESOURCE_LENGTH = 4_096
    }
}
