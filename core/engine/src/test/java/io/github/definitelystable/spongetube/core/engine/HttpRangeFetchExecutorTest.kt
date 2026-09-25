package io.github.definitelystable.spongetube.core.engine

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.definitelystable.spongetube.core.engine.recovery.FailureClassifier
import io.github.definitelystable.spongetube.core.engine.recovery.FailureObservation
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryDecisionContext
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryDecisionKind
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryPolicy
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentSpec
import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import java.io.ByteArrayOutputStream
import java.io.File
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

// 13
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class HttpRangeFetchExecutorTest {
    private val body = ByteArray(RESOURCE_LENGTH) { (it * 31).toByte() }
    private val rangeHeaders = CopyOnWriteArrayList<String?>()
    private val attemptHeaders = CopyOnWriteArrayList<String?>()
    private val transportCorrelations = CopyOnWriteArrayList<String>()
    private val responseStatuses = CopyOnWriteArrayList<Int>()
    private val responseContentRanges = CopyOnWriteArrayList<String?>()

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

        assertRangeRejected(disposition, "lab-1")
        assertEquals(0, received.size)
    }

    @Test
    fun mismatchedContentRangeStartIsRejected() {
        mode = Mode.WRONG_START

        val (disposition, received) = execute(start = 100, endExclusive = 1_100)

        assertRangeRejected(disposition, "lab-1")
        assertEquals(0, received.size)
    }

    @Test
    fun mismatchedContentRangeTotalIsRejected() {
        mode = Mode.WRONG_TOTAL

        val (disposition, received) = execute(start = 100, endExclusive = 1_100)

        assertRangeRejected(disposition, "lab-1")
        assertEquals(0, received.size)
    }

    @Test
    fun serverErrorIsAProviderPlaneHttpObservationNotATransportFailure() {
        mode = Mode.SERVER_ERROR

        val (disposition, _) = execute(start = 0, endExclusive = 10)

        // M2-C: a received status is a provider-plane observation; the
        // executor carries no retryability.
        assertEquals(
            FetchAttemptDisposition.Failure(
                observation = FailureObservation.HttpResponse(503),
                transportCorrelationId = "lab-1",
            ),
            disposition,
        )
        assertEquals(listOf("lab-1"), transportCorrelations.toList())
    }

    @Test
    fun localTargetPreflightDoesNotAdmitPhysicalAttempt() {
        val request = FetchRequest(
            fetchKey = FetchKey("fixture:TEST/preflight"),
            extentSpec = ExtentSpec(
                mediaAssetId = MediaAssetId("fixture:TEST"),
                extentId = ExtentId("t:preflight"),
                trackId = "video",
                representationId = "v1",
                mediaStartUs = null,
                mediaEndUs = null,
                byteStart = 0,
                byteEndExclusive = 10,
                expectedLength = 10,
            ),
        )
        val executor = HttpRangeFetchExecutor(
            targetFor = { null },
            connectTimeoutMs = 5_000,
            readTimeoutMs = 5_000,
        )
        var admissions = 0

        val disposition = runBlocking {
            executor.executeCorrelatedWithAdmission(
                request = request,
                attempt = 1,
                priority = MutableStateFlow(FetchPriority.PLAYBACK),
                onPhysicalAttemptStart = { admissions += 1 },
                onTransportCorrelation = { },
                emitChunk = { },
            )
        }

        assertEquals(0, admissions)
        assertEquals(
            FetchAttemptDisposition.Failure(
                FailureObservation.TransportIo(
                    io.github.definitelystable.spongetube.core.engine.recovery.TransportIoKind
                        .TARGET_UNRESOLVED,
                ),
            ),
            disposition,
        )
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

    @Test
    fun canonicalContinuationVariantsProduceEvidence() {
        val observations = listOf(
            evidenceCase("MATCHING_206", Mode.PARTIAL, expectSuccess = true),
            evidenceCase("FULL_200", Mode.FULL_200, expectSuccess = false),
            evidenceCase("WRONG_START_206", Mode.WRONG_START, expectSuccess = false),
            evidenceCase("WRONG_TOTAL_206", Mode.WRONG_TOTAL, expectSuccess = false),
        )

        val output = File(ACC15_EVIDENCE_PATH)
        val parent = checkNotNull(output.parentFile)
        check(parent.isDirectory || parent.mkdirs()) {
            "failed to create ACC-15 evidence directory"
        }
        output.bufferedWriter().use { writer ->
            observations.forEach { observation ->
                writer.write(observation.toJsonLine())
                writer.newLine()
            }
        }
    }

    private fun evidenceCase(
        caseId: String,
        responseMode: Mode,
        expectSuccess: Boolean,
    ): EvidenceObservation {
        mode = responseMode
        val index = rangeHeaders.size
        val (disposition, received) = execute(start = 100, endExclusive = 1_100)

        // range-continuation-v1 keeps its M1 fields: `outcome` is the legacy
        // projection of the observation and `retryable` is whether the M2-C
        // recovery policy would retry it (never the executor's opinion).
        val (outcome, retryable, correlation) = when (disposition) {
            is FetchAttemptDisposition.Success ->
                Triple("SUCCESS", false, disposition.transportCorrelationId)
            is FetchAttemptDisposition.Failure ->
                Triple(
                    disposition.observation.legacyOutcomeKind().name,
                    policyWouldRetry(disposition.observation),
                    disposition.transportCorrelationId,
                )
        }

        if (expectSuccess) {
            assertEquals("SUCCESS", outcome)
            assertEquals(1_000, received.size)
        } else {
            assertEquals("RANGE_REJECTED", outcome)
            assertEquals(false, retryable)
            assertEquals(0, received.size)
        }

        return EvidenceObservation(
            caseId = caseId,
            requestedByteStart = 100,
            requestedByteEndExclusive = 1_100,
            resourceLength = RESOURCE_LENGTH,
            requestRange = checkNotNull(rangeHeaders[index]),
            responseStatus = responseStatuses[index],
            responseContentRange = responseContentRanges[index],
            outcome = outcome,
            retryable = retryable,
            emittedBytes = received.size,
            transportCorrelationId = checkNotNull(correlation),
        )
    }

    private fun assertRangeRejected(
        disposition: FetchAttemptDisposition,
        correlation: String,
    ) {
        disposition as FetchAttemptDisposition.Failure
        assertTrue(
            disposition.observation is FailureObservation.RangeProtocolFailure,
            disposition.toString(),
        )
        assertEquals(correlation, disposition.transportCorrelationId)
        assertEquals(FetchOutcomeKind.RANGE_REJECTED, disposition.observation.legacyOutcomeKind())
        assertFalse(policyWouldRetry(disposition.observation))
    }

    private fun policyWouldRetry(observation: FailureObservation): Boolean =
        RecoveryPolicy.DEFAULT.decide(
            FailureClassifier.classify(observation),
            observation,
            RecoveryDecisionContext(
                demandPresent = true,
                sessionClosing = false,
                remoteAttemptsRemaining = 1,
            ),
        ).kind == RecoveryDecisionKind.RETRY_AFTER_BACKOFF

    private fun execute(
        start: Long?,
        endExclusive: Long?,
    ): Pair<FetchAttemptDisposition, ByteArray> {
        val length =
            if (start == null) RESOURCE_LENGTH.toLong() else endExclusive!! - start
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
        val expectedCorrelation = "lab-${rangeHeaders.size + 1}"
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
                assertEquals(expectedCorrelation, chunk.transportCorrelationId)
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
            attemptHeaders +=
                exchange.requestHeaders.getFirst(HttpRangeFetchExecutor.ATTEMPT_HEADER)
            exchange.responseHeaders.add(
                HttpRangeFetchExecutor.LAB_REQUEST_HEADER,
                "lab-" + rangeHeaders.size,
            )
            val match = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range ?: "")
            val first = checkNotNull(match).groupValues[1].toInt()
            val last = match.groupValues[2].toInt()
            when (mode) {
                Mode.PARTIAL -> {
                    val contentRange = "bytes $first-$last/$RESOURCE_LENGTH"
                    responseStatuses += 206
                    responseContentRanges += contentRange
                    exchange.responseHeaders.add("Content-Range", contentRange)
                    exchange.sendResponseHeaders(206, (last - first + 1).toLong())
                    exchange.responseBody.write(body, first, last - first + 1)
                }
                Mode.WRONG_START -> {
                    val contentRange =
                        "bytes ${first + 1}-$last/$RESOURCE_LENGTH"
                    responseStatuses += 206
                    responseContentRanges += contentRange
                    exchange.responseHeaders.add("Content-Range", contentRange)
                    exchange.sendResponseHeaders(206, (last - first).toLong())
                    exchange.responseBody.write(body, first + 1, last - first)
                }
                Mode.WRONG_TOTAL -> {
                    val contentRange =
                        "bytes $first-$last/${RESOURCE_LENGTH + 1}"
                    responseStatuses += 206
                    responseContentRanges += contentRange
                    exchange.responseHeaders.add("Content-Range", contentRange)
                    exchange.sendResponseHeaders(206, (last - first + 1).toLong())
                    exchange.responseBody.write(body, first, last - first + 1)
                }
                Mode.FULL_200 -> {
                    responseStatuses += 200
                    responseContentRanges += null
                    exchange.sendResponseHeaders(200, RESOURCE_LENGTH.toLong())
                    exchange.responseBody.write(body)
                }
                Mode.SERVER_ERROR -> {
                    responseStatuses += 503
                    responseContentRanges += null
                    exchange.sendResponseHeaders(503, -1)
                }
            }
        } finally {
            exchange.close()
        }
    }

    private data class EvidenceObservation(
        val caseId: String,
        val requestedByteStart: Int,
        val requestedByteEndExclusive: Int,
        val resourceLength: Int,
        val requestRange: String,
        val responseStatus: Int,
        val responseContentRange: String?,
        val outcome: String,
        val retryable: Boolean,
        val emittedBytes: Int,
        val transportCorrelationId: String,
    ) {
        fun toJsonLine(): String =
            buildString {
                append('{')
                append("\"schemaVersion\":1,")
                append("\"caseId\":").append(jsonString(caseId)).append(',')
                append("\"requestedByteStart\":").append(requestedByteStart).append(',')
                append("\"requestedByteEndExclusive\":")
                    .append(requestedByteEndExclusive).append(',')
                append("\"resourceLength\":").append(resourceLength).append(',')
                append("\"requestRange\":").append(jsonString(requestRange)).append(',')
                append("\"responseStatus\":").append(responseStatus).append(',')
                append("\"responseContentRange\":")
                    .append(jsonString(responseContentRange)).append(',')
                append("\"outcome\":").append(jsonString(outcome)).append(',')
                append("\"retryable\":").append(retryable).append(',')
                append("\"emittedBytes\":").append(emittedBytes).append(',')
                append("\"transportCorrelationId\":")
                    .append(jsonString(transportCorrelationId))
                append('}')
            }
    }

    private enum class Mode {
        PARTIAL,
        WRONG_START,
        WRONG_TOTAL,
        FULL_200,
        SERVER_ERROR,
    }

    private companion object {
        const val RESOURCE_LENGTH = 4_096
        const val ACC15_EVIDENCE_PATH = "build/m1-acc15/range-continuation-v1.jsonl"

        fun jsonString(value: String?): String {
            if (value == null) {
                return "null"
            }
            return buildString {
                append('"')
                for (char in value) {
                    when (char) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(char)
                    }
                }
                append('"')
            }
        }
    }
}
