package io.github.definitelystable.spongetube.core.engine

import io.github.definitelystable.spongetube.core.storage.CommittedExtent
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentSpec
import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import io.github.definitelystable.spongetube.core.storage.Sha256Digest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FetchBrokerTest {
    @Test
    fun concurrentConsumersJoinOneOwnerAndPlaybackRaisesPriority() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var executions = 0
        val events = mutableListOf<FetchEvent>()
        val broker = broker(
            executor = FetchAttemptExecutor { _, _, priority, emit ->
                executions += 1
                started.complete(Unit)
                release.await()
                assertEquals(FetchPriority.PLAYBACK, priority.value)
                emit(FetchNetworkChunk(0, byteArrayOf(1, 2, 3, 4)))
                FetchAttemptDisposition.Success
            },
            events = events,
        )

        val reserve = broker.acquire(REQUEST, consumer("reserve", FetchConsumerKind.RESERVE))
        started.await()
        val playback = broker.acquire(REQUEST, consumer("playback", FetchConsumerKind.PLAYBACK))

        assertEquals(reserve.fetchId, playback.fetchId)
        assertEquals(1, executions)
        release.complete(Unit)

        assertEquals(FetchOutcomeKind.SUCCESS, reserve.await().kind)
        assertEquals(FetchOutcomeKind.SUCCESS, playback.await().kind)
        assertEquals(0, broker.activeFetchCountForTest())
        assertEquals(
            1,
            events.count { it.event == FetchEventKind.OWNER_REGISTERED },
        )
        assertEquals(
            1,
            events.count { it.event == FetchEventKind.CONSUMER_JOINED },
        )
        assertEquals(
            1,
            events.count { it.event == FetchEventKind.PRIORITY_RAISED },
        )
    }

    @Test
    fun cancellingOneJoinedConsumerDoesNotCancelRemainingConsumer() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var executions = 0
        val broker = broker(
            executor = FetchAttemptExecutor { _, _, _, emit ->
                executions += 1
                started.complete(Unit)
                release.await()
                emit(FetchNetworkChunk(0, byteArrayOf(1, 2, 3, 4)))
                FetchAttemptDisposition.Success
            },
        )

        val first = broker.acquire(REQUEST, consumer("one", FetchConsumerKind.RESERVE))
        started.await()
        val second = broker.acquire(REQUEST, consumer("two", FetchConsumerKind.RESERVE))

        first.close()
        runCurrent()
        assertEquals(1, executions)
        release.complete(Unit)

        assertEquals(FetchOutcomeKind.SUCCESS, second.await().kind)
        assertEquals(0, broker.activeFetchCountForTest())
    }

    @Test
    fun lastConsumerCancellationCancelsOwnerAndRemovesRegistryEntry() = runTest {
        val started = CompletableDeferred<Unit>()
        val events = mutableListOf<FetchEvent>()
        val broker = broker(
            executor = FetchAttemptExecutor { _, _, _, _ ->
                started.complete(Unit)
                CompletableDeferred<Unit>().await()
                FetchAttemptDisposition.Success
            },
            events = events,
        )

        val handle = broker.acquire(REQUEST, consumer("only", FetchConsumerKind.RESERVE))
        started.await()
        handle.close()
        runCurrent()

        assertEquals(0, broker.activeFetchCountForTest())
        assertEquals(
            FetchOutcomeKind.CANCELLED_NO_CONSUMERS,
            events.last { it.event == FetchEventKind.OWNER_CANCELLED }.outcome,
        )
    }

    @Test
    fun retryIsSequentialBoundedAndAccountsDuplicateRanges() = runTest {
        val attempts = mutableListOf<Int>()
        val broker = broker(
            budget = FetchAttemptBudget(2),
            executor = FetchAttemptExecutor { _, attempt, _, emit ->
                attempts += attempt
                if (attempt == 1) {
                    emit(FetchNetworkChunk(0, byteArrayOf(1, 2)))
                    FetchAttemptDisposition.Failure(
                        FetchOutcomeKind.RETRYABLE_TRANSPORT_FAILURE,
                        retryable = true,
                    )
                } else {
                    emit(FetchNetworkChunk(0, byteArrayOf(1, 2, 3, 4)))
                    FetchAttemptDisposition.Success
                }
            },
        )

        val outcome = broker.acquire(
            REQUEST,
            consumer("retry", FetchConsumerKind.RESERVE),
        ).await()

        assertEquals(listOf(1, 2), attempts)
        assertEquals(FetchOutcomeKind.SUCCESS, outcome.kind)
        assertEquals(2, outcome.attempts)
        assertEquals(6L, outcome.bytes.networkBytes)
        assertEquals(4L, outcome.bytes.uniqueRangeBytes)
        assertEquals(2L, outcome.bytes.duplicateRangeBytes)
        assertEquals(0L, outcome.bytes.rejectedOrUnmappedBytes)
    }

    @Test
    fun sameKeyWithDifferentImmutableWorkFailsFast() = runTest {
        val started = CompletableDeferred<Unit>()
        val broker = broker(
            executor = FetchAttemptExecutor { _, _, _, _ ->
                started.complete(Unit)
                CompletableDeferred<Unit>().await()
                FetchAttemptDisposition.Success
            },
        )

        val first = broker.acquire(
            REQUEST,
            consumer("first", FetchConsumerKind.RESERVE),
        )
        started.await()

        val different = REQUEST.copy(
            extentSpec = REQUEST.extentSpec.copy(
                extentId = ExtentId("different"),
            ),
        )
        val failure = runCatching {
            broker.acquire(
                different,
                consumer("second", FetchConsumerKind.RESERVE),
            )
        }.exceptionOrNull()
        assertEquals(
            FetchIdentityConflictException::class.java,
            failure?.javaClass,
        )

        first.close()
        runCurrent()
    }

    @Test
    fun rejectedRangeNeverPublishesAndIsCountedSeparately() = runTest {
        var committed = 0
        val publisher = FakePublisher { committed += 1 }
        val broker = FetchBroker(
            publisher = publisher,
            executor = FetchAttemptExecutor { _, _, _, emit ->
                emit(FetchNetworkChunk(1, byteArrayOf(1, 2)))
                FetchAttemptDisposition.Success
            },
            attemptBudget = FetchAttemptBudget(1),
            sessionId = "test-session",
            ownerScope = backgroundScope,
            ownsScope = false,
        )

        val outcome = broker.acquire(
            REQUEST,
            consumer("range", FetchConsumerKind.RESERVE),
        ).await()

        assertEquals(FetchOutcomeKind.RANGE_REJECTED, outcome.kind)
        assertEquals(2L, outcome.bytes.networkBytes)
        assertEquals(0L, outcome.bytes.uniqueRangeBytes)
        assertEquals(2L, outcome.bytes.rejectedOrUnmappedBytes)
        assertEquals(0, committed)
    }

    private fun TestScope.broker(
        executor: FetchAttemptExecutor,
        budget: FetchAttemptBudget = FetchAttemptBudget(1),
        events: MutableList<FetchEvent> = mutableListOf(),
    ): FetchBroker = FetchBroker(
        publisher = FakePublisher(),
        executor = executor,
        attemptBudget = budget,
        sessionId = "test-session",
        eventListener = FetchEventListener { event -> events += event },
        ownerScope = backgroundScope,
        ownsScope = false,
        monotonicClockNs = { events.size.toLong() },
    )

    private fun consumer(
        id: String,
        kind: FetchConsumerKind,
    ) = FetchConsumer(FetchConsumerId(id), kind)

    private class FakePublisher(
        private val onCommit: () -> Unit = {},
    ) : FetchPublisher {
        override suspend fun publish(
            spec: ExtentSpec,
            producer: suspend (FetchPublishSink) -> Unit,
        ): CommittedExtent {
            val bytes = mutableListOf<Byte>()
            producer(
                object : FetchPublishSink {
                    override suspend fun write(bytesToWrite: ByteArray) {
                        bytes += bytesToWrite.toList()
                    }
                },
            )
            check(bytes.size.toLong() == spec.expectedLength)
            onCommit()
            return CommittedExtent(
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
        }
    }

    private companion object {
        val REQUEST = FetchRequest(
            fetchKey = FetchKey("fixture:F1/video/0-4"),
            extentSpec = ExtentSpec(
                mediaAssetId = MediaAssetId("fixture:F1"),
                extentId = ExtentId("extent-0"),
                trackId = "video-main",
                representationId = "video-r1",
                mediaStartUs = 0,
                mediaEndUs = 10_000_000,
                byteStart = 0,
                byteEndExclusive = 4,
                expectedLength = 4,
            ),
        )
    }
}
