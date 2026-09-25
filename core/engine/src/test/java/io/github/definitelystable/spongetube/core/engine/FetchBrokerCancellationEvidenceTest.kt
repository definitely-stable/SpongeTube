package io.github.definitelystable.spongetube.core.engine

import io.github.definitelystable.spongetube.core.storage.CommittedExtent
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentSpec
import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import io.github.definitelystable.spongetube.core.storage.Sha256Digest
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Retained deterministic evidence producer for M1-ACC-07.
 *
 * The semantic verifier is host-side and consumes only schema-valid
 * fetch-events-v3 plus the small case observation written here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FetchBrokerCancellationEvidenceTest {
    @Test
    fun canonicalCancellationCasesProduceEvidence() = runTest {
        cleanEvidenceRoot()
        joinedConsumerReleaseCase()
        cancellingBarrierRestartCase()
        cancellingBarrierLateSuccessCase()
        cancellingBarrierLateFailureCase()
    }

    private suspend fun TestScope.joinedConsumerReleaseCase() {
        val events = mutableListOf<FetchEvent>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var executions = 0
        val broker = broker(
            events = events,
            executor = FetchAttemptExecutor { _, _, _, emit ->
                executions += 1
                started.complete(Unit)
                release.await()
                emit(FetchNetworkChunk(0, byteArrayOf(1, 2, 3, 4)))
                FetchAttemptDisposition.Success()
            },
        )

        val first = broker.acquire(
            REQUEST,
            consumer("one", FetchConsumerKind.RESERVE),
        )
        started.await()
        val second = broker.acquire(
            REQUEST,
            consumer("two", FetchConsumerKind.RESERVE),
        )
        assertEquals(first.fetchId, second.fetchId)

        first.close()
        runCurrent()

        assertEquals(1, executions)
        release.complete(Unit)
        assertEquals(FetchOutcomeKind.SUCCESS, second.await().kind)
        assertEquals(0, broker.activeFetchCountForTest())

        writeCase(
            caseId = "JOINED_CONSUMER_RELEASE",
            events = events,
            observation = linkedMapOf(
                "schemaVersion" to 1,
                "caseId" to "JOINED_CONSUMER_RELEASE",
                "executions" to executions,
                "waitingBeforeTerminal" to false,
                "replacementDisposition" to null,
                "sameFetchId" to true,
                "terminalOutcome" to "SUCCESS",
            ),
        )
    }

    private suspend fun TestScope.cancellingBarrierRestartCase() {
        val events = mutableListOf<FetchEvent>()
        val firstStarted = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var executions = 0

        val broker = broker(
            events = events,
            executor = FetchAttemptExecutor { _, _, _, emit ->
                executions += 1
                if (executions == 1) {
                    firstStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleanupStarted.complete(Unit)
                            releaseCleanup.await()
                        }
                    }
                } else {
                    emit(FetchNetworkChunk(0, byteArrayOf(1, 2, 3, 4)))
                    FetchAttemptDisposition.Success()
                }
            },
        )

        val first = broker.acquire(
            REQUEST,
            consumer("first-owner", FetchConsumerKind.RESERVE),
        )
        firstStarted.await()
        first.close()
        cleanupStarted.await()

        val replacement = async {
            broker.acquire(
                REQUEST,
                consumer("replacement", FetchConsumerKind.PLAYBACK),
            )
        }
        runCurrent()
        val waitingBeforeTerminal = !replacement.isCompleted
        assertTrue(waitingBeforeTerminal)
        assertEquals(1, executions)

        releaseCleanup.complete(Unit)
        runCurrent()

        val replacementHandle = replacement.await()
        assertEquals(
            FetchAcquireDisposition.NEW_OWNER,
            replacementHandle.acquireDisposition,
        )
        assertEquals(FetchOutcomeKind.SUCCESS, replacementHandle.await().kind)
        assertEquals(2, executions)
        assertTrue(replacementHandle.fetchId != first.fetchId)

        writeCase(
            caseId = "CANCELLING_BARRIER_RESTART",
            events = events,
            observation = linkedMapOf(
                "schemaVersion" to 1,
                "caseId" to "CANCELLING_BARRIER_RESTART",
                "executions" to executions,
                "waitingBeforeTerminal" to waitingBeforeTerminal,
                "replacementDisposition" to replacementHandle.acquireDisposition.name,
                "sameFetchId" to false,
                "terminalOutcome" to "SUCCESS",
            ),
        )
    }

    private suspend fun TestScope.cancellingBarrierLateSuccessCase() {
        val events = mutableListOf<FetchEvent>()
        val commitStarted = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        var executions = 0

        val publisher = object : FetchPublisher {
            override suspend fun publish(
                spec: ExtentSpec,
                producer: suspend (FetchPublishSink) -> Unit,
            ): CommittedExtent {
                val writtenBytes = mutableListOf<Byte>()
                producer(
                    object : FetchPublishSink {
                        override suspend fun write(bytes: ByteArray) {
                            writtenBytes += bytes.toList()
                        }
                    },
                )
                check(writtenBytes.size.toLong() == spec.expectedLength)
                withContext(NonCancellable) {
                    commitStarted.complete(Unit)
                    releaseCommit.await()
                }
                return committed(spec)
            }
        }

        val broker = FetchBroker(
            publisher = publisher,
            executor = FetchAttemptExecutor { _, _, _, emit ->
                executions += 1
                emit(FetchNetworkChunk(0, byteArrayOf(1, 2, 3, 4)))
                FetchAttemptDisposition.Success()
            },
            sessionId = "m1-acc07-late-success",
            eventListener = FetchEventListener { event -> events += event },
            ownerScope = backgroundScope,
            ownsScope = false,
            monotonicClockNs = { events.size.toLong() },
        )

        val first = broker.acquire(
            REQUEST,
            consumer("late-success-owner", FetchConsumerKind.RESERVE),
        )
        commitStarted.await()
        first.close()

        val replacement = async {
            broker.acquire(
                REQUEST,
                consumer("late-success-replacement", FetchConsumerKind.PLAYBACK),
            )
        }
        runCurrent()
        val waitingBeforeTerminal = !replacement.isCompleted
        assertTrue(waitingBeforeTerminal)

        releaseCommit.complete(Unit)
        runCurrent()

        val replacementHandle = replacement.await()
        assertEquals(
            FetchAcquireDisposition.WAITED_CANCELLING,
            replacementHandle.acquireDisposition,
        )
        assertEquals(first.fetchId, replacementHandle.fetchId)
        assertEquals(1, executions)
        assertEquals(FetchOutcomeKind.SUCCESS, replacementHandle.await().kind)

        writeCase(
            caseId = "CANCELLING_BARRIER_LATE_SUCCESS",
            events = events,
            observation = linkedMapOf(
                "schemaVersion" to 1,
                "caseId" to "CANCELLING_BARRIER_LATE_SUCCESS",
                "executions" to executions,
                "waitingBeforeTerminal" to waitingBeforeTerminal,
                "replacementDisposition" to replacementHandle.acquireDisposition.name,
                "sameFetchId" to true,
                "terminalOutcome" to "SUCCESS",
            ),
        )
    }

    private suspend fun TestScope.cancellingBarrierLateFailureCase() {
        val events = mutableListOf<FetchEvent>()
        val commitStarted = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        var executions = 0

        val publisher = object : FetchPublisher {
            override suspend fun publish(
                spec: ExtentSpec,
                producer: suspend (FetchPublishSink) -> Unit,
            ): CommittedExtent {
                val writtenBytes = mutableListOf<Byte>()
                producer(
                    object : FetchPublishSink {
                        override suspend fun write(bytes: ByteArray) {
                            writtenBytes += bytes.toList()
                        }
                    },
                )
                check(writtenBytes.size.toLong() == spec.expectedLength)
                withContext(NonCancellable) {
                    commitStarted.complete(Unit)
                    releaseCommit.await()
                    throw IllegalStateException("terminal publish failure")
                }
            }
        }

        val broker = FetchBroker(
            publisher = publisher,
            executor = FetchAttemptExecutor { _, _, _, emit ->
                executions += 1
                emit(FetchNetworkChunk(0, byteArrayOf(1, 2, 3, 4)))
                FetchAttemptDisposition.Success()
            },
            sessionId = "m1-acc07-late-failure",
            eventListener = FetchEventListener { event -> events += event },
            ownerScope = backgroundScope,
            ownsScope = false,
            monotonicClockNs = { events.size.toLong() },
        )

        val first = broker.acquire(
            REQUEST,
            consumer("late-failure-owner", FetchConsumerKind.RESERVE),
        )
        commitStarted.await()
        first.close()

        val replacement = async {
            broker.acquire(
                REQUEST,
                consumer("late-failure-replacement", FetchConsumerKind.PLAYBACK),
            )
        }
        runCurrent()
        val waitingBeforeTerminal = !replacement.isCompleted
        assertFalse(replacement.isCompleted)

        releaseCommit.complete(Unit)
        runCurrent()

        val replacementHandle = replacement.await()
        assertEquals(
            FetchAcquireDisposition.WAITED_CANCELLING,
            replacementHandle.acquireDisposition,
        )
        assertEquals(first.fetchId, replacementHandle.fetchId)
        assertEquals(1, executions)
        assertEquals(
            FetchOutcomeKind.INTERNAL_FAILURE,
            replacementHandle.await().kind,
        )

        writeCase(
            caseId = "CANCELLING_BARRIER_LATE_FAILURE",
            events = events,
            observation = linkedMapOf(
                "schemaVersion" to 1,
                "caseId" to "CANCELLING_BARRIER_LATE_FAILURE",
                "executions" to executions,
                "waitingBeforeTerminal" to waitingBeforeTerminal,
                "replacementDisposition" to replacementHandle.acquireDisposition.name,
                "sameFetchId" to true,
                "terminalOutcome" to "INTERNAL_FAILURE",
            ),
        )
    }

    private fun TestScope.broker(
        events: MutableList<FetchEvent>,
        executor: FetchAttemptExecutor,
    ): FetchBroker =
        FetchBroker(
            publisher = FakePublisher(),
            executor = executor,
            sessionId = "m1-acc07",
            eventListener = FetchEventListener { event -> events += event },
            ownerScope = backgroundScope,
            ownsScope = false,
            monotonicClockNs = { events.size.toLong() },
        )

    private fun writeCase(
        caseId: String,
        events: List<FetchEvent>,
        observation: Map<String, Any?>,
    ) {
        val root = File(EVIDENCE_ROOT, caseId)
        check(root.isDirectory || root.mkdirs()) {
            "failed to create ACC-07 evidence directory: $root"
        }

        File(root, "fetch-events-v3.jsonl").bufferedWriter().use { writer ->
            events.sortedBy(FetchEvent::eventSequence).forEach { event ->
                writer.write(json(event.toArtifactMap()))
                writer.newLine()
            }
        }
        File(root, "case.json").writeText(json(observation) + "\n")
    }

    private fun cleanEvidenceRoot() {
        File(EVIDENCE_ROOT).deleteRecursively()
    }

    private fun consumer(
        id: String,
        kind: FetchConsumerKind,
    ): FetchConsumer = FetchConsumer(FetchConsumerId(id), kind)

    private class FakePublisher : FetchPublisher {
        override suspend fun publish(
            spec: ExtentSpec,
            producer: suspend (FetchPublishSink) -> Unit,
        ): CommittedExtent {
            val writtenBytes = mutableListOf<Byte>()
            producer(
                object : FetchPublishSink {
                    override suspend fun write(bytes: ByteArray) {
                        writtenBytes += bytes.toList()
                    }
                },
            )
            check(writtenBytes.size.toLong() == spec.expectedLength)
            return committed(spec)
        }
    }

    private companion object {
        const val EVIDENCE_ROOT = "build/m1-acc07"

        val REQUEST = FetchRequest(
            fetchKey = FetchKey("fixture:F1/video/0-4"),
            extentSpec = ExtentSpec(
                mediaAssetId = MediaAssetId("fixture:F1"),
                extentId = ExtentId("extent-acc07"),
                trackId = "video-main",
                representationId = "video-r1",
                mediaStartUs = 0,
                mediaEndUs = 10_000_000,
                byteStart = 0,
                byteEndExclusive = 4,
                expectedLength = 4,
            ),
        )

        fun committed(spec: ExtentSpec): CommittedExtent =
            CommittedExtent(
                mediaAssetId = spec.mediaAssetId,
                extentId = spec.extentId,
                trackId = spec.trackId,
                representationId = spec.representationId,
                mediaStartUs = spec.mediaStartUs,
                mediaEndUs = spec.mediaEndUs,
                byteStart = spec.byteStart,
                byteEndExclusive = spec.byteEndExclusive,
                dependencyExtentIds = spec.dependencyExtentIds,
                length = spec.expectedLength,
                sha256 = Sha256Digest("0".repeat(64)),
            )

        fun json(value: Any?): String = when (value) {
            null -> "null"
            is String -> buildString {
                append('"')
                value.forEach { char ->
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
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> value.entries.joinToString(
                prefix = "{",
                postfix = "}",
            ) { (key, item) ->
                json(key.toString()) + ":" + json(item)
            }
            is Iterable<*> -> value.joinToString(
                prefix = "[",
                postfix = "]",
            ) { item -> json(item) }
            else -> error("unsupported JSON evidence value: ${value::class.java.name}")
        }
    }
}
