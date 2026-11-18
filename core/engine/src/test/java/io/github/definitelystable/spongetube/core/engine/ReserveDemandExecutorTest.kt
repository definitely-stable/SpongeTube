package io.github.definitelystable.spongetube.core.engine

import io.github.definitelystable.spongetube.core.engine.recovery.FailureObservation
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryAttemptGate
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryAttemptPermit
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryBudgetEventKind
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumerKind
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryPermitReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(SpongeBridgeApi::class)
class ReserveDemandExecutorTest {
    @Test
    fun fillsTimedReserveInDependencyOrderWithOneOwnerAtATime() = runBlocking {
        val harness = BridgeHarness().start()
        try {
            val fixture = harness.fixture
            val result = harness.runtime.fillReserveOnce(
                VIDEO, 0, 20_000_000L, 3,
            )
            assertEquals(ReserveExecutionStop.TARGET_REACHED, result.stop)
            assertEquals(0L, result.initialReserveUs)
            assertEquals(20_000_000L, result.finalReserveUs)
            assertEquals(
                listOf(
                    fixture.videoInit.fetchKey,
                    fixture.video[0].fetchKey,
                    fixture.video[1].fetchKey,
                ),
                result.requestedFetchKeys,
            )
            assertEquals(
                result.requestedFetchKeys.map(FetchKey::value),
                harness.origin.executions.toList(),
            )
            assertEquals(0, harness.broker.activeFetchCountForTest())
        } finally {
            harness.runtime.shutdown()
        }
    }

    @Test
    fun alreadyDurableCoverageAndPreseededDependenciesAreNotRefetched() = runBlocking {
        val harness = BridgeHarness()
        val fixture = harness.fixture
        fixture.seed(harness.store, fixture.videoInit, fixture.video[0])
        harness.start()
        try {
            val full = harness.runtime.fillReserveOnce(VIDEO, 0, 20_000_000L, 3)
            assertEquals(ReserveExecutionStop.TARGET_REACHED, full.stop)
            assertEquals(10_000_000L, full.initialReserveUs)
            assertEquals(listOf(fixture.video[1].fetchKey), full.requestedFetchKeys)

            val repeated = harness.runtime.fillReserveOnce(VIDEO, 0, 20_000_000L, 3)
            assertEquals(ReserveExecutionStop.TARGET_REACHED, repeated.stop)
            assertTrue(repeated.requestedFetchKeys.isEmpty())
            assertEquals(listOf(fixture.video[1].fetchKey.value), harness.origin.executions)
        } finally {
            harness.runtime.shutdown()
        }
    }

    @Test
    fun capStopsAfterTwoRequestsWithoutDownloadingRestOfVideo() = runBlocking {
        val harness = BridgeHarness().start()
        try {
            val result = harness.runtime.fillReserveOnce(VIDEO, 0, 30_000_000L, 2)
            assertEquals(ReserveExecutionStop.PLAN_DEPLETED, result.stop)
            assertTrue(result.planTruncated)
            assertEquals(10_000_000L, result.finalReserveUs)
            assertEquals(2, result.requestedFetchKeys.size)
            assertFalse(harness.store.contains(harness.fixture.video[1].extentId))
            assertEquals(2, harness.origin.executions.size)
        } finally {
            harness.runtime.shutdown()
        }
    }

    @Test
    fun terminalFailureStopsThePassAndPreservesAlreadyPublishedBytes() = runBlocking {
        val harness = BridgeHarness().start()
        harness.origin.failure = FetchAttemptDisposition.Failure(
            FailureObservation.HttpResponse(statusCode = 403),
        )
        try {
            val result = harness.runtime.fillReserveOnce(VIDEO, 0, 20_000_000L, 3)
            assertEquals(ReserveExecutionStop.FETCH_FAILED, result.stop)
            assertNotNull(result.terminalFetchOutcome)
            assertEquals(1, result.requestedFetchKeys.size)
            assertEquals(0L, result.finalReserveUs)
            assertTrue(harness.origin.executions.none {
                it == harness.fixture.video[0].fetchKey.value
            })
            assertEquals(0, harness.broker.activeFetchCountForTest())
        } finally {
            harness.runtime.shutdown()
        }
    }

    @Test
    fun cancellingPassClosesLastReserveLeaseAndPreventsNextAdmission() = runBlocking {
        val harness = BridgeHarness().start()
        val init = harness.fixture.videoInit
        val gate = harness.origin.gate(init.fetchKey)
        try {
            val work = async(Dispatchers.Default) {
                harness.runtime.fillReserveOnce(VIDEO, 0, 20_000_000L, 3)
            }
            withTimeout(TIMEOUT_MS) { harness.origin.entered(init.fetchKey).await() }
            assertEquals(listOf(init.fetchKey.value), harness.origin.executions.toList())

            work.cancelAndJoin()
            withTimeout(TIMEOUT_MS) {
                while (harness.broker.activeFetchCountForTest() > 0) delay(10)
            }
            assertFalse(harness.store.contains(init.extentId))
            assertEquals(listOf(init.fetchKey.value), harness.origin.executions.toList())
            assertFalse(gate.isCompleted)
        } finally {
            harness.runtime.shutdown()
        }
    }

    @Test
    fun secondCallerWaitsThenReplansInsteadOfStartingDuplicateWork() = runBlocking {
        val harness = BridgeHarness().start()
        val init = harness.fixture.videoInit
        val gate = harness.origin.gate(init.fetchKey)
        try {
            val first = async(Dispatchers.Default) {
                harness.runtime.fillReserveOnce(VIDEO, 0, 10_000_000L, 2)
            }
            withTimeout(TIMEOUT_MS) { harness.origin.entered(init.fetchKey).await() }
            val secondStarted = CompletableDeferred<Unit>()
            val second = async(Dispatchers.Default) {
                secondStarted.complete(Unit)
                harness.runtime.fillReserveOnce(VIDEO, 0, 10_000_000L, 2)
            }
            withTimeout(TIMEOUT_MS) { secondStarted.await() }
            assertEquals(1, harness.origin.executions.size)
            gate.complete(Unit)

            val a = withTimeout(TIMEOUT_MS) { first.await() }
            val b = withTimeout(TIMEOUT_MS) { second.await() }
            assertEquals(ReserveExecutionStop.TARGET_REACHED, a.stop)
            assertEquals(ReserveExecutionStop.TARGET_REACHED, b.stop)
            assertEquals(10_000_000L, b.initialReserveUs)
            assertTrue(b.requestedFetchKeys.isEmpty())
            assertEquals(
                listOf(init.fetchKey.value, harness.fixture.video[0].fetchKey.value),
                harness.origin.executions.toList(),
            )
        } finally {
            harness.runtime.shutdown()
        }
    }

    @Test
    fun playbackJoinsActiveReserveWithoutSecondPhysicalFetch() = runBlocking {
        val harness = BridgeHarness().start()
        val init = harness.fixture.videoInit
        val gate = harness.origin.gate(init.fetchKey)
        try {
            val reserve = async(Dispatchers.Default) {
                harness.runtime.fillReserveOnce(VIDEO, 0, 10_000_000L, 2)
            }
            withTimeout(TIMEOUT_MS) { harness.origin.entered(init.fetchKey).await() }
            val playback = async(Dispatchers.IO) {
                harness.readAll("v-init").first
            }
            withTimeout(TIMEOUT_MS) {
                while (
                    harness.recoveryEvidence.budgetEvents().count {
                        it.fetchKey == init.fetchKey.value &&
                            it.kind == RecoveryBudgetEventKind.CONSUMER_JOINED
                    } < 2
                ) delay(10)
            }
            assertEquals(1, harness.origin.executions.count {
                it == init.fetchKey.value
            })
            assertTrue(
                harness.recoveryEvidence.budgetEvents().any {
                    it.fetchKey == init.fetchKey.value &&
                        it.kind == RecoveryBudgetEventKind.PRIORITY_RAISED
                },
            )
            gate.complete(Unit)
            assertTrue(withTimeout(TIMEOUT_MS) { playback.await() }.contentEquals(
                harness.fixture.bytesOf(init),
            ))
            assertEquals(
                ReserveExecutionStop.TARGET_REACHED,
                withTimeout(TIMEOUT_MS) { reserve.await() }.stop,
            )
            assertEquals(1, harness.origin.executions.count {
                it == init.fetchKey.value
            })
        } finally {
            harness.runtime.shutdown()
        }
    }

    @Test
    fun pausedAttemptDoesNotReachOriginUntilGuardRestoresPermit() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val permit = CompletableDeferred<Unit>()
        val attemptGate = RecoveryAttemptGate {
            entered.complete(Unit)
            permit.await()
            RecoveryAttemptPermit(
                routeEpoch = null,
                reason = RecoveryPermitReason.ALWAYS_PERMIT,
            )
        }
        val harness = BridgeHarness(attemptGate = attemptGate).start()
        try {
            val reserve = async(Dispatchers.Default) {
                harness.runtime.fillReserveOnce(VIDEO, 0, 10_000_000L, 2)
            }
            withTimeout(TIMEOUT_MS) { entered.await() }
            assertTrue(harness.origin.executions.isEmpty())
            permit.complete(Unit)
            assertEquals(
                ReserveExecutionStop.TARGET_REACHED,
                withTimeout(TIMEOUT_MS) { reserve.await() }.stop,
            )
            assertEquals(2, harness.origin.executions.size)
        } finally {
            harness.runtime.shutdown()
        }
    }

    @Test
    fun cancellationDuringRoutePauseNeverStartsAPhysicalAttempt() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val blocker = CompletableDeferred<Unit>()
        val harness = BridgeHarness(
            attemptGate = RecoveryAttemptGate {
                entered.complete(Unit)
                blocker.await()
                RecoveryAttemptPermit(
                    routeEpoch = null,
                    reason = RecoveryPermitReason.ALWAYS_PERMIT,
                )
            },
        ).start()
        try {
            val reserve = async(Dispatchers.Default) {
                harness.runtime.fillReserveOnce(VIDEO, 0, 10_000_000L, 2)
            }
            withTimeout(TIMEOUT_MS) { entered.await() }
            reserve.cancelAndJoin()
            blocker.complete(Unit)
            assertTrue(harness.origin.executions.isEmpty())
        } finally {
            harness.runtime.shutdown()
        }
    }

    @Test
    fun shutdownDuringBlockedOwnerPreventsNextUnitAndFurtherPasses() = runBlocking {
        val harness = BridgeHarness().start()
        val init = harness.fixture.videoInit
        harness.origin.gate(init.fetchKey)
        val reserve = async(Dispatchers.Default) {
            runCatching {
                harness.runtime.fillReserveOnce(VIDEO, 0, 20_000_000L, 3)
            }
        }
        withTimeout(TIMEOUT_MS) { harness.origin.entered(init.fetchKey).await() }
        harness.runtime.shutdown()
        val result = withTimeout(TIMEOUT_MS) { reserve.await() }
        assertTrue(result.isFailure)
        assertTrue(harness.origin.executions.none {
            it == harness.fixture.video[0].fetchKey.value
        })
        val next = runCatching {
            harness.runtime.fillReserveOnce(VIDEO, 0, 10_000_000L, 2)
        }
        assertTrue(next.isFailure)
        assertEquals(0, harness.broker.activeFetchCountForTest())
    }

    private companion object {
        const val TIMEOUT_MS = 8_000L
        val VIDEO = PlaybackRequirementSet(
            io.github.definitelystable.spongetube.core.storage.MediaAssetId("fixture:TEST"),
            mapOf("video" to "v1"),
        )
    }
}
