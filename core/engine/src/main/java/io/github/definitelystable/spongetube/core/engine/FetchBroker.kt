package io.github.definitelystable.spongetube.core.engine

import android.os.SystemClock
import io.github.definitelystable.spongetube.core.engine.recovery.CancellationKind
import io.github.definitelystable.spongetube.core.engine.recovery.FailureObservation
import io.github.definitelystable.spongetube.core.engine.recovery.RangeProtocolKind
import io.github.definitelystable.spongetube.core.engine.recovery.StorageFailureKind
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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Single-flight physical owner registry (M1-D, narrowed by M2-C / ADR-0003).
 *
 * One owner is exactly one physical remote attempt. The broker never decides
 * to try again: the executor reports a raw [FailureObservation] and the owner
 * terminates. Whether a next owner is created is decided only by the
 * RecoveryCoordinator above it.
 */
internal class FetchBroker internal constructor(
    private val publisher: FetchPublisher,
    private val executor: FetchAttemptExecutor,
    private val sessionId: String,
    private val eventListener: FetchEventListener? = null,
    private val ownerScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val ownsScope: Boolean = true,
    private val monotonicClockNs: () -> Long = {
        SystemClock.elapsedRealtimeNanos()
    },
) {
    private val registryLock = Any()
    private val eventDeliveryLock = Any()
    private val active = mutableMapOf<FetchKey, SharedFetch>()
    private val fetchCounter = AtomicLong()
    private val eventCounter = AtomicLong()

    @Volatile
    private var closed = false

    internal constructor(
        extentStore: ExtentStore,
        executor: FetchAttemptExecutor,
        sessionId: String,
        eventListener: FetchEventListener? = null,
        ownerScope: CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ownsScope: Boolean = true,
        monotonicClockNs: () -> Long = {
            SystemClock.elapsedRealtimeNanos()
        },
    ) : this(
        publisher = ExtentStoreFetchPublisher(extentStore),
        executor = executor,
        sessionId = sessionId,
        eventListener = eventListener,
        ownerScope = ownerScope,
        ownsScope = ownsScope,
        monotonicClockNs = monotonicClockNs,
    )

    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
    }

    /**
     * Registers [consumer] on the single-flight owner for [request].
     *
     * [admission] is invoked only for a NEW_OWNER, inside the owner, after
     * cancellation checks and immediately before the physical attempt starts;
     * it is where the RecoveryCoordinator charges its budget, so a charge
     * exists if and only if the attempt is made. If it throws, the owner ends
     * as INTERNAL_FAILURE with zero attempts.
     */
    internal suspend fun acquire(
        request: FetchRequest,
        consumer: FetchConsumer,
        admission: FetchAttemptAdmission? = null,
    ): FetchHandle {
        while (true) {
            currentCoroutineContext().ensureActive()

            var created: SharedFetch? = null
            var joined: SharedFetch? = null
            var waitingShared: SharedFetch? = null
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
                        admission = admission,
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
                    ensureCompatible(existing, request)
                    waitingShared = existing
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
                return Handle(
                    broker = this,
                    shared = shared,
                    consumerId = consumer.id,
                    acquireDisposition = FetchAcquireDisposition.NEW_OWNER,
                )
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
                return Handle(
                    broker = this,
                    shared = shared,
                    consumerId = consumer.id,
                    acquireDisposition = FetchAcquireDisposition.JOINED_RUNNING,
                )
            }

            val waiting = checkNotNull(waitingShared)
            val terminal = waiting.result.await()
            if (terminal.shouldRestartAfterCancellationBarrier()) {
                continue
            }
            return TerminalHandle(
                fetchKey = waiting.request.fetchKey,
                fetchId = waiting.fetchId,
                outcome = terminal,
            )
        }
    }

    internal suspend fun shutdown() {
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
                shared.cancelOutcome = CancellationKind.SESSION_SHUTDOWN
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
                failureOutcome(
                    shared,
                    FailureObservation.Cancellation(
                        CancellationKind.SESSION_SHUTDOWN,
                    ),
                ),
            )
        }
        jobs.joinAll()

        if (ownsScope) {
            ownerScope.cancel()
        }
    }

    /**
     * Next FetchEvent sequence number. Evidence markers record it so that
     * "no attempt inside a window" is checked by sequence, not by clocks.
     */
    internal fun eventSequenceWatermark(): Long = eventCounter.get()

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
            // A job cancelled before its body is dispatched never runs
            // runOwner; the owner must still reach its terminal outcome.
            job.invokeOnCompletion {
                val kind = synchronized(registryLock) {
                    if (shared.state == SharedFetchState.TERMINAL) {
                        null
                    } else {
                        shared.cancelOutcome
                            ?: if (closed) {
                                CancellationKind.SESSION_SHUTDOWN
                            } else {
                                CancellationKind.NO_CONSUMERS
                            }
                    }
                } ?: return@invokeOnCompletion
                completeTerminal(
                    shared,
                    failureOutcome(shared, FailureObservation.Cancellation(kind)),
                )
            }
            job.start()
        } else {
            job.cancel()
        }
    }

    private suspend fun runOwner(shared: SharedFetch) {
        try {
            currentCoroutineContext().ensureActive()
            shared.admission?.admit(
                shared.fetchId,
                attemptCorrelationId(shared.fetchId),
            )
            // No suspension point between an admitted charge and the attempt
            // record: a charge always has exactly one ATTEMPT_STARTED.
            shared.attemptsStarted = SINGLE_ATTEMPT
            emit(
                shared = shared,
                event = FetchEventKind.ATTEMPT_STARTED,
                attempt = SINGLE_ATTEMPT,
            )

            when (val attemptResult = runAttempt(shared)) {
                is AttemptRunResult.Success -> {
                    emit(
                        shared = shared,
                        event = FetchEventKind.ATTEMPT_COMPLETED,
                        attempt = SINGLE_ATTEMPT,
                        outcome = FetchOutcomeKind.SUCCESS,
                        transportCorrelationId =
                            attemptResult.transportCorrelationId,
                    )
                    completeTerminal(
                        shared,
                        FetchOutcome(
                            kind = FetchOutcomeKind.SUCCESS,
                            attempts = SINGLE_ATTEMPT,
                            bytes = shared.accounting.snapshot(),
                            committedExtent = attemptResult.extent,
                        ),
                    )
                }

                is AttemptRunResult.Failure -> {
                    emit(
                        shared = shared,
                        event = FetchEventKind.ATTEMPT_FAILED,
                        attempt = SINGLE_ATTEMPT,
                        outcome = attemptResult.observation.legacyOutcomeKind(),
                        transportCorrelationId =
                            attemptResult.transportCorrelationId,
                    )
                    completeTerminal(
                        shared,
                        failureOutcome(shared, attemptResult.observation),
                    )
                }
            }
        } catch (_: CancellationException) {
            val kind = synchronized(registryLock) {
                shared.cancelOutcome
                    ?: if (closed) {
                        CancellationKind.SESSION_SHUTDOWN
                    } else {
                        CancellationKind.NO_CONSUMERS
                    }
            }
            completeTerminal(
                shared,
                failureOutcome(shared, FailureObservation.Cancellation(kind)),
            )
        } catch (_: Exception) {
            completeTerminal(
                shared,
                failureOutcome(shared, FailureObservation.InternalFailure),
            )
        } catch (fatal: Error) {
            completeTerminal(
                shared,
                failureOutcome(shared, FailureObservation.InternalFailure),
            )
            throw fatal
        }
    }

    private fun failureOutcome(
        shared: SharedFetch,
        observation: FailureObservation,
    ): FetchOutcome =
        FetchOutcome(
            kind = observation.legacyOutcomeKind(),
            attempts = shared.attemptsStarted,
            bytes = shared.accounting.snapshot(),
            failure = observation,
        )

    private suspend fun runAttempt(
        shared: SharedFetch,
    ): AttemptRunResult {
        val attempt = SINGLE_ATTEMPT
        var disposition: FetchAttemptDisposition? = null
        val spec = shared.request.extentSpec
        var expectedOffset = spec.byteStart ?: 0L
        val expectedEnd = spec.byteEndExclusive
            ?: Math.addExact(expectedOffset, spec.expectedLength)

        return try {
            val committed = publisher.publish(spec) { sink ->
                val emitCorrelation: suspend (String) -> Unit = { correlation ->
                    emit(
                        shared = shared,
                        event = FetchEventKind.ATTEMPT_CORRELATED,
                        attempt = attempt,
                        joined = shared.consumers.size > 1,
                        transportCorrelationId = correlation,
                    )
                }
                val emitChunk: suspend (FetchNetworkChunk) -> Unit = { chunk ->
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
                                observation = FailureObservation.RangeProtocolFailure(
                                    RangeProtocolKind.RESPONSE_BYTES_OUTSIDE_RANGE,
                                ),
                            ),
                        )
                    }

                    shared.accounting.recordAccepted(
                        start = chunk.byteStart,
                        endExclusive = chunkEnd,
                    )
                    emit(
                        shared = shared,
                        event = FetchEventKind.ATTEMPT_PROGRESS,
                        attempt = attempt,
                        joined = shared.consumers.size > 1,
                        transportCorrelationId =
                            chunk.transportCorrelationId,
                        chunkByteStart = chunk.byteStart,
                        chunkByteEndExclusive = chunkEnd,
                    )
                    sink.write(chunk.bytes)
                    expectedOffset = chunkEnd
                }

                disposition = if (executor is CorrelatingFetchAttemptExecutor) {
                    executor.executeCorrelated(
                        request = shared.request,
                        attempt = attempt,
                        priority = shared.priority,
                        onTransportCorrelation = emitCorrelation,
                        emitChunk = emitChunk,
                    )
                } else {
                    executor.execute(
                        request = shared.request,
                        attempt = attempt,
                        priority = shared.priority,
                        emitChunk = emitChunk,
                    )
                }

                val terminalDisposition = checkNotNull(disposition) {
                    "fetch executor returned without a disposition"
                }
                if (terminalDisposition is FetchAttemptDisposition.Failure) {
                    throw FetchAttemptAbort(terminalDisposition)
                }
            }

            val success = disposition as FetchAttemptDisposition.Success
            AttemptRunResult.Success(
                extent = committed,
                transportCorrelationId = success.transportCorrelationId,
            )
        } catch (abort: FetchAttemptAbort) {
            AttemptRunResult.Failure(
                observation = abort.failure.observation,
                transportCorrelationId =
                    abort.failure.transportCorrelationId,
            )
        } catch (error: ExtentStorageException) {
            AttemptRunResult.Failure(
                observation = FailureObservation.StorageFailure(
                    when (error.kind) {
                        ExtentStorageFailureKind.NO_SPACE ->
                            StorageFailureKind.NO_SPACE
                        ExtentStorageFailureKind.IO ->
                            StorageFailureKind.IO
                    },
                ),
                transportCorrelationId = null,
            )
        } catch (_: ExtentIntegrityException) {
            AttemptRunResult.Failure(
                observation = FailureObservation.ContentIntegrityFailure,
                transportCorrelationId = null,
            )
        } catch (_: ExtentConflictException) {
            AttemptRunResult.Failure(
                observation = FailureObservation.StorageFailure(
                    StorageFailureKind.CONFLICT,
                ),
                transportCorrelationId = null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AttemptRunResult.Failure(
                observation = FailureObservation.InternalFailure,
                transportCorrelationId = null,
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
                shared.cancelOutcome = CancellationKind.NO_CONSUMERS
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
                failureOutcome(
                    shared,
                    FailureObservation.Cancellation(
                        CancellationKind.NO_CONSUMERS,
                    ),
                ),
            )
        } else {
            cancelJob?.cancel(
                CancellationException("no consumers remain"),
            )
        }
    }

    private fun raisePriority(
        shared: SharedFetch,
        requested: FetchPriority,
    ) {
        val raised = synchronized(registryLock) {
            if (
                shared.state != SharedFetchState.RUNNING ||
                requested.ordinal <= shared.priority.value.ordinal
            ) {
                false
            } else {
                shared.priority.value = requested
                true
            }
        }
        if (raised) {
            emit(
                shared = shared,
                event = FetchEventKind.PRIORITY_RAISED,
            )
        }
    }

    private fun completeTerminal(
        shared: SharedFetch,
        outcome: FetchOutcome,
    ) {
        val shouldComplete = synchronized(registryLock) {
            if (shared.state == SharedFetchState.TERMINAL) {
                false
            } else {
                shared.state = SharedFetchState.TERMINAL
                true
            }
        }

        if (!shouldComplete) {
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
            if (active[shared.request.fetchKey] === shared) {
                active.remove(shared.request.fetchKey)
            }
            shared.consumers.clear()
            shared.result.complete(outcome)
        }
    }

    private fun ensureCompatible(
        shared: SharedFetch,
        request: FetchRequest,
    ) {
        if (!shared.request.extentSpec.isCompatibleWith(request.extentSpec)) {
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
        transportCorrelationId: String? = null,
        chunkByteStart: Long? = null,
        chunkByteEndExclusive: Long? = null,
    ) {
        val listener = eventListener ?: return
        synchronized(eventDeliveryLock) {
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
                        attemptCorrelationId(shared.fetchId, it)
                    },
                    transportCorrelationId = transportCorrelationId,
                    event = event,
                    consumerIds = shared.consumers.keys
                        .map(FetchConsumerId::value)
                        .sorted(),
                    effectivePriority = shared.priority.value,
                    requestedByteStart =
                        shared.request.extentSpec.byteStart,
                    requestedByteEndExclusive =
                        shared.request.extentSpec.byteEndExclusive,
                    chunkByteStart = chunkByteStart,
                    chunkByteEndExclusive = chunkByteEndExclusive,
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
    }

    private class TerminalHandle(
        override val fetchKey: FetchKey,
        override val fetchId: FetchId,
        private val outcome: FetchOutcome,
    ) : FetchHandle {
        override val acquireDisposition: FetchAcquireDisposition
            get() = FetchAcquireDisposition.WAITED_CANCELLING

        override suspend fun await(): FetchOutcome = outcome

        override suspend fun awaitTerminal(): FetchOutcome = outcome

        override fun raisePriority(priority: FetchPriority) = Unit

        override fun close() = Unit
    }

    private class Handle(
        private val broker: FetchBroker,
        private val shared: SharedFetch,
        private val consumerId: FetchConsumerId,
        override val acquireDisposition: FetchAcquireDisposition,
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

        override suspend fun awaitTerminal(): FetchOutcome = shared.result.await()

        override fun raisePriority(priority: FetchPriority) {
            if (!released.get()) {
                broker.raisePriority(shared, priority)
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
    val transportCorrelationId: String? = null,
) {
    init {
        require(byteStart >= 0) { "chunk byteStart must be >= 0" }
        require(bytes.isNotEmpty()) { "chunk bytes must not be empty" }
    }
}

internal sealed interface FetchAttemptDisposition {
    data class Success(
        val transportCorrelationId: String? = null,
    ) : FetchAttemptDisposition

    /**
     * What the transport observed. It carries no retryability: whether
     * anything is tried again is decided only by the RecoveryCoordinator.
     */
    data class Failure(
        val observation: FailureObservation,
        val transportCorrelationId: String? = null,
    ) : FetchAttemptDisposition
}

/**
 * Invoked by a new owner immediately before its single physical attempt;
 * see [FetchBroker.acquire].
 */
internal fun interface FetchAttemptAdmission {
    fun admit(
        fetchId: FetchId,
        attemptCorrelationId: String,
    )
}

internal const val SINGLE_ATTEMPT = 1

internal fun attemptCorrelationId(
    fetchId: FetchId,
    attempt: Int = SINGLE_ATTEMPT,
): String = fetchId.value + ":attempt-" + attempt

internal fun interface FetchAttemptExecutor {
    suspend fun execute(
        request: FetchRequest,
        attempt: Int,
        priority: StateFlow<FetchPriority>,
        emitChunk: suspend (FetchNetworkChunk) -> Unit,
    ): FetchAttemptDisposition
}

/**
 * Optional evidence capability for transports that can expose their physical
 * request identity before any response-body bytes arrive.
 */
internal interface CorrelatingFetchAttemptExecutor : FetchAttemptExecutor {
    suspend fun executeCorrelated(
        request: FetchRequest,
        attempt: Int,
        priority: StateFlow<FetchPriority>,
        onTransportCorrelation: suspend (String) -> Unit,
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
    val admission: FetchAttemptAdmission?,
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
    var cancelOutcome: CancellationKind? = null
}

private sealed interface AttemptRunResult {
    data class Success(
        val extent: CommittedExtent,
        val transportCorrelationId: String?,
    ) : AttemptRunResult

    data class Failure(
        val observation: FailureObservation,
        val transportCorrelationId: String?,
    ) : AttemptRunResult
}

private class FetchAttemptAbort(
    val failure: FetchAttemptDisposition.Failure,
) : RuntimeException()

private fun FetchOutcome.shouldRestartAfterCancellationBarrier(): Boolean =
    kind == FetchOutcomeKind.CANCELLED_NO_CONSUMERS

private fun ExtentSpec.isCompatibleWith(other: ExtentSpec): Boolean =
    mediaAssetId == other.mediaAssetId &&
        extentId == other.extentId &&
        trackId == other.trackId &&
        representationId == other.representationId &&
        mediaStartUs == other.mediaStartUs &&
        mediaEndUs == other.mediaEndUs &&
        byteStart == other.byteStart &&
        byteEndExclusive == other.byteEndExclusive &&
        dependencyExtentIds.toSet() == other.dependencyExtentIds.toSet() &&
        expectedLength == other.expectedLength &&
        expectedSha256 == other.expectedSha256

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
