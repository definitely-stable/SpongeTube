package io.github.definitelystable.spongetube.core.engine.recovery

import io.github.definitelystable.spongetube.core.engine.FetchAttemptDisposition
import io.github.definitelystable.spongetube.core.engine.FetchEventKind
import io.github.definitelystable.spongetube.core.engine.FetchRequest
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingCaller
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingEventKind
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingRefreshAdmission
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingRefresher
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingRevision
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryMaterialRefresh
import io.github.definitelystable.spongetube.core.engine.delivery.ProviderWallClock
import io.github.definitelystable.spongetube.core.engine.delivery.RetryAfterKind
import io.github.definitelystable.spongetube.core.engine.delivery.RetryAfterObservation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * M2-D provider wait and delivery-binding refresh executed by the
 * RecoveryCoordinator over the deterministic harness (TEST_POLICY). Every
 * case is driven by scripted provider statuses and a scripted refresher: no
 * real sleeping and no hidden retry anywhere.
 */
@Timeout(30)
class ProviderRecoveryTest {
    private val harnesses = mutableListOf<RecoveryHarness>()

    @AfterEach
    fun tearDown() {
        harnesses.forEach(RecoveryHarness::shutdown)
    }

    private fun harness(
        refresher: DeliveryBindingRefresher? = null,
        gate: RecoveryAttemptGate = RecoveryAttemptGate.ALWAYS_PERMIT,
        sleeper: RecoverySleeper? = null,
        policy: RecoveryPolicy = TEST_POLICY,
        providerWallClock: ProviderWallClock =
            ProviderWallClock { HARNESS_PROVIDER_WALL_CLOCK_UTC_MS },
    ): RecoveryHarness =
        RecoveryHarness(
            policy = policy,
            gate = gate,
            sleeper = sleeper,
            refresher = refresher,
            providerWallClock = providerWallClock,
        ).also(harnesses::add)

    private fun delaySeconds(seconds: Long): RetryAfterObservation =
        RetryAfterObservation(RetryAfterKind.DELAY_SECONDS, delaySeconds = seconds)

    private fun httpDate(notBeforeUtcEpochMs: Long): RetryAfterObservation =
        RetryAfterObservation(RetryAfterKind.HTTP_DATE, notBeforeUtcEpochMs = notBeforeUtcEpochMs)

    private fun RecoveryHarness.charges(
        dimension: RecoveryBudgetDimension,
    ): List<RecoveryBudgetEvent> =
        budget(RecoveryBudgetEventKind.CHARGE)
            .filter { checkNotNull(it.charge).dimension == dimension }

    private fun RecoveryHarness.delays(): List<Long> =
        (sleeper as RecordingSleeper).delays.toList()

    /** Every owner of the harness ran exactly the original immutable work. */
    private fun RecoveryHarness.assertSameWork(work: FetchRequest) {
        val owners = fetchEvents.filter { it.event == FetchEventKind.OWNER_REGISTERED }
        assertTrue(owners.isNotEmpty(), "no owner was registered")
        owners.forEach { owner ->
            assertEquals(work.fetchKey, owner.fetchKey)
            assertEquals(work.extentSpec.byteStart, owner.requestedByteStart)
            assertEquals(work.extentSpec.byteEndExclusive, owner.requestedByteEndExclusive)
        }
        assertEquals(0, fetchEvents.count { it.fetchKey != work.fetchKey })
    }

    @Test
    fun rateLimitWithDelayWaitsThenRetriesSameChain() {
        val refresher = TestRefresher()
        val h = harness(refresher)
        val work = RecoveryHarness.work("retry-after-delay")
        h.origin.script(work.fetchKey, h.origin.http(429, delaySeconds(2)))

        val outcome = h.blocking { h.acquire(work, "playback").await() }

        assertTrue(outcome.isSuccess, "outcome=$outcome failures=${h.failures}")
        assertEquals(2, h.origin.executions(work.fetchKey))
        assertEquals(listOf(2_000L), h.delays())
        assertEquals(
            listOf(1, 2),
            h.charges(RecoveryBudgetDimension.REMOTE_ATTEMPT)
                .map { checkNotNull(it.charge).spentAfter },
        )
        assertTrue(h.charges(RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH).isEmpty())
        assertEquals(BINDING_1, h.bindings!!.current().revision)
        assertTrue(refresher.calls.isEmpty())
        assertEquals(
            0,
            h.bindingEvents.count {
                it.kind != DeliveryBindingEventKind.BINDING_SELECTED_FOR_ATTEMPT
            },
        )

        val failure = h.failures.single()
        assertEquals(outcome.recoveryChainId, failure.recoveryChainId)
        assertEquals(FailureClassification.PROVIDER_RATE_LIMITED, failure.classification)
        assertEquals(RecoveryDecisionKind.WAIT_UNTIL_PROVIDER, failure.decision.kind)
        assertEquals(RecoveryActionKind.WAIT_PROVIDER, failure.action.kind)
        val wait = checkNotNull(failure.action.providerWait)
        assertEquals(RetryAfterKind.DELAY_SECONDS, wait.rawKind)
        assertEquals(2_000L, wait.waitMs)
        assertNull(wait.notBeforeUtcEpochMs)
        assertNull(wait.wallClockNowUtcEpochMs)
        assertEquals(2, h.charges(RecoveryBudgetDimension.REMOTE_ATTEMPT).size)

        // DESIGN 8: the wait advances the harness monotonic clock, so the
        // next charge is at least waitMs after the failure row.
        val chargeAfterWait = h.budget(RecoveryBudgetEventKind.CHARGE)
            .single { it.attemptCorrelationId == "fetch-2:attempt-1" }
        assertTrue(
            chargeAfterWait.elapsedRealtimeNs >=
                failure.elapsedRealtimeNs + 2_000L * 1_000_000,
        )
        h.assertSameWork(work)
    }

    @Test
    fun rateLimitWithHttpDateUsesProviderWallClock() {
        val h = harness(TestRefresher())
        val future = RecoveryHarness.work("retry-after-date")
        h.origin.script(
            future.fetchKey,
            h.origin.http(
                429,
                httpDate(HARNESS_PROVIDER_WALL_CLOCK_UTC_MS + 2_000),
            ),
        )

        assertTrue(h.blocking { h.acquire(future, "playback").await() }.isSuccess)
        val futureWait = checkNotNull(h.failures.single().action.providerWait)
        assertEquals(RetryAfterKind.HTTP_DATE, futureWait.rawKind)
        assertEquals(2_000L, futureWait.waitMs)
        assertEquals(
            HARNESS_PROVIDER_WALL_CLOCK_UTC_MS + 2_000,
            futureWait.notBeforeUtcEpochMs,
        )
        assertEquals(HARNESS_PROVIDER_WALL_CLOCK_UTC_MS, futureWait.wallClockNowUtcEpochMs)
        assertEquals(
            listOf(1, 2),
            h.charges(RecoveryBudgetDimension.REMOTE_ATTEMPT)
                .map { checkNotNull(it.charge).spentAfter },
        )
        assertTrue(h.charges(RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH).isEmpty())
        h.assertSameWork(future)

        val past = RecoveryHarness.work("retry-after-past")
        h.origin.script(
            past.fetchKey,
            h.origin.http(
                429,
                httpDate(HARNESS_PROVIDER_WALL_CLOCK_UTC_MS - 5_000),
            ),
        )

        assertTrue(h.blocking { h.acquire(past, "playback-past").await() }.isSuccess)
        val pastWait = checkNotNull(h.failures.last().action.providerWait)
        assertEquals(0L, pastWait.waitMs)
        assertEquals(HARNESS_PROVIDER_WALL_CLOCK_UTC_MS, pastWait.wallClockNowUtcEpochMs)
        assertEquals(listOf(2_000L, 0L), h.delays())
    }

    @Test
    fun rateLimitWaitConsumesNoBudget() {
        val h = harness(TestRefresher())
        val work = RecoveryHarness.work("wait-no-budget")
        h.origin.script(work.fetchKey, h.origin.http(429, delaySeconds(1)))

        assertTrue(h.blocking { h.acquire(work, "playback").await() }.isSuccess)

        assertEquals(
            listOf(1, 2),
            h.charges(RecoveryBudgetDimension.REMOTE_ATTEMPT)
                .map { checkNotNull(it.charge).spentAfter },
        )
        assertTrue(h.charges(RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH).isEmpty())
        assertTrue(h.budget(RecoveryBudgetEventKind.BACKOFF_SCHEDULED).isEmpty())
        val failure = h.failures.single()
        assertEquals(RecoveryActionKind.WAIT_PROVIDER, failure.action.kind)
        assertEquals(1, failure.context.deliveryBindingRefreshesRemaining)
        assertEquals(3, failure.context.remoteAttemptsRemaining)
        val spent = h.budgetEvents.last().spent
        assertEquals(2, spent.getValue(RecoveryBudgetDimension.REMOTE_ATTEMPT))
        assertEquals(0, spent.getValue(RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH))
        h.assertSameWork(work)
    }

    @Test
    fun rateLimitWithoutDemandDoesNotWait() {
        val h = harness(TestRefresher())
        val work = RecoveryHarness.work("wait-no-demand")
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        h.origin.script(work.fetchKey, { _, _, _ ->
            entered.complete(Unit)
            // A blocking transfer has no suspension point where the
            // cancellation of the released lease could land.
            check(release.await(5, TimeUnit.SECONDS))
            FetchAttemptDisposition.Failure(
                FailureObservation.HttpResponse(
                    statusCode = 429,
                    retryAfter = delaySeconds(2),
                    deliveryBindingRevision = h.origin.bindingRevisions.lastOrNull(),
                ),
            )
        })

        val handle = h.blocking { h.acquire(work, "playback") }
        h.blocking { entered.await() }
        handle.close()
        release.countDown()
        h.awaitCondition { h.budget(RecoveryBudgetEventKind.CHAIN_TERMINATED).isNotEmpty() }

        val terminal = h.budget(RecoveryBudgetEventKind.CHAIN_TERMINATED).single()
        assertEquals(RecoveryTerminalReason.NO_REMAINING_DEMAND, terminal.terminalReason)
        assertEquals(
            FailureClassification.PROVIDER_RATE_LIMITED,
            h.failures.single().classification,
        )
        assertEquals(RecoveryDecisionKind.COMPLETE_NO_DEMAND, h.failures.single().decision.kind)
        assertEquals(RecoveryActionKind.TERMINATE_NO_DEMAND, h.failures.single().action.kind)
        assertEquals(1, h.origin.executions(work.fetchKey))
        assertTrue(h.delays().isEmpty())
    }

    @Test
    fun rateLimitWithNoRemoteAttemptsDoesNotWait() {
        val h = harness(TestRefresher())
        val work = RecoveryHarness.work("wait-exhausted")
        h.origin.script(
            work.fetchKey,
            h.origin.http(429, delaySeconds(1)),
            h.origin.http(429, delaySeconds(1)),
            h.origin.http(429, delaySeconds(1)),
            h.origin.http(429, delaySeconds(1)),
        )

        val outcome = h.blocking { h.acquire(work, "playback").await() }

        assertEquals(RecoveryTerminalReason.BUDGET_EXHAUSTED, outcome.terminalReason)
        assertEquals(4, h.origin.executions(work.fetchKey))
        assertEquals(listOf(1_000L, 1_000L, 1_000L), h.delays())
        assertEquals(4, h.failures.size)
        assertTrue(
            h.failures.all {
                it.decision.kind == RecoveryDecisionKind.WAIT_UNTIL_PROVIDER
            },
        )
        val last = h.failures.last()
        assertEquals(RecoveryActionKind.TERMINATE_BUDGET_EXHAUSTED, last.action.kind)
        assertEquals(RecoveryBudgetDimension.REMOTE_ATTEMPT, last.action.exhaustedDimension)
        assertEquals(RecoveryBudgetDimension.REMOTE_ATTEMPT, outcome.exhaustedDimension)
        assertNull(last.action.providerWait)
    }

    @Test
    fun rateLimitWithoutRetryAfterFailsClosed() {
        val h = harness(TestRefresher())
        val work = RecoveryHarness.work("wait-absent")
        h.origin.script(work.fetchKey, h.origin.http(429))

        val outcome = h.blocking { h.acquire(work, "playback").await() }

        assertEquals(RecoveryTerminalReason.TERMINAL_FAILURE, outcome.terminalReason)
        assertEquals(1, h.origin.executions(work.fetchKey))
        assertTrue(h.delays().isEmpty())
        val failure = h.failures.single()
        assertEquals(RecoveryDecisionKind.FAIL_TERMINAL, failure.decision.kind)
        assertEquals(RecoveryDecisionReason.RETRY_AFTER_ABSENT, failure.decision.reason)
        assertEquals(RecoveryActionKind.TERMINATE_FAILURE, failure.action.kind)
    }

    @Test
    fun rateLimitWithMalformedRetryAfterFailsClosed() {
        val h = harness(TestRefresher())
        val work = RecoveryHarness.work("wait-malformed")
        h.origin.script(work.fetchKey, h.origin.http(429, RetryAfterObservation.MALFORMED))

        val outcome = h.blocking { h.acquire(work, "playback").await() }

        assertEquals(RecoveryTerminalReason.TERMINAL_FAILURE, outcome.terminalReason)
        assertEquals(1, h.origin.executions(work.fetchKey))
        assertTrue(h.delays().isEmpty())
        val failure = h.failures.single()
        assertEquals(RecoveryDecisionReason.RETRY_AFTER_MALFORMED, failure.decision.reason)
        assertEquals(RecoveryActionKind.TERMINATE_FAILURE, failure.action.kind)
    }

    @Test
    fun retryAfterZeroWaitsZeroThenRetries() {
        val h = harness(TestRefresher())
        val work = RecoveryHarness.work("wait-zero")
        h.origin.script(work.fetchKey, h.origin.http(429, delaySeconds(0)))

        assertTrue(h.blocking { h.acquire(work, "playback").await() }.isSuccess)

        assertEquals(listOf(0L), h.delays())
        assertEquals(2, h.origin.executions(work.fetchKey))
        assertEquals(2, h.charges(RecoveryBudgetDimension.REMOTE_ATTEMPT).size)
    }

    @Test
    fun staleBindingRefreshesOnce() {
        val refresher = TestRefresher()
        refresher.enqueue(TestRefresher.material(2))
        val h = harness(refresher)
        val work = RecoveryHarness.work("stale-refresh")
        h.origin.script(
            work.fetchKey,
            h.origin.http(403, signal = ProviderSignal.BINDING_STALE_CONFIRMED),
        )

        val outcome = h.blocking { h.acquire(work, "playback").await() }

        assertTrue(outcome.isSuccess, "outcome=$outcome failures=${h.failures}")
        assertEquals(2, h.origin.executions(work.fetchKey))
        assertEquals(listOf(BINDING_1, BINDING_2), h.origin.bindingRevisions.toList())
        assertEquals(listOf("refresh-1"), refresher.calls.toList())
        assertEquals(1, h.bindings!!.refreshOperationCountForTest())
        assertEquals(BINDING_2, h.bindings.current().revision)

        assertEquals(
            listOf(1, 2),
            h.charges(RecoveryBudgetDimension.REMOTE_ATTEMPT)
                .map { checkNotNull(it.charge).spentAfter },
        )
        val refreshChargeEvent =
            h.charges(RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH).single()
        val refreshCharge = checkNotNull(refreshChargeEvent.charge)
        assertEquals(1, refreshCharge.amount)
        assertEquals(0, refreshCharge.spentBefore)
        assertEquals(1, refreshCharge.spentAfter)
        assertEquals("recovery-1:failure-1", refreshChargeEvent.failureId)
        assertNull(refreshChargeEvent.ownerOrdinal)
        assertNull(refreshChargeEvent.fetchId)
        assertNull(refreshChargeEvent.attemptCorrelationId)

        val failure = h.failures.single()
        assertEquals(outcome.recoveryChainId, failure.recoveryChainId)
        assertEquals(FailureClassification.DELIVERY_BINDING_STALE, failure.classification)
        assertEquals(RecoveryDecisionKind.REFRESH_DELIVERY_BINDING, failure.decision.kind)
        assertEquals(RecoveryDecisionReason.STALE_BINDING_SIGNAL, failure.decision.reason)
        assertEquals(RecoveryActionKind.REFRESH_DELIVERY_BINDING, failure.action.kind)
        val record = checkNotNull(failure.action.deliveryBinding)
        assertEquals(BINDING_1, record.expectedRevision)
        assertEquals(DeliveryBindingActionResult.REFRESHED, record.result)
        assertEquals(BINDING_2, record.currentRevision)
        assertEquals("refresh-1", record.refreshCorrelationId)
        assertTrue(record.charged)
        assertEquals(
            listOf(
                DeliveryBindingEventKind.REFRESH_REQUESTED,
                DeliveryBindingEventKind.REFRESH_STARTED,
                DeliveryBindingEventKind.REFRESH_SUCCEEDED,
            ),
            h.bindingEvents
                .filter { it.kind != DeliveryBindingEventKind.BINDING_SELECTED_FOR_ATTEMPT }
                .map { it.kind },
        )
        h.assertSameWork(work)
    }

    @Test
    fun bare403NeverRefreshes() {
        val refresher = TestRefresher()
        val h = harness(refresher)
        val work = RecoveryHarness.work("bare-403")
        h.origin.script(work.fetchKey, h.origin.http(403))

        val outcome = h.blocking { h.acquire(work, "playback").await() }

        assertEquals(RecoveryTerminalReason.TERMINAL_FAILURE, outcome.terminalReason)
        assertEquals(1, h.origin.executions(work.fetchKey))
        assertTrue(refresher.calls.isEmpty())
        assertEquals(0, h.bindings!!.refreshOperationCountForTest())
        val failure = h.failures.single()
        assertEquals(FailureClassification.PROVIDER_REJECTED, failure.classification)
        assertEquals(RecoveryDecisionKind.FAIL_TERMINAL, failure.decision.kind)
        assertEquals(RecoveryActionKind.TERMINATE_FAILURE, failure.action.kind)
        h.assertSameWork(work)
    }

    @Test
    fun staleBindingWithNoDemandDoesNotRefresh() {
        val refresher = TestRefresher()
        val h = harness(refresher)
        val work = RecoveryHarness.work("stale-no-demand")
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        h.origin.script(work.fetchKey, { _, _, _ ->
            entered.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS))
            FetchAttemptDisposition.Failure(
                FailureObservation.HttpResponse(
                    statusCode = 403,
                    providerSignal = ProviderSignal.BINDING_STALE_CONFIRMED,
                    deliveryBindingRevision = h.origin.bindingRevisions.lastOrNull(),
                ),
            )
        })

        val handle = h.blocking { h.acquire(work, "playback") }
        h.blocking { entered.await() }
        handle.close()
        release.countDown()
        h.awaitCondition { h.budget(RecoveryBudgetEventKind.CHAIN_TERMINATED).isNotEmpty() }

        assertEquals(
            RecoveryTerminalReason.NO_REMAINING_DEMAND,
            h.budget(RecoveryBudgetEventKind.CHAIN_TERMINATED).single().terminalReason,
        )
        assertEquals(1, h.origin.executions(work.fetchKey))
        assertTrue(refresher.calls.isEmpty())
        assertEquals(0, h.bindings!!.refreshOperationCountForTest())
        val failure = h.failures.single()
        assertEquals(FailureClassification.DELIVERY_BINDING_STALE, failure.classification)
        assertEquals(RecoveryDecisionKind.COMPLETE_NO_DEMAND, failure.decision.kind)
        assertEquals(RecoveryActionKind.TERMINATE_NO_DEMAND, failure.action.kind)
        h.assertSameWork(work)
    }

    @Test
    fun staleBindingWithNoRemoteAttemptsDoesNotRefresh() {
        val refresher = TestRefresher()
        val h = harness(refresher)
        val work = RecoveryHarness.work("stale-exhausted")
        h.origin.script(
            work.fetchKey,
            ScriptedOrigin.TIMEOUT,
            ScriptedOrigin.TIMEOUT,
            ScriptedOrigin.TIMEOUT,
            h.origin.http(403, signal = ProviderSignal.BINDING_STALE_CONFIRMED),
        )

        val outcome = h.blocking { h.acquire(work, "playback").await() }

        assertEquals(RecoveryTerminalReason.BUDGET_EXHAUSTED, outcome.terminalReason)
        assertEquals(4, h.origin.executions(work.fetchKey))
        assertTrue(refresher.calls.isEmpty())
        assertEquals(0, h.bindings!!.refreshOperationCountForTest())
        assertEquals(listOf(100L, 200L, 400L), h.delays())
        val last = h.failures.last()
        assertEquals(RecoveryDecisionKind.REFRESH_DELIVERY_BINDING, last.decision.kind)
        assertEquals(RecoveryActionKind.TERMINATE_BUDGET_EXHAUSTED, last.action.kind)
        assertEquals(RecoveryBudgetDimension.REMOTE_ATTEMPT, last.action.exhaustedDimension)
        assertEquals(4, h.charges(RecoveryBudgetDimension.REMOTE_ATTEMPT).size)
        h.assertSameWork(work)
    }

    @Test
    fun staleBindingWithRefreshBudgetExhaustedFailsOnlyWhenItMustStartRefresh() {
        val refresher = TestRefresher()
        refresher.enqueue(TestRefresher.material(2))
        val h = harness(refresher)
        val work = RecoveryHarness.work("stale-refresh-exhausted")
        h.origin.script(
            work.fetchKey,
            h.origin.http(403, signal = ProviderSignal.BINDING_STALE_CONFIRMED),
            h.origin.http(403, signal = ProviderSignal.BINDING_STALE_CONFIRMED),
        )

        val outcome = h.blocking { h.acquire(work, "playback").await() }

        assertEquals(RecoveryTerminalReason.BUDGET_EXHAUSTED, outcome.terminalReason)
        assertEquals(RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH, outcome.exhaustedDimension)
        assertEquals(2, h.origin.executions(work.fetchKey))
        assertEquals(listOf("refresh-1"), refresher.calls.toList())
        assertEquals(2, h.failures.size)
        val refreshRow = h.failures.first()
        assertEquals(
            DeliveryBindingActionResult.REFRESHED,
            checkNotNull(refreshRow.action.deliveryBinding).result,
        )
        val last = h.failures.last()
        assertEquals(0, last.context.deliveryBindingRefreshesRemaining)
        assertEquals(RecoveryActionKind.REFRESH_DELIVERY_BINDING, last.action.kind)
        assertEquals(
            RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH,
            last.action.exhaustedDimension,
        )
        val record = checkNotNull(last.action.deliveryBinding)
        assertEquals(DeliveryBindingActionResult.NOT_ADMITTED, record.result)
        assertFalse(record.charged)
        assertEquals(1, h.charges(RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH).size)
        h.assertSameWork(work)
    }

    @Test
    fun refreshDoesNotResetRemoteBudget() {
        val refresher = TestRefresher()
        refresher.enqueue(TestRefresher.material(2))
        val h = harness(refresher)
        val work = RecoveryHarness.work("refresh-ledger")
        h.origin.script(
            work.fetchKey,
            ScriptedOrigin.TIMEOUT,
            h.origin.http(403, signal = ProviderSignal.BINDING_STALE_CONFIRMED),
            ScriptedOrigin.TIMEOUT,
        )

        assertTrue(h.blocking { h.acquire(work, "playback").await() }.isSuccess)

        assertEquals(4, h.origin.executions(work.fetchKey))
        assertEquals(
            listOf(1, 2, 3, 4),
            h.charges(RecoveryBudgetDimension.REMOTE_ATTEMPT)
                .map { checkNotNull(it.charge).spentAfter },
        )
        assertEquals(1, h.charges(RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH).size)
        val spent = h.budgetEvents
            .map { it.spent.getValue(RecoveryBudgetDimension.REMOTE_ATTEMPT) }
        assertEquals(spent.sorted(), spent)
        assertEquals(listOf(100L, 200L), h.delays())
    }

    @Test
    fun refreshDoesNotChangeFetchKeyOrExtentSpec() {
        val refresher = TestRefresher()
        refresher.enqueue(TestRefresher.material(2))
        val h = harness(refresher)
        val work = RecoveryHarness.work("refresh-identity")
        h.origin.scriptBound(
            work.fetchKey,
            { request, _, _, revision ->
                assertEquals(work.fetchKey, request.fetchKey)
                assertEquals(work.extentSpec, request.extentSpec)
                FetchAttemptDisposition.Failure(
                    FailureObservation.HttpResponse(
                        statusCode = 403,
                        providerSignal = ProviderSignal.BINDING_STALE_CONFIRMED,
                        deliveryBindingRevision = revision,
                    ),
                )
            },
        )

        assertTrue(h.blocking { h.acquire(work, "playback").await() }.isSuccess)

        assertEquals(2, h.origin.executions(work.fetchKey))
        assertEquals(2, h.origin.executions.size)
        assertEquals(1, h.charges(RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH).size)
        h.assertSameWork(work)
    }

    @Test
    fun staleWithoutBindingCoordinatorFailsClosed() {
        val h = harness()
        val work = RecoveryHarness.work("stale-unbound")
        h.origin.script(
            work.fetchKey,
            h.origin.http(403, signal = ProviderSignal.BINDING_STALE_CONFIRMED),
        )

        val outcome = h.blocking { h.acquire(work, "playback").await() }

        assertNull(h.bindings)
        assertEquals(RecoveryTerminalReason.TERMINAL_FAILURE, outcome.terminalReason)
        assertEquals(RecoveryActionKind.FAIL_CLOSED_ACTION_UNAVAILABLE, outcome.action)
        assertNull(outcome.deliveryBindingResult)
        assertEquals(1, h.origin.executions(work.fetchKey))
        val failure = h.failures.single()
        assertEquals(RecoveryDecisionKind.REFRESH_DELIVERY_BINDING, failure.decision.kind)
        assertEquals(RecoveryActionKind.FAIL_CLOSED_ACTION_UNAVAILABLE, failure.action.kind)
        assertNull(failure.action.deliveryBinding)
    }

    @Test
    fun alreadyAdvancedRevisionConsumesNoRefreshBudget() {
        val releaseRefresh = CompletableDeferred<Unit>()
        val refresher = TestRefresher(releaseRefresh)
        refresher.enqueue(TestRefresher.material(2))
        val h = harness(refresher)
        val workA = RecoveryHarness.work("stale-a")
        val workB = RecoveryHarness.work("stale-b")
        h.origin.script(
            workA.fetchKey,
            h.origin.http(403, signal = ProviderSignal.BINDING_STALE_CONFIRMED),
        )
        val bEntered = CompletableDeferred<Unit>()
        val bRelease = CompletableDeferred<Unit>()
        h.origin.scriptBound(
            workB.fetchKey,
            { _, _, _, revision ->
                bEntered.complete(Unit)
                bRelease.await()
                FetchAttemptDisposition.Failure(
                    FailureObservation.HttpResponse(
                        statusCode = 403,
                        providerSignal = ProviderSignal.BINDING_STALE_CONFIRMED,
                        deliveryBindingRevision = revision,
                    ),
                )
            },
        )

        val handleA = h.blocking { h.acquire(workA, "playback-a") }
        h.awaitCondition {
            h.bindingEvents.any { it.kind == DeliveryBindingEventKind.REFRESH_STARTED }
        }
        val handleB = h.blocking { h.acquire(workB, "playback-b") }
        h.blocking { bEntered.await() }
        // A's refresh completes while B still holds its binding-1 failure.
        releaseRefresh.complete(Unit)
        h.awaitCondition {
            h.bindingEvents.any { it.kind == DeliveryBindingEventKind.REFRESH_SUCCEEDED }
        }
        bRelease.complete(Unit)

        val outcomeA = h.blocking { handleA.await() }
        val outcomeB = h.blocking { handleB.await() }

        assertTrue(outcomeA.isSuccess, "A=$outcomeA")
        assertTrue(outcomeB.isSuccess, "B=$outcomeB")
        assertEquals(listOf("refresh-1"), refresher.calls.toList())
        assertEquals(BINDING_2, h.bindings!!.current().revision)
        assertEquals(
            listOf(BINDING_1, BINDING_1),
            h.origin.bindingRevisions.take(2),
        )
        assertEquals(
            listOf(BINDING_2, BINDING_2),
            h.origin.bindingRevisions.drop(2),
        )

        val failureA = h.failures.single { it.recoveryChainId == outcomeA.recoveryChainId }
        val recordA = checkNotNull(failureA.action.deliveryBinding)
        assertEquals(DeliveryBindingActionResult.REFRESHED, recordA.result)
        assertTrue(recordA.charged)
        val failureB = h.failures.single { it.recoveryChainId == outcomeB.recoveryChainId }
        val recordB = checkNotNull(failureB.action.deliveryBinding)
        assertEquals(DeliveryBindingActionResult.ALREADY_ADVANCED, recordB.result)
        assertEquals(BINDING_1, recordB.expectedRevision)
        assertEquals(BINDING_2, recordB.currentRevision)
        assertNull(recordB.refreshCorrelationId)
        assertFalse(recordB.charged)
        assertTrue(
            h.budgetEvents
                .filter { it.recoveryChainId == outcomeB.recoveryChainId }
                .all { event ->
                    event.charge?.dimension != RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH
                },
        )
        assertEquals(
            0,
            h.charges(RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH)
                .count { it.recoveryChainId == outcomeB.recoveryChainId },
        )
    }

    @Test
    fun exhaustedRefreshBudgetMayUseAlreadyAdvancedRevisionForFree() {
        val refresher = TestRefresher()
        refresher.enqueue(
            TestRefresher.material(2),
            TestRefresher.material(3),
        )
        val h = harness(refresher)
        val work = RecoveryHarness.work("exhausted-already-advanced")
        val secondOwnerEntered = CompletableDeferred<Unit>()
        val releaseSecondOwner = CompletableDeferred<Unit>()

        h.origin.script(
            work.fetchKey,
            h.origin.http(403, signal = ProviderSignal.BINDING_STALE_CONFIRMED),
        )
        h.origin.scriptBound(
            work.fetchKey,
            { _, _, _, revision ->
                secondOwnerEntered.complete(Unit)
                releaseSecondOwner.await()
                FetchAttemptDisposition.Failure(
                    FailureObservation.HttpResponse(
                        statusCode = 403,
                        providerSignal = ProviderSignal.BINDING_STALE_CONFIRMED,
                        deliveryBindingRevision = revision,
                    ),
                )
            },
        )

        val handle = h.blocking { h.acquire(work, "playback") }
        h.blocking { secondOwnerEntered.await() }

        val external = h.blocking {
            h.bindings!!.refresh(
                expected = BINDING_2,
                caller = DeliveryBindingCaller(
                    recoveryChainId = "recovery-999",
                    failureId = null,
                    fetchKey = "fixture:external",
                    extentId = "external:extent",
                ),
                admission = DeliveryBindingRefreshAdmission { },
            )
        }
        assertEquals(BINDING_3, h.bindings!!.current().revision)
        assertEquals(
            io.github.definitelystable.spongetube.core.engine.delivery
                .DeliveryBindingRefreshResultKind.REFRESHED,
            external.kind,
        )

        releaseSecondOwner.complete(Unit)
        val outcome = h.blocking { handle.await() }

        assertTrue(outcome.isSuccess, "outcome=$outcome failures=${h.failures}")
        assertEquals(
            listOf(BINDING_1, BINDING_2, BINDING_3),
            h.origin.bindingRevisions.toList(),
        )
        assertEquals(
            1,
            h.charges(RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH)
                .count { it.recoveryChainId == outcome.recoveryChainId },
        )
        val ownFailures = h.failures.filter { it.recoveryChainId == outcome.recoveryChainId }
        assertEquals(2, ownFailures.size)
        assertEquals(0, ownFailures.last().context.deliveryBindingRefreshesRemaining)
        val record = checkNotNull(ownFailures.last().action.deliveryBinding)
        assertEquals(DeliveryBindingActionResult.ALREADY_ADVANCED, record.result)
        assertEquals(BINDING_2, record.expectedRevision)
        assertEquals(BINDING_3, record.currentRevision)
        assertFalse(record.charged)
    }

    @Test
    fun exhaustedRefreshBudgetMayJoinExistingRefreshForFree() = runBlocking {
        val refreshCall = AtomicInteger()
        val secondRefreshEntered = CompletableDeferred<Unit>()
        val releaseSecondRefresh = CompletableDeferred<Unit>()
        val refresher = DeliveryBindingRefresher { _, _ ->
            when (refreshCall.incrementAndGet()) {
                1 -> TestRefresher.material(2)
                2 -> {
                    secondRefreshEntered.complete(Unit)
                    releaseSecondRefresh.await()
                    TestRefresher.material(3)
                }
                else -> DeliveryMaterialRefresh.Failed
            }
        }
        val h = harness(refresher)
        val work = RecoveryHarness.work("exhausted-join")
        val secondOwnerEntered = CompletableDeferred<Unit>()
        val releaseSecondOwner = CompletableDeferred<Unit>()

        h.origin.script(
            work.fetchKey,
            h.origin.http(403, signal = ProviderSignal.BINDING_STALE_CONFIRMED),
        )
        h.origin.scriptBound(
            work.fetchKey,
            { _, _, _, revision ->
                secondOwnerEntered.complete(Unit)
                releaseSecondOwner.await()
                FetchAttemptDisposition.Failure(
                    FailureObservation.HttpResponse(
                        statusCode = 403,
                        providerSignal = ProviderSignal.BINDING_STALE_CONFIRMED,
                        deliveryBindingRevision = revision,
                    ),
                )
            },
        )

        val handle = h.acquire(work, "playback")
        secondOwnerEntered.await()

        val external = async {
            h.bindings!!.refresh(
                expected = BINDING_2,
                caller = DeliveryBindingCaller(
                    recoveryChainId = "recovery-999",
                    failureId = null,
                    fetchKey = "fixture:external",
                    extentId = "external:extent",
                ),
                admission = DeliveryBindingRefreshAdmission { },
            )
        }
        secondRefreshEntered.await()

        releaseSecondOwner.complete(Unit)
        h.awaitCondition {
            h.bindingEvents.any {
                it.kind == DeliveryBindingEventKind.REFRESH_JOINED &&
                    it.recoveryChainId == handle.recoveryChainId.value
            }
        }
        releaseSecondRefresh.complete(Unit)
        external.await()
        val outcome = handle.await()

        assertTrue(outcome.isSuccess, "outcome=$outcome failures=${h.failures}")
        assertEquals(
            listOf(BINDING_1, BINDING_2, BINDING_3),
            h.origin.bindingRevisions.toList(),
        )
        assertEquals(
            1,
            h.charges(RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH)
                .count { it.recoveryChainId == outcome.recoveryChainId },
        )
        val ownFailures = h.failures.filter { it.recoveryChainId == outcome.recoveryChainId }
        assertEquals(2, ownFailures.size)
        assertEquals(0, ownFailures.last().context.deliveryBindingRefreshesRemaining)
        val record = checkNotNull(ownFailures.last().action.deliveryBinding)
        assertEquals(DeliveryBindingActionResult.JOINED_REFRESH, record.result)
        assertEquals(BINDING_2, record.expectedRevision)
        assertEquals(BINDING_3, record.currentRevision)
        assertFalse(record.charged)
        assertEquals(2, refreshCall.get())
    }

    @Test
    fun concurrentRefreshIsSingleFlight() {
        val releaseRefresh = CompletableDeferred<Unit>()
        val refresher = TestRefresher(releaseRefresh)
        refresher.enqueue(TestRefresher.material(2))
        val h = harness(refresher)
        val workA = RecoveryHarness.work("flight-a")
        val workB = RecoveryHarness.work("flight-b")
        h.origin.script(
            workA.fetchKey,
            h.origin.http(403, signal = ProviderSignal.BINDING_STALE_CONFIRMED),
        )
        h.origin.script(
            workB.fetchKey,
            h.origin.http(403, signal = ProviderSignal.BINDING_STALE_CONFIRMED),
        )

        val handleA = h.blocking { h.acquire(workA, "playback-a") }
        h.awaitCondition {
            h.bindingEvents.any { it.kind == DeliveryBindingEventKind.REFRESH_STARTED }
        }
        val handleB = h.blocking { h.acquire(workB, "playback-b") }
        h.awaitCondition {
            h.bindingEvents.any { it.kind == DeliveryBindingEventKind.REFRESH_JOINED }
        }
        releaseRefresh.complete(Unit)

        val outcomeA = h.blocking { handleA.await() }
        val outcomeB = h.blocking { handleB.await() }

        assertTrue(outcomeA.isSuccess, "A=$outcomeA")
        assertTrue(outcomeB.isSuccess, "B=$outcomeB")
        assertEquals(listOf("refresh-1"), refresher.calls.toList())
        assertEquals(1, h.bindings!!.refreshOperationCountForTest())
        assertEquals(
            1,
            h.bindingEvents.count { it.kind == DeliveryBindingEventKind.REFRESH_STARTED },
        )
        val joined = h.bindingEvents.single {
            it.kind == DeliveryBindingEventKind.REFRESH_JOINED
        }
        val started = h.bindingEvents.single {
            it.kind == DeliveryBindingEventKind.REFRESH_STARTED
        }
        assertEquals("refresh-1", started.refreshCorrelationId)
        assertEquals(started.refreshCorrelationId, joined.refreshCorrelationId)
        assertEquals(
            1,
            h.bindingEvents.count { it.kind == DeliveryBindingEventKind.REFRESH_SUCCEEDED },
        )

        val recordA = checkNotNull(
            h.failures.single { it.recoveryChainId == outcomeA.recoveryChainId }
                .action.deliveryBinding,
        )
        assertEquals(DeliveryBindingActionResult.REFRESHED, recordA.result)
        assertTrue(recordA.charged)
        val recordB = checkNotNull(
            h.failures.single { it.recoveryChainId == outcomeB.recoveryChainId }
                .action.deliveryBinding,
        )
        assertEquals(DeliveryBindingActionResult.JOINED_REFRESH, recordB.result)
        assertEquals(BINDING_2, recordB.currentRevision)
        assertFalse(recordB.charged)
        assertEquals(1, h.charges(RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH).size)
        assertEquals(
            outcomeA.recoveryChainId,
            h.charges(RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH).single().recoveryChainId,
        )
        assertEquals(BINDING_2, h.bindings.current().revision)
        assertEquals(
            listOf(BINDING_2, BINDING_2),
            h.origin.bindingRevisions.drop(2),
        )
    }

    @Test
    fun incompatibleRefreshFailsClosed() {
        val refresher = TestRefresher()
        refresher.enqueue(DeliveryMaterialRefresh.Incompatible)
        val h = harness(refresher)
        val work = RecoveryHarness.work("refresh-incompatible")
        h.origin.script(
            work.fetchKey,
            h.origin.http(403, signal = ProviderSignal.BINDING_STALE_CONFIRMED),
        )

        val outcome = h.blocking { h.acquire(work, "playback").await() }

        assertEquals(RecoveryTerminalReason.TERMINAL_FAILURE, outcome.terminalReason)
        assertEquals(1, h.origin.executions(work.fetchKey))
        assertEquals(BINDING_1, h.bindings!!.current().revision)
        assertEquals(listOf("refresh-1"), refresher.calls.toList())
        val record = checkNotNull(h.failures.single().action.deliveryBinding)
        assertEquals(DeliveryBindingActionResult.INCOMPATIBLE, record.result)
        assertEquals(BINDING_1, record.expectedRevision)
        assertNull(record.currentRevision)
        assertEquals("refresh-1", record.refreshCorrelationId)
        assertTrue(record.charged)
        assertEquals(DeliveryBindingActionResult.INCOMPATIBLE, outcome.deliveryBindingResult)
        assertEquals(
            1,
            h.bindingEvents.count { it.kind == DeliveryBindingEventKind.REFRESH_INCOMPATIBLE },
        )
        h.assertSameWork(work)
    }

    @Test
    fun failedRefreshDoesNotStartAnotherOwner() {
        val refresher = TestRefresher()
        refresher.enqueue(DeliveryMaterialRefresh.Failed)
        val h = harness(refresher)
        val work = RecoveryHarness.work("refresh-failed")
        h.origin.script(
            work.fetchKey,
            h.origin.http(403, signal = ProviderSignal.BINDING_STALE_CONFIRMED),
        )

        val outcome = h.blocking { h.acquire(work, "playback").await() }

        assertEquals(RecoveryTerminalReason.TERMINAL_FAILURE, outcome.terminalReason)
        assertEquals(1, h.origin.executions(work.fetchKey))
        assertEquals(
            1,
            h.bindingEvents.count { it.kind == DeliveryBindingEventKind.REFRESH_FAILED },
        )
        assertEquals(1, h.charges(RecoveryBudgetDimension.REMOTE_ATTEMPT).size)
        val record = checkNotNull(h.failures.single().action.deliveryBinding)
        assertEquals(DeliveryBindingActionResult.FAILED, record.result)
        assertTrue(record.charged)
        assertEquals(DeliveryBindingActionResult.FAILED, outcome.deliveryBindingResult)
        assertEquals(0, h.coordinator.activeChainCountForTest())
        assertEquals(1, refresher.calls.size)
        h.assertSameWork(work)
    }

    @Test
    fun refresherIsCalledOncePerRefresh() {
        val refresher = TestRefresher()
        refresher.enqueue(TestRefresher.material(2))
        val h = harness(refresher)
        val work = RecoveryHarness.work("refresh-once")
        h.origin.script(
            work.fetchKey,
            h.origin.http(403, signal = ProviderSignal.BINDING_STALE_CONFIRMED),
        )

        assertTrue(h.blocking { h.acquire(work, "playback").await() }.isSuccess)

        // One executed refresh, one operation, one charge: no hidden retry.
        assertEquals(listOf("refresh-1"), refresher.calls.toList())
        assertEquals(1, h.bindings!!.refreshOperationCountForTest())
        assertEquals(
            1,
            h.bindingEvents.count { it.kind == DeliveryBindingEventKind.REFRESH_REQUESTED },
        )
        assertEquals(
            1,
            h.bindingEvents.count { it.kind == DeliveryBindingEventKind.REFRESH_STARTED },
        )
        assertEquals(1, h.charges(RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH).size)
        h.assertSameWork(work)
    }

    @Test
    fun cancellationDuringProviderWaitStopsChain() {
        val entered = CompletableDeferred<Unit>()
        val h = harness(
            TestRefresher(),
            sleeper = RecoverySleeper {
                entered.complete(Unit)
                awaitCancellation()
            },
        )
        val work = RecoveryHarness.work("wait-cancel")
        h.origin.script(work.fetchKey, h.origin.http(429, delaySeconds(2)))

        val handle = h.blocking { h.acquire(work, "playback") }
        h.blocking { entered.await() }
        handle.close()
        h.awaitCondition { h.budget(RecoveryBudgetEventKind.CHAIN_TERMINATED).isNotEmpty() }

        val terminal = h.budget(RecoveryBudgetEventKind.CHAIN_TERMINATED).single()
        assertEquals(RecoveryTerminalReason.NO_REMAINING_DEMAND, terminal.terminalReason)
        assertEquals(1, h.origin.executions(work.fetchKey))
        assertEquals(1, h.failures.size)
        assertEquals(RecoveryActionKind.WAIT_PROVIDER, h.failures.single().action.kind)
        assertEquals(1, h.charges(RecoveryBudgetDimension.REMOTE_ATTEMPT).size)
        h.assertSameWork(work)
    }

    @Test
    fun shutdownCancelsProviderWait() {
        val entered = CompletableDeferred<Unit>()
        val h = harness(
            TestRefresher(),
            sleeper = RecoverySleeper {
                entered.complete(Unit)
                awaitCancellation()
            },
        )
        val work = RecoveryHarness.work("wait-shutdown")
        h.origin.script(work.fetchKey, h.origin.http(429, delaySeconds(2)))
        val handle = h.blocking { h.acquire(work, "playback") }
        h.blocking { entered.await() }

        h.blocking { h.coordinator.shutdown() }

        assertEquals(
            RecoveryTerminalReason.SESSION_TERMINATION,
            h.blocking { handle.await() }.terminalReason,
        )
        assertEquals(1, h.origin.executions(work.fetchKey))
        assertEquals(0, h.coordinator.activeChainCountForTest())
        h.assertSameWork(work)
    }

    @Test
    fun shutdownSettlesRefresh() {
        val refresher = TestRefresher(CompletableDeferred())
        val h = harness(refresher)
        val work = RecoveryHarness.work("refresh-shutdown")
        h.origin.script(
            work.fetchKey,
            h.origin.http(403, signal = ProviderSignal.BINDING_STALE_CONFIRMED),
        )
        val handle = h.blocking { h.acquire(work, "playback") }
        h.awaitCondition {
            h.bindingEvents.any { it.kind == DeliveryBindingEventKind.REFRESH_STARTED }
        }

        h.blocking { h.coordinator.shutdown() }

        assertEquals(
            RecoveryTerminalReason.SESSION_TERMINATION,
            h.blocking { handle.await() }.terminalReason,
        )
        assertEquals(1, h.origin.executions(work.fetchKey))
        assertEquals(
            1,
            h.bindingEvents.count { it.kind == DeliveryBindingEventKind.REFRESH_CANCELLED },
        )
        assertEquals(0, h.coordinator.activeChainCountForTest())
        h.assertSameWork(work)
    }

    @Test
    fun nextOwnerAlwaysSelectsCurrentBinding() {
        val refresher = TestRefresher()
        refresher.enqueue(TestRefresher.material(2))
        val h = harness(refresher)
        val work = RecoveryHarness.work("binding-lineage")
        h.origin.script(
            work.fetchKey,
            h.origin.http(403, signal = ProviderSignal.BINDING_STALE_CONFIRMED),
        )

        assertTrue(h.blocking { h.acquire(work, "playback").await() }.isSuccess)

        val charges = h.charges(RecoveryBudgetDimension.REMOTE_ATTEMPT)
        assertEquals(
            listOf("fetch-1:attempt-1", "fetch-2:attempt-1"),
            charges.map { it.attemptCorrelationId },
        )
        val selections = h.bindingEvents.filter {
            it.kind == DeliveryBindingEventKind.BINDING_SELECTED_FOR_ATTEMPT
        }
        assertEquals(2, selections.size)
        assertEquals(
            charges.map { it.attemptCorrelationId },
            selections.map { it.attemptCorrelationId },
        )
        assertEquals(listOf(BINDING_1, BINDING_2), selections.map { it.currentRevision })
        assertEquals(
            listOf(null, "recovery-1:failure-1"),
            selections.map { it.failureId },
        )
        assertEquals(
            h.failures.single().failureId,
            selections.last().failureId,
        )
    }

    @Test
    fun noValidPersistedExtentIsRemoved() {
        val refresher = TestRefresher()
        refresher.enqueue(TestRefresher.material(2))
        val h = harness(refresher)
        val other = RecoveryHarness.work("persisted-other")
        h.publisher.seed(other.extentSpec)
        val work = RecoveryHarness.work("persisted-work")
        h.origin.script(
            work.fetchKey,
            h.origin.http(429, delaySeconds(1)),
            h.origin.http(403, signal = ProviderSignal.BINDING_STALE_CONFIRMED),
        )

        assertTrue(h.blocking { h.acquire(work, "playback").await() }.isSuccess)

        assertTrue(h.publisher.contains(other.extentSpec.extentId))
        assertTrue(h.publisher.contains(work.extentSpec.extentId))
        assertEquals(
            listOf(
                RecoveryActionKind.WAIT_PROVIDER,
                RecoveryActionKind.REFRESH_DELIVERY_BINDING,
            ),
            h.failures.map { it.action.kind },
        )
    }
}

private val BINDING_1 = DeliveryBindingRevision("binding-1")
private val BINDING_2 = DeliveryBindingRevision("binding-2")
private val BINDING_3 = DeliveryBindingRevision("binding-3")
