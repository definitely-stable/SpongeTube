package io.github.definitelystable.spongetube.core.engine

import io.github.definitelystable.spongetube.core.storage.CommittedExtent
import io.github.definitelystable.spongetube.core.storage.ExtentConflictException
import io.github.definitelystable.spongetube.core.storage.ExtentIntegrityException
import io.github.definitelystable.spongetube.core.storage.ExtentSpec
import io.github.definitelystable.spongetube.core.storage.ExtentStorageException
import io.github.definitelystable.spongetube.core.storage.ExtentStorageFailureKind
import io.github.definitelystable.spongetube.core.storage.ExtentStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class FetchBroker internal constructor(
    private val publisher: FetchPublisher,
    private val executor: FetchAttemptExecutor,
    private val attemptBudget: FetchAttemptBudget,
    private val sessionId: String,
    private val eventListener: FetchEventListener? = null,
    private val ownerScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val ownsScope: Boolean = true,
    private val monotonicClockNs: () -> Long = System::nanoTime,
) : AutoCloseable {
    private val registryLock = Any()
    private val active = mutableMapOf<FetchKey, SharedFetch>()
    private val fetchCounter = AtomicLong()
    private val eventCounter = AtomicLong()

    @Volatile
    private var closed = false

    internal constructor(
        extentStore: ExtentStore,
        executor: FetchAttemptExecutor,
        attemptBudget: FetchAttemptBudget,
        sessionId: String,
        eventListener: FetchEventListener? = null,
        ownerScope: CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ownsScope: Boolean = true,
        monotonicClockNs: () -> Long = System::nanoTime,
    ) : this(
        publisher = ExtentStoreFetchPublisher(extentStore),
        executor = executor,
        attemptBudget = attemptBudget,
        sessionId = sessionId,
        eventListener = eventListener,
        ownerScope = ownerScope,
        ownsScope = ownsScope,
        monotonicClockNs = monotonicClockNs,
    )

    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
    }

    suspend fun acquire(
        request: FetchRequest,
        consumer: FetchConsumer,
    ): FetchHandle {
        while (true) {
            currentCoroutineContext().ensureActive()

            var created: SharedFetch? = null
            var joined: SharedFetch? = null
            var waitForTerminal: CompletableDeferred<FetchOutcome>? = null
            var priorityRaised = false

            synchronized(registryLock) {
                check(!closed) { "fetch broker is closed" }

                val existing = active[request.fetchKey]
                if (existing == null) {
                    val shared = SharedFetch(
                        request = request,
                        fetchId = FetchId(
                            "fetch-" + fetchCounter.incrementAndGet(),
                        ),
                        initialConsumer = consumer,
                    )
                    active[request.fetchKey] = shared
                    created = shared
                } else if (existing.state == SharedFetchState.RUNNING) {
                    ensureCompatible(existing, request)
                    if (existing.consumers.containsKey(consumer.id)) {
                        throw FetchIdentityConflictException(
                            "consumer id already joined active fetch: " +
                                consumer.id,
                        )
                    }
                    existing.consumers[consumer.id] = consumer
                    val requestedPriority = consumer.kind.priority
                    if (
                        requestedPriority.ordinal >
                        existing.priority.value.ordinal
                    ) {
                        existing.priority.value = requestedPriority
                        priorityRaised = true
                    }
                    joined = existing
                } else {
                    waitForTerminal = existing.result
                }
            }

            if (created != null) {
                val shared = checkNotNull(created)
                emit(
                    shared = shared,
                    event = FetchEventKind.OWNER_REGISTERED,
                    joined = false,
                )
                startOwner(shared)
                return Handle(this, shared, consumer.id)
            }

            if (joined != null) {
                val shared = checkNotNull(joined)
                emit(
                    shared = shared,
                    event = FetchEventKind.CONSUMER_JOINED,
                    joined = true,
                )
                if (priorityRaised) {
                    emit(
                        shared = shared,
                        event = FetchEventKind.PRIORITY_RAISED,
                        joined = true,
                    )
                }
                return Handle(this, shared, consumer.id)
            }

            checkNotNull(waitForTerminal).await()
        }
    }

    override fun close() {
        val jobs = mutableListOf<Job>()
        val orphaned = mutableListOf<SharedFetch>()

        synchronized(registryLock) {
            if (closed) {
                return
            }
            closed = true

            for (shared in active.values) {
                if (shared.state == SharedFetchState.TERMINAL) {
                    continue
                }
                shared.state = SharedFetchState.CANCELLING
                shared.cancelOutcome =
                    FetchOutcomeKind.CANCELLED_BROKER_SHUTDOWN
                val job = shared.ownerJob
                if (job == null) {
                    orphaned += shared
                } else {
                    jobs += job
                }
            }
        }

        jobs.forEach { job ->
            job.cancel(CancellationException("fetch broker closed"))
        }
        orphaned.forEach { shared ->
            completeTerminal(
                shared,
                FetchOutcome(
                    kind = FetchOutcomeKind.CANCELLED_BROKER_SHUTDOWN,
                    attempts = shared.attemptsStarted,
                    bytes = shared.accounting.snapshot(),
                ),
            )
        }

        if (ownsScope) {
            ownerScope.cancel()
        }
    }

    internal fun activeFetchCountForTest(): Int =
        synchronized(registryLock) { active.size }

    private fun startOwner(shared: SharedFetch) {
        val job = ownerScope.launch(start = CoroutineStart.LAZY) {
            runOwner(shared)
        }

        val shouldStart = synchronized(registryLock) {
            if (
                closed ||
                shared.state != SharedFetchState.RUNNING ||
                active[shared.request.fetchKey] !== shared
            ) {
                false
            } else {
                shared.ownerJob = job
                true
            }
        }

        if (shouldStart) {
            job.start()
        } else {
            job.cancel()
        }
    }

    private suspend fun runOwner(shared: SharedFetch) {
        try {
            for (attempt in 1..attemptBudget.maxAttempts) {
                currentCoroutineContext().ensureActive()
                shared.attemptsStarted = attempt
                emit(
                    shared = shared,
                    event = FetchEventKind.ATTEMPT_STARTED,
                    attempt = attempt,
                )

                val attemptResult = runAttempt(shared, attempt)
                if (attemptResult is AttemptRunResult.Success) {
                    val outcome = FetchOutcome(
                        kind = FetchOutcomeKind.SUCCESS,
                        attempts = attempt,
                        bytes = shared.accounting.snapshot(),
                        committedExtent = attemptResult.extent,
                    )
                    emit(
                        shared = shared,
                        event = FetchEventKind.ATTEMPT_COMPLETED,
                        attempt = attempt,
                        outcome = FetchOutcomeKind.SUCCESS,
                    )
                    completeTerminal(shared, outcome)
                    return
                }

                attemptResult as AttemptRunResult.Failure
                emit(
                    shared = shared,
                    event = FetchEventKind.ATTEMPT_FAILED,
                    attempt = attempt,
                    outcome = attemptResult.kind,
                )

                if (
                    attemptResult.retryable &&
                    attempt < attemptBudget.maxAttempts
                ) {
                    continue
                }

                completeTerminal(
                    shared,
                    FetchOutcome(
                        kind = attemptResult.kind,
                        attempts = attempt,
                        bytes = shared.accounting.snapshot(),
                    ),
                )
                return
            }
        } catch (_: CancellationException) {
            val kind = synchronized(registryLock) {
                shared.cancelOutcome
                    ?: if (closed) {
                        FetchOutcomeKind.CANCELLED_BROKER_SHUTDOWN
                    } else {
                        FetchOutcomeKind.CANCELLED_NO_CONSUMERS
                    }
            }
            completeTerminal(
                shared,
                FetchOutcome(
                    kind = kind,
                    attempts = shared.attemptsStarted,
                    bytes = shared.accounting.snapshot(),
                ),
            )
        } catch (_: Throwable) {
            completeTerminal(
                shared,
                FetchOutcome(
                    kind = FetchOutcomeKind.INTERNAL_FAILURE,
                    attempts = shared.attemptsStarted,
                    bytes = shared.accounting.snapshot(),
                ),
            )
        }
    }

    private suspend fun runAttempt(
        shared: SharedFetch,
        attempt: Int,
    ): AttemptRunResult {
        var disposition: FetchAttemptDisposition? = null
        val spec = shared.request.extentSpec
        var expectedOffset = spec.byteStart ?: 0L
        val expectedEnd = spec.byteEndExclusive
            ?: Math.addExact(expectedOffset, spec.expectedLength)

        return try {
            val committed = publisher.publish(spec) { sink ->
                disposition = executor.execute(
                    request = shared.request,
                    attempt = attempt,
                    priority = shared.priority,
                ) { chunk ->
                    val chunkEnd = Math.addExact(
                        chunk.byteStart,
                        chunk.bytes.size.toLong(),
                    )
                    if (
                        chunk.byteStart != expectedOffset ||
                        chunkEnd > expectedEnd
                    ) {
                        shared.accounting.recordRejected(
                            chunk.bytes.size.toLong(),
                        )
                        throw FetchAttemptAbort(
                            FetchAttemptDisposition.Failure(
                                kind = FetchOutcomeKind.RANGE_REJECTED,
                                retryable = false,
                            ),
                        )
                    }

                    shared.accounting.recordAccepted(
                        start = chunk.byteStart,
                        endExclusive = chunkEnd,
                    )
                    sink.write(chunk.bytes)
                    expectedOffset = chunkEnd
                }

                val terminalDisposition = checkNotNull(disposition) {
                    "fetch executor returned without a disposition"
                }
                if (terminalDisposition is FetchAttemptDisposition.Failure) {
                    throw FetchAttemptAbort(terminalDisposition)
                }
            }

            AttemptRunResult.Success(committed)
        } catch (abort: FetchAttemptAbort) {
            AttemptRunResult.Failure(
                kind = abort.failure.kind,
                retryable = abort.failure.retryable,
            )
        } catch (error: ExtentStorageException) {
            AttemptRunResult.Failure(
                kind = when (error.kind) {
                    ExtentStorageFailureKind.NO_SPACE ->
                        FetchOutcomeKind.STORAGE_NO_SPACE
                    ExtentStorageFailureKind.IO ->
                        FetchOutcomeKind.STORAGE_IO
                },
                retryable = false,
            )
        } catch (_: ExtentIntegrityException) {
            AttemptRunResult.Failure(
                kind = FetchOutcomeKind.CONTENT_INTEGRITY_REJECTED,
                retryable = false,
            )
        } catch (_: ExtentConflictException) {
            AttemptRunResult.Failure(
                kind = FetchOutcomeKind.STORAGE_CONFLICT,
                retryable = false,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            AttemptRunResult.Failure(
                kind = FetchOutcomeKind.INTERNAL_FAILURE,
                retryable = false,
            )
        }
    }

    private fun release(
        shared: SharedFetch,
        consumerId: FetchConsumerId,
    ) {
        var cancelJob: Job? = null
        var completeWithoutOwner = false
        var released = false

        synchronized(registryLock) {
            if (shared.state == SharedFetchState.TERMINAL) {
                return
            }

            released = shared.consumers.remove(consumerId) != null
            if (!released) {
                return
            }

            if (
                shared.consumers.isEmpty() &&
                shared.state == SharedFetchState.RUNNING
            ) {
                shared.state = SharedFetchState.CANCELLING
                shared.cancelOutcome =
                    FetchOutcomeKind.CANCELLED_NO_CONSUMERS
                cancelJob = shared.ownerJob
                completeWithoutOwner = cancelJob == null
            }
        }

        if (released) {
            emit(
                shared = shared,
                event = FetchEventKind.CONSUMER_RELEASED,
                joined = true,
            )
        }

        if (completeWithoutOwner) {
            completeTerminal(
                shared,
                FetchOutcome(
                    kind = FetchOutcomeKind.CANCELLED_NO_CONSUMERS,
                    attempts = shared.attemptsStarted,
                    bytes = shared.accounting.snapshot(),
                ),
            )
        } else {
            cancelJob?.cancel(
                CancellationException("no consumers remain"),
            )
        }
    }

    private fun completeTerminal(
        shared: SharedFetch,
        outcome: FetchOutcome,
    ) {
        val shouldEmit = synchronized(registryLock) {
            if (shared.state == SharedFetchState.TERMINAL) {
                false
            } else {
                shared.state = SharedFetchState.TERMINAL
                if (active[shared.request.fetchKey] === shared) {
                    active.remove(shared.request.fetchKey)
                }
                shared.result.complete(outcome)
                true
            }
        }

        if (!shouldEmit) {
            return
        }

        val event = when (outcome.kind) {
            FetchOutcomeKind.SUCCESS ->
                FetchEventKind.OWNER_COMPLETED
            FetchOutcomeKind.CANCELLED_NO_CONSUMERS,
            FetchOutcomeKind.CANCELLED_BROKER_SHUTDOWN,
            ->
                FetchEventKind.OWNER_CANCELLED
            else ->
                FetchEventKind.OWNER_FAILED
        }
        emit(
            shared = shared,
            event = event,
            outcome = outcome.kind,
        )
        synchronized(registryLock) {
            shared.consumers.clear()
        }
    }

    private fun ensureCompatible(
        shared: SharedFetch,
        request: FetchRequest,
    ) {
        if (shared.request.extentSpec != request.extentSpec) {
            throw FetchIdentityConflictException(
                "same FetchKey was acquired with a different immutable " +
                    "ExtentSpec: " + request.fetchKey,
            )
        }
    }

    private fun emit(
        shared: SharedFetch,
        event: FetchEventKind,
        attempt: Int? = null,
        joined: Boolean = false,
        outcome: FetchOutcomeKind? = null,
    ) {
        val listener = eventListener ?: return
        val snapshot = synchronized(registryLock) {
            val accounting = shared.accounting.snapshot()
            FetchEvent(
                eventSequence = eventCounter.getAndIncrement(),
                eventElapsedRealtimeNs = monotonicClockNs(),
                sessionId = sessionId,
                fetchId = shared.fetchId,
                fetchKey = shared.request.fetchKey,
                attempt = attempt,
                attemptCorrelationId = attempt?.let {
                    shared.fetchId.value + ":attempt-" + it
                },
                event = event,
                consumerIds = shared.consumers.keys
                    .map(FetchConsumerId::value)
                    .sorted(),
                effectivePriority = shared.priority.value,
                requestedByteStart =
                    shared.request.extentSpec.byteStart,
                requestedByteEndExclusive =
                    shared.request.extentSpec.byteEndExclusive,
                networkBytes = accounting.networkBytes,
                uniqueRangeBytes = accounting.uniqueRangeBytes,
                duplicateRangeBytes = accounting.duplicateRangeBytes,
                rejectedOrUnmappedBytes =
                    accounting.rejectedOrUnmappedBytes,
                singleFlightJoined = joined,
                outcome = outcome,
            )
        }
        runCatching { listener.onEvent(snapshot) }
    }

    private class Handle(
        private val broker: FetchBroker,
        private val shared: SharedFetch,
        private val consumerId: FetchConsumerId,
    ) : FetchHandle {
        private val released = AtomicBoolean()

        override val fetchKey: FetchKey
            get() = shared.request.fetchKey

        override val fetchId: FetchId
            get() = shared.fetchId

        override suspend fun await(): FetchOutcome {
            check(!released.get()) { "fetch handle already released" }
            return try {
                shared.result.await()
            } finally {
                releaseOnce()
            }
        }

        override fun close() {
            releaseOnce()
        }

        private fun releaseOnce() {
            if (released.compareAndSet(false, true)) {
                broker.release(shared, consumerId)
            }
        }
    }
}

internal data class FetchNetworkChunk(
    val byteStart: Long,
    val bytes: ByteArray,
) {
    init {
        require(byteStart >= 0) { "chunk byteStart must be >= 0" }
        require(bytes.isNotEmpty()) { "chunk bytes must not be empty" }
    }
}

internal sealed interface FetchAttemptDisposition {
    data object Success : FetchAttemptDisposition

    data class Failure(
        val kind: FetchOutcomeKind,
        val retryable: Boolean,
    ) : FetchAttemptDisposition {
        init {
            require(kind != FetchOutcomeKind.SUCCESS)
            require(
                !retryable ||
                    kind == FetchOutcomeKind.RETRYABLE_TRANSPORT_FAILURE,
            ) {
                "only retryable transport failure may request an M1 retry"
            }
        }
    }
}

internal fun interface FetchAttemptExecutor {
    suspend fun execute(
        request: FetchRequest,
        attempt: Int,
        priority: StateFlow<FetchPriority>,
        emitChunk: suspend (FetchNetworkChunk) -> Unit,
    ): FetchAttemptDisposition
}

internal interface FetchPublishSink {
    suspend fun write(bytes: ByteArray)
}

internal fun interface FetchPublisher {
    suspend fun publish(
        spec: ExtentSpec,
        producer: suspend (FetchPublishSink) -> Unit,
    ): CommittedExtent
}

private class ExtentStoreFetchPublisher(
    private val extentStore: ExtentStore,
) : FetchPublisher {
    override suspend fun publish(
        spec: ExtentSpec,
        producer: suspend (FetchPublishSink) -> Unit,
    ): CommittedExtent =
        extentStore.writeExtent(spec) {
            val extentSink = this
            producer(
                object : FetchPublishSink {
                    override suspend fun write(bytes: ByteArray) {
                        extentSink.write(bytes)
                    }
                },
            )
        }
}

private enum class SharedFetchState {
    RUNNING,
    CANCELLING,
    TERMINAL,
}

private class SharedFetch(
    val request: FetchRequest,
    val fetchId: FetchId,
    initialConsumer: FetchConsumer,
) {
    val consumers = linkedMapOf(
        initialConsumer.id to initialConsumer,
    )
    val priority = MutableStateFlow(initialConsumer.kind.priority)
    val result = CompletableDeferred<FetchOutcome>()
    val accounting = FetchByteAccumulator()

    var state: SharedFetchState = SharedFetchState.RUNNING
    var ownerJob: Job? = null
    @Volatile
    var attemptsStarted: Int = 0
    var cancelOutcome: FetchOutcomeKind? = null
}

private sealed interface AttemptRunResult {
    data class Success(
        val extent: CommittedExtent,
    ) : AttemptRunResult

    data class Failure(
        val kind: FetchOutcomeKind,
        val retryable: Boolean,
    ) : AttemptRunResult
}

private class FetchAttemptAbort(
    val failure: FetchAttemptDisposition.Failure,
) : RuntimeException()

private data class ByteInterval(
    val start: Long,
    val endExclusive: Long,
)

private class FetchByteAccumulator {
    private val accepted = mutableListOf<ByteInterval>()
    private var networkBytes = 0L
    private var uniqueRangeBytes = 0L
    private var duplicateRangeBytes = 0L
    private var rejectedOrUnmappedBytes = 0L

    @Synchronized
    fun recordAccepted(
        start: Long,
        endExclusive: Long,
    ) {
        require(start >= 0)
        require(endExclusive > start)

        val length = endExclusive - start
        networkBytes = Math.addExact(networkBytes, length)

        var overlap = 0L
        for (interval in accepted) {
            val overlapStart = maxOf(start, interval.start)
            val overlapEnd = minOf(endExclusive, interval.endExclusive)
            if (overlapStart < overlapEnd) {
                overlap = Math.addExact(
                    overlap,
                    overlapEnd - overlapStart,
                )
            }
        }

        val unique = length - overlap
        uniqueRangeBytes = Math.addExact(uniqueRangeBytes, unique)
        duplicateRangeBytes = Math.addExact(
            duplicateRangeBytes,
            overlap,
        )
        addInterval(ByteInterval(start, endExclusive))
    }

    @Synchronized
    fun recordRejected(length: Long) {
        require(length > 0)
        networkBytes = Math.addExact(networkBytes, length)
        rejectedOrUnmappedBytes = Math.addExact(
            rejectedOrUnmappedBytes,
            length,
        )
    }

    @Synchronized
    fun snapshot(): FetchByteAccounting = FetchByteAccounting(
        networkBytes = networkBytes,
        uniqueRangeBytes = uniqueRangeBytes,
        duplicateRangeBytes = duplicateRangeBytes,
        rejectedOrUnmappedBytes = rejectedOrUnmappedBytes,
    )

    private fun addInterval(next: ByteInterval) {
        val all = (accepted + next).sortedBy(ByteInterval::start)
        accepted.clear()
        for (interval in all) {
            val previous = accepted.lastOrNull()
            if (
                previous == null ||
                interval.start > previous.endExclusive
            ) {
                accepted += interval
            } else {
                accepted[accepted.lastIndex] = ByteInterval(
                    start = previous.start,
                    endExclusive = maxOf(
                        previous.endExclusive,
                        interval.endExclusive,
                    ),
                )
            }
        }
    }
}
