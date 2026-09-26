package io.github.definitelystable.spongetube.core.engine

import io.github.definitelystable.spongetube.core.engine.recovery.FailureObservation
import io.github.definitelystable.spongetube.core.engine.recovery.TransportIoKind
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingRevision
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingSnapshot
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryMaterial
import io.github.definitelystable.spongetube.core.storage.CommittedExtent
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentSpec
import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import io.github.definitelystable.spongetube.core.storage.Sha256Digest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
                FetchAttemptDisposition.Success()
            },
            events = events,
        )

        val reserve = broker.acquire(REQUEST, consumer("reserve", FetchConsumerKind.RESERVE))
        started.await()
        val playback = broker.acquire(REQUEST, consumer("playback", FetchConsumerKind.PLAYBACK))

        assertEquals(reserve.fetchId, playback.fetchId)
        assertEquals(FetchAcquireDisposition.NEW_OWNER, reserve.acquireDisposition)
        assertEquals(FetchAcquireDisposition.JOINED_RUNNING, playback.acquireDisposition)
        assertFalse(reserve.joinedExisting)
        assertTrue(playback.joinedExisting)
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
                FetchAttemptDisposition.Success()
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
    fun cancellingAwaitingConsumerReleasesOnlyItsLease() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var executions = 0
        val broker = broker(
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
            consumer("await-one", FetchConsumerKind.RESERVE),
        )
        started.await()
        val second = broker.acquire(
            REQUEST,
            consumer("await-two", FetchConsumerKind.RESERVE),
        )

        val waiter = async { first.await() }
        runCurrent()
        waiter.cancelAndJoin()
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
                FetchAttemptDisposition.Success()
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
    fun replacementOwnerWaitsUntilCancellingPhysicalAttemptIsTerminal() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        var executions = 0
        val events = mutableListOf<FetchEvent>()

        val broker = broker(
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
                    secondStarted.complete(Unit)
                    emit(FetchNetworkChunk(0, byteArrayOf(1, 2, 3, 4)))
                    FetchAttemptDisposition.Success()
                }
            },
            events = events,
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

        assertEquals(1, executions)
        assertEquals(false, replacement.isCompleted)

        releaseCleanup.complete(Unit)
        runCurrent()

        val replacementHandle = replacement.await()
        assertEquals(FetchAcquireDisposition.NEW_OWNER, replacementHandle.acquireDisposition)
        secondStarted.await()
        assertEquals(2, executions)
        assertEquals(
            FetchOutcomeKind.SUCCESS,
            replacementHandle.await().kind,
        )
        val firstTerminal = events.indexOfFirst {
            it.event == FetchEventKind.OWNER_CANCELLED
        }
        val secondOwner = events.indexOfLast {
            it.event == FetchEventKind.OWNER_REGISTERED
        }
        assertEquals(true, firstTerminal >= 0)
        assertEquals(true, secondOwner > firstTerminal)
    }

    @Test
    fun cancellingBarrierHandsLateSuccessToReplacementWithoutRefetch() = runTest {
        val commitStarted = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        var executions = 0
        val publisher = object : FetchPublisher {
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
            sessionId = "test-session",
            ownerScope = backgroundScope,
            ownsScope = false,
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
        assertEquals(false, replacement.isCompleted)

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
            FetchOutcomeKind.SUCCESS,
            replacementHandle.await().kind,
        )
    }

    @Test
    fun cancellingBarrierPreservesTerminalFailureWithoutResettingBudget() = runTest {
        val commitStarted = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        var executions = 0
        val publisher = object : FetchPublisher {
            override suspend fun publish(
                spec: ExtentSpec,
                producer: suspend (FetchPublishSink) -> Unit,
            ): CommittedExtent {
                producer(
                    object : FetchPublishSink {
                        override suspend fun write(bytes: ByteArray) = Unit
                    },
                )
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
            sessionId = "test-session",
            ownerScope = backgroundScope,
            ownsScope = false,
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
        assertEquals(false, replacement.isCompleted)

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
    }

    @Test
    fun ownerMakesExactlyOneAttemptAndNeverRetriesATransientFailure() = runTest {
        val attempts = mutableListOf<Int>()
        val events = mutableListOf<FetchEvent>()
        val broker = broker(
            events = events,
            executor = FetchAttemptExecutor { _, attempt, _, emit ->
                attempts += attempt
                emit(FetchNetworkChunk(0, byteArrayOf(1, 2)))
                FetchAttemptDisposition.Failure(
                    FailureObservation.TransportIo(TransportIoKind.READ_TIMEOUT),
                )
            },
        )

        val outcome = broker.acquire(
            REQUEST,
            consumer("single-attempt", FetchConsumerKind.RESERVE),
        ).await()

        // M2-C: retry ownership moved to RecoveryCoordinator.
        assertEquals(listOf(1), attempts)
        assertEquals(1, outcome.attempts)
        assertEquals(
            FailureObservation.TransportIo(TransportIoKind.READ_TIMEOUT),
            outcome.failure,
        )
        // Legacy fetch-events-v3 projection keeps its M1 meaning.
        assertEquals(FetchOutcomeKind.RETRYABLE_TRANSPORT_FAILURE, outcome.kind)
        assertEquals(1, events.count { it.event == FetchEventKind.ATTEMPT_STARTED })
        assertEquals(2L, outcome.bytes.networkBytes)
    }

    @Test
    fun admissionRunsOnceImmediatelyBeforeTheSingleAttempt() = runTest {
        val order = mutableListOf<String>()
        val events = mutableListOf<FetchEvent>()
        val broker = broker(
            events = events,
            executor = FetchAttemptExecutor { _, _, _, emit ->
                order += "attempt"
                emit(FetchNetworkChunk(0, byteArrayOf(1, 2, 3, 4)))
                FetchAttemptDisposition.Success()
            },
        )

        val handle = broker.acquire(
            REQUEST,
            consumer("admitted", FetchConsumerKind.RESERVE),
            admission = FetchAttemptAdmission { fetchId, correlation ->
                order += "admit:$fetchId:$correlation"
            },
        )
        val outcome = handle.await()

        assertEquals(
            listOf("admit:${handle.fetchId}:${handle.fetchId}:attempt-1", "attempt"),
            order,
        )
        assertEquals(FetchOutcomeKind.SUCCESS, outcome.kind)
        assertNull(outcome.failure)
    }

    @Test
    fun refusedAdmissionMakesNoAttempt() = runTest {
        var executed = false
        val broker = broker(
            executor = FetchAttemptExecutor { _, _, _, _ ->
                executed = true
                FetchAttemptDisposition.Success()
            },
        )

        val outcome = broker.acquire(
            REQUEST,
            consumer("refused", FetchConsumerKind.RESERVE),
            admission = FetchAttemptAdmission { _, _ -> error("budget exhausted") },
        ).await()

        assertEquals(false, executed)
        assertEquals(0, outcome.attempts)
        assertEquals(FailureObservation.InternalFailure, outcome.failure)
    }

    @Test
    fun raisePriorityEscalatesTheRunningOwnerWithoutRestart() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val observed = mutableListOf<FetchPriority>()
        val events = mutableListOf<FetchEvent>()
        val broker = broker(
            events = events,
            executor = FetchAttemptExecutor { _, _, priority, emit ->
                observed += priority.value
                entered.complete(Unit)
                release.await()
                observed += priority.value
                emit(FetchNetworkChunk(0, byteArrayOf(1, 2, 3, 4)))
                FetchAttemptDisposition.Success()
            },
        )

        val handle = broker.acquire(
            REQUEST,
            consumer("reserve", FetchConsumerKind.RESERVE),
        )
        entered.await()
        handle.raisePriority(FetchPriority.PLAYBACK)
        handle.raisePriority(FetchPriority.RESERVE)
        release.complete(Unit)
        handle.await()

        assertEquals(listOf(FetchPriority.RESERVE, FetchPriority.PLAYBACK), observed)
        assertEquals(1, events.count { it.event == FetchEventKind.PRIORITY_RAISED })
        assertEquals(1, events.count { it.event == FetchEventKind.ATTEMPT_STARTED })
    }

    @Test
    fun ownerCancelledBeforeItsBodyRunsStillReachesATerminal() {
        val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val broker = FetchBroker(
                publisher = FakePublisher(),
                executor = FetchAttemptExecutor { _, _, _, emit ->
                    emit(FetchNetworkChunk(0, byteArrayOf(1, 2, 3, 4)))
                    FetchAttemptDisposition.Success()
                },
                sessionId = "test-session",
                ownerScope = scope,
                ownsScope = false,
                monotonicClockNs = { 1L },
            )
            repeat(200) { index ->
                val outcome = kotlinx.coroutines.runBlocking {
                    val handle = broker.acquire(
                        REQUEST,
                        consumer("fast-close-$index", FetchConsumerKind.RESERVE),
                    )
                    handle.close()
                    kotlinx.coroutines.withTimeout(5_000) { handle.awaitTerminal() }
                }
                assertTrue(
                    outcome.kind in setOf(
                        FetchOutcomeKind.SUCCESS,
                        FetchOutcomeKind.CANCELLED_NO_CONSUMERS,
                    ),
                    outcome.toString(),
                )
            }
            assertEquals(0, broker.activeFetchCountForTest())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun sameKeyWithDifferentImmutableWorkFailsFast() = runTest {
        val started = CompletableDeferred<Unit>()
        val broker = broker(
            executor = FetchAttemptExecutor { _, _, _, _ ->
                started.complete(Unit)
                CompletableDeferred<Unit>().await()
                FetchAttemptDisposition.Success()
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
    fun dependencyOrderingDoesNotCreateFalseFetchIdentityConflict() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var executions = 0
        val dependencyA = ExtentId("dependency-a")
        val dependencyB = ExtentId("dependency-b")
        val firstRequest = REQUEST.copy(
            extentSpec = REQUEST.extentSpec.copy(
                dependencyExtentIds = listOf(dependencyA, dependencyB),
            ),
        )
        val reorderedRequest = firstRequest.copy(
            extentSpec = firstRequest.extentSpec.copy(
                dependencyExtentIds = listOf(dependencyB, dependencyA),
            ),
        )
        val broker = broker(
            executor = FetchAttemptExecutor { _, _, _, emit ->
                executions += 1
                started.complete(Unit)
                release.await()
                emit(FetchNetworkChunk(0, byteArrayOf(1, 2, 3, 4)))
                FetchAttemptDisposition.Success()
            },
        )

        val first = broker.acquire(
            firstRequest,
            consumer("ordered-one", FetchConsumerKind.RESERVE),
        )
        started.await()
        val second = broker.acquire(
            reorderedRequest,
            consumer("ordered-two", FetchConsumerKind.RESERVE),
        )

        assertEquals(first.fetchId, second.fetchId)
        assertEquals(1, executions)
        release.complete(Unit)
        assertEquals(FetchOutcomeKind.SUCCESS, first.await().kind)
        assertEquals(FetchOutcomeKind.SUCCESS, second.await().kind)
    }

    @Test
    fun fatalErrorCleansRegistryButIsNotSwallowed() = runTest {
        val fatal = CompletableDeferred<Throwable>()
        val handler = CoroutineExceptionHandler { _, error ->
            fatal.complete(error)
        }
        val scope = CoroutineScope(
            SupervisorJob() +
                StandardTestDispatcher(testScheduler) +
                handler,
        )
        val broker = FetchBroker(
            publisher = FakePublisher(),
            executor = FetchAttemptExecutor { _, _, _, _ ->
                throw AssertionError("fatal-owner")
            },
            sessionId = "test-session",
            ownerScope = scope,
            ownsScope = false,
        )

        try {
            val handle = broker.acquire(
                REQUEST,
                consumer("fatal", FetchConsumerKind.RESERVE),
            )
            runCurrent()

            assertEquals(
                FetchOutcomeKind.INTERNAL_FAILURE,
                handle.await().kind,
            )
            assertEquals(
                AssertionError::class.java,
                fatal.await().javaClass,
            )
            assertEquals(0, broker.activeFetchCountForTest())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun shutdownCancelsAndJoinsActiveOwnerBeforeReturning() = runTest {
        val started = CompletableDeferred<Unit>()
        val events = mutableListOf<FetchEvent>()
        val broker = broker(
            executor = FetchAttemptExecutor { _, _, _, _ ->
                started.complete(Unit)
                awaitCancellation()
            },
            events = events,
        )

        broker.acquire(
            REQUEST,
            consumer("shutdown", FetchConsumerKind.RESERVE),
        )
        started.await()

        broker.shutdown()

        assertEquals(0, broker.activeFetchCountForTest())
        assertEquals(
            FetchOutcomeKind.CANCELLED_BROKER_SHUTDOWN,
            events.last {
                it.event == FetchEventKind.OWNER_CANCELLED
            }.outcome,
        )
    }

    @Test
    fun rejectedRangeNeverPublishesAndIsCountedSeparately() = runTest {
        var committed = 0
        val publisher = FakePublisher { committed += 1 }
        val broker = FetchBroker(
            publisher = publisher,
            executor = FetchAttemptExecutor { _, _, _, emit ->
                emit(FetchNetworkChunk(1, byteArrayOf(1, 2)))
                FetchAttemptDisposition.Success()
            },
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

    @Test
    fun deliveryBindingIsPassedToTheBoundExecutorAndAJoinNeverChangesIt() = runTest {
        val release = CompletableDeferred<Unit>()
        val executor = BoundExecutor(release)
        val broker = FetchBroker(
            publisher = FakePublisher(),
            executor = executor,
            sessionId = "test-session",
            ownerScope = backgroundScope,
            ownsScope = false,
        )
        val initial = DeliveryBindingSnapshot(
            DeliveryBindingRevision("binding-1"),
            TestDeliveryMaterial(),
        )
        val other = DeliveryBindingSnapshot(
            DeliveryBindingRevision("binding-2"),
            TestDeliveryMaterial(),
        )

        val owner = broker.acquire(
            REQUEST,
            consumer("bound-owner", FetchConsumerKind.RESERVE),
            deliveryBinding = initial,
        )
        executor.entered.await()
        val joiner = broker.acquire(
            REQUEST,
            consumer("bound-joiner", FetchConsumerKind.PLAYBACK),
            deliveryBinding = other,
        )

        assertEquals(FetchAcquireDisposition.JOINED_RUNNING, joiner.acquireDisposition)
        release.complete(Unit)

        assertEquals(FetchOutcomeKind.SUCCESS, owner.await().kind)
        assertEquals(FetchOutcomeKind.SUCCESS, joiner.await().kind)
        assertEquals(1, executor.executions)
        assertEquals(1, executor.admissions)
        assertEquals(listOf(DeliveryBindingRevision("binding-1")), executor.bindingRevisions)
    }

    @Test
    fun ownerWithBindingAndNonBoundExecutorFailsClosedWithZeroAttempts() = runTest {
        var executed = false
        var admissions = 0
        val broker = broker(
            executor = FetchAttemptExecutor { _, _, _, _ ->
                executed = true
                FetchAttemptDisposition.Success()
            },
        )

        val outcome = broker.acquire(
            REQUEST,
            consumer("unbound-capability", FetchConsumerKind.RESERVE),
            admission = FetchAttemptAdmission { _, _ -> admissions += 1 },
            deliveryBinding = DeliveryBindingSnapshot(
                DeliveryBindingRevision("binding-1"),
                TestDeliveryMaterial(),
            ),
        ).await()

        assertFalse(executed)
        assertEquals(0, admissions)
        assertEquals(0, outcome.attempts)
        assertEquals(FetchOutcomeKind.INTERNAL_FAILURE, outcome.kind)
        assertEquals(FailureObservation.InternalFailure, outcome.failure)
    }

    private fun TestScope.broker(
        executor: FetchAttemptExecutor,
        events: MutableList<FetchEvent> = mutableListOf(),
    ): FetchBroker = FetchBroker(
        publisher = FakePublisher(),
        executor = executor,
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
    ) : FetchPublisher {        override suspend fun publish(
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
            return committed(spec)
        }
    }

    /**
     * Delivery-bound executor fake: records the binding of every execution
     * and the number of physical admissions. The unbound and correlated paths
     * must never be used for a bound owner.
     */
    private class BoundExecutor(
        private val gate: CompletableDeferred<Unit>? = null,
    ) : DeliveryBoundFetchAttemptExecutor {
        val bindingRevisions = mutableListOf<DeliveryBindingRevision>()
        var executions = 0
        var admissions = 0
        val entered = CompletableDeferred<Unit>()

        override suspend fun execute(
            request: FetchRequest,
            attempt: Int,
            priority: StateFlow<FetchPriority>,
            emitChunk: suspend (FetchNetworkChunk) -> Unit,
        ): FetchAttemptDisposition = error("a bound owner must use the bound path")

        override suspend fun executeCorrelated(
            request: FetchRequest,
            attempt: Int,
            priority: StateFlow<FetchPriority>,
            onTransportCorrelation: suspend (String) -> Unit,
            emitChunk: suspend (FetchNetworkChunk) -> Unit,
        ): FetchAttemptDisposition = error("a bound owner must use the bound path")

        override suspend fun executeWithDeliveryBinding(
            request: FetchRequest,
            attempt: Int,
            priority: StateFlow<FetchPriority>,
            deliveryBinding: DeliveryBindingSnapshot,
            onPhysicalAttemptStart: () -> Unit,
            onTransportCorrelation: suspend (String) -> Unit,
            emitChunk: suspend (FetchNetworkChunk) -> Unit,
        ): FetchAttemptDisposition {
            executions += 1
            bindingRevisions += deliveryBinding.revision
            admissions += 1
            onPhysicalAttemptStart()
            entered.complete(Unit)
            gate?.await()
            emitChunk(FetchNetworkChunk(0, byteArrayOf(1, 2, 3, 4)))
            return FetchAttemptDisposition.Success()
        }
    }

    private class TestDeliveryMaterial : DeliveryMaterial

    private companion object {
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
