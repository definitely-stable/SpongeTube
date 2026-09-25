package io.github.definitelystable.spongetube.core.engine.recovery

import com.sun.net.httpserver.HttpServer
import io.github.definitelystable.spongetube.core.engine.FetchBroker
import io.github.definitelystable.spongetube.core.engine.FetchEvent
import io.github.definitelystable.spongetube.core.engine.FetchEventListener
import io.github.definitelystable.spongetube.core.engine.FetchEventKind
import io.github.definitelystable.spongetube.core.engine.FetchIdentityConflictException
import io.github.definitelystable.spongetube.core.engine.FetchPriority
import io.github.definitelystable.spongetube.core.engine.HttpRangeFetchExecutor
import io.github.definitelystable.spongetube.core.engine.HttpRangeTarget
import io.github.definitelystable.spongetube.core.storage.ExtentId
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows

/**
 * M2-C RecoveryCoordinator behavior (C-01..C-10, C-15, C-16, C-20, the
 * single-owner physical request bound and the full failure path).
 * No real sleeps: backoff uses [RecordingSleeper] and deterministic jitter.
 */
@Timeout(30)
class RecoveryCoordinatorTest {
    private val harnesses = mutableListOf<RecoveryHarness>()

    @AfterEach
    fun tearDown() {
        harnesses.forEach(RecoveryHarness::shutdown)
    }

    private fun harness(
        gate: RecoveryAttemptGate = RecoveryAttemptGate.ALWAYS_PERMIT,
        sleeper: RecoverySleeper = RecordingSleeper(),
        policy: RecoveryPolicy = TEST_POLICY,
    ): RecoveryHarness =
        RecoveryHarness(policy = policy, gate = gate, sleeper = sleeper).also(harnesses::add)

    // C-01
    @Test
    fun transientFailureRetriesThenSucceedsInOneChain() {
        val h = harness()
        val work = RecoveryHarness.work("c01")
        h.origin.script(work.fetchKey, ScriptedOrigin.TIMEOUT)

        val outcome = h.blocking { h.acquire(work, "playback").await() }

        assertEquals(RecoveryTerminalReason.SUCCESS, outcome.terminalReason)
        assertEquals(1, h.budget(RecoveryBudgetEventKind.CHAIN_STARTED).size)
        assertEquals(
            listOf("fetch-1", "fetch-2"),
            h.budget(RecoveryBudgetEventKind.OWNER_STARTED).map { it.fetchId },
        )
        assertEquals(2, h.budget(RecoveryBudgetEventKind.CHARGE).size)
        assertEquals(2, h.origin.executions(work.fetchKey))
        assertEquals(listOf(100L), (h.sleeper as RecordingSleeper).delays)
    }

    // C-02
    @Test
    fun fourTransientFailuresExhaustTheBudgetWithoutAFifthAttempt() {
        val h = harness()
        val work = RecoveryHarness.work("c02")
        h.origin.script(work.fetchKey, *Array(5) { ScriptedOrigin.TIMEOUT })

        val outcome = h.blocking { h.acquire(work, "playback").await() }

        assertEquals(RecoveryTerminalReason.BUDGET_EXHAUSTED, outcome.terminalReason)
        assertEquals(4, h.origin.executions(work.fetchKey))
        val charges = h.budget(RecoveryBudgetEventKind.CHARGE).map { checkNotNull(it.charge) }
        assertEquals(listOf(0, 1, 2, 3), charges.map { it.spentBefore })
        assertEquals(listOf(1, 2, 3, 4), charges.map { it.spentAfter })
        assertTrue(charges.all { it.limit == 4 })
        assertEquals(
            RecoveryActionKind.TERMINATE_BUDGET_EXHAUSTED,
            h.failures.last().action.kind,
        )
        assertEquals(RecoveryDecisionKind.RETRY_AFTER_BACKOFF, h.failures.last().decision.kind)
        assertEquals(listOf(100L, 200L, 400L), (h.sleeper as RecordingSleeper).delays)
    }

    // C-03
    @Test
    fun playbackJoinsReserveChainAndRaisesPriorityWithoutNewBudget() {
        val h = harness()
        val work = RecoveryHarness.work("c03")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val seen = mutableListOf<FetchPriority>()
        h.origin.script(work.fetchKey, { request, priority, emit ->
            seen += priority.value
            entered.complete(Unit)
            release.await()
            seen += priority.value
            ScriptedOrigin.serve(request, emit)
        })

        val reserve = h.blocking { h.acquire(work, "reserve", RecoveryConsumerKind.RESERVE) }
        h.blocking { entered.await() }
        val playback = h.blocking { h.acquire(work, "playback") }
        release.complete(Unit)

        assertEquals(RecoveryAcquireDisposition.NEW_CHAIN, reserve.acquireDisposition)
        assertEquals(RecoveryAcquireDisposition.JOINED_ACTIVE, playback.acquireDisposition)
        assertEquals(reserve.recoveryChainId, playback.recoveryChainId)
        assertEquals(reserve.fetchIdAtAcquire, playback.fetchIdAtAcquire)
        assertTrue(h.blocking { playback.await() }.isSuccess)
        assertTrue(h.blocking { reserve.await() }.isSuccess)
        assertEquals(listOf(FetchPriority.RESERVE, FetchPriority.PLAYBACK), seen)
        assertEquals(1, h.origin.executions(work.fetchKey))
        assertEquals(1, h.fetchEvents.count { it.event == FetchEventKind.OWNER_REGISTERED })
        assertEquals(1, h.fetchEvents.count { it.event == FetchEventKind.PRIORITY_RAISED })
        val raised = h.budget(RecoveryBudgetEventKind.PRIORITY_RAISED).single()
        assertEquals("RESERVE", raised.priorityBefore)
        assertEquals("PLAYBACK", raised.effectivePriority)
        // M2-ACC-06: escalation does not change the ledger.
        assertEquals(1, raised.spent.getValue(RecoveryBudgetDimension.REMOTE_ATTEMPT))
        assertEquals(1, h.budget(RecoveryBudgetEventKind.CHARGE).size)
    }

    // C-04
    @Test
    fun oneConsumerLeavingDoesNotCancelTheSharedAttempt() {
        val h = harness()
        val work = RecoveryHarness.work("c04")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        h.origin.script(work.fetchKey, { request, _, emit ->
            entered.complete(Unit)
            release.await()
            ScriptedOrigin.serve(request, emit)
        })

        val reserve = h.blocking { h.acquire(work, "reserve", RecoveryConsumerKind.RESERVE) }
        h.blocking { entered.await() }
        val playback = h.blocking { h.acquire(work, "playback") }
        reserve.close()
        release.complete(Unit)

        assertTrue(h.blocking { playback.await() }.isSuccess)
        assertEquals(1, h.origin.executions(work.fetchKey))
        assertEquals(0, h.fetchEvents.count { it.event == FetchEventKind.OWNER_CANCELLED })
    }

    // C-05
    @Test
    fun lastConsumerLeavingCancelsTheAttemptAndStartsNoOther() {
        val h = harness()
        val work = RecoveryHarness.work("c05")
        val entered = CompletableDeferred<Unit>()
        h.origin.script(work.fetchKey, { _, _, _ ->
            entered.complete(Unit)
            awaitCancellation()
        })

        val only = h.blocking { h.acquire(work, "playback") }
        h.blocking { entered.await() }
        only.close()
        h.awaitCondition { h.budget(RecoveryBudgetEventKind.CHAIN_TERMINATED).isNotEmpty() }

        val terminal = h.budget(RecoveryBudgetEventKind.CHAIN_TERMINATED).single()
        assertEquals(
            RecoveryTerminalReason.NO_REMAINING_DEMAND,
            terminal.terminalReason,
            "failures=${h.failures} fetch=${h.fetchEvents.map { it.event to it.outcome }}",
        )
        assertEquals(1, h.origin.executions(work.fetchKey))
        assertEquals(1, h.budget(RecoveryBudgetEventKind.CHARGE).size)
        assertEquals(RecoveryDecisionKind.COMPLETE_NO_DEMAND, h.failures.single().decision.kind)
        assertEquals(FailureClassification.CANCELLED, h.failures.single().classification)
        assertEquals(0, h.coordinator.activeChainCountForTest())
    }

    // C-06
    @Test
    fun lateDemandDuringCancellationBarrierJoinsTheSameChainAndLedger() {
        val h = harness()
        val work = RecoveryHarness.work("c06")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        h.origin.script(work.fetchKey, { _, _, _ ->
            entered.complete(Unit)
            // Slow cancellation: the physical owner is not terminal yet.
            withContext(NonCancellable) { release.await() }
            currentCoroutineContext().ensureActive()
            error("owner must observe its cancellation")
        })

        val first = h.blocking { h.acquire(work, "reserve", RecoveryConsumerKind.RESERVE) }
        h.blocking { entered.await() }
        first.close()
        h.awaitCondition {
            h.budget(RecoveryBudgetEventKind.CONSUMER_RELEASED).isNotEmpty()
        }
        val late = h.blocking { h.acquire(work, "playback") }
        assertEquals(RecoveryAcquireDisposition.JOINED_CANCELLING, late.acquireDisposition)
        assertEquals(first.recoveryChainId, late.recoveryChainId)
        release.complete(Unit)

        val outcome = h.blocking { late.await() }

        assertTrue(
            outcome.isSuccess,
            "outcome=$outcome failures=${h.failures} " +
                "fetch=${h.fetchEvents.map { Triple(it.fetchId, it.event, it.outcome) }}",
        )
        assertEquals(1, h.budget(RecoveryBudgetEventKind.CHAIN_STARTED).size)
        assertEquals(2, h.origin.executions(work.fetchKey))
        val owners = h.fetchEvents.filter { it.event == FetchEventKind.OWNER_REGISTERED }
        assertEquals(2, owners.size)
        // Owner 2 registers only after owner 1 is terminal.
        val firstTerminal = h.fetchEvents.first {
            it.fetchId == owners[0].fetchId && it.event == FetchEventKind.OWNER_CANCELLED
        }
        assertTrue(firstTerminal.eventSequence < owners[1].eventSequence)
        assertEquals(
            RecoveryDecisionKind.CONTINUE_FOR_DEMAND,
            h.failures.single().decision.kind,
        )
        assertEquals(
            listOf(1, 2),
            h.budget(RecoveryBudgetEventKind.CHARGE).map { checkNotNull(it.charge).spentAfter },
        )
    }

    // C-07
    @Test
    fun lateSuccessDuringCancellationCompletesTheChainWithoutRefetch() {
        val h = harness()
        val work = RecoveryHarness.work("c07")
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        h.origin.script(work.fetchKey, { request, _, emit ->
            entered.complete(Unit)
            // A blocking transfer that finishes after the cancel request has
            // no suspension point at which the cancellation could land.
            check(release.await(5, TimeUnit.SECONDS))
            ScriptedOrigin.serve(request, emit)
        })

        val only = h.blocking { h.acquire(work, "playback") }
        h.blocking { entered.await() }
        only.close()
        h.awaitCondition {
            h.fetchEvents.any { it.event == FetchEventKind.CONSUMER_RELEASED }
        }
        release.countDown()
        h.awaitCondition { h.budget(RecoveryBudgetEventKind.CHAIN_TERMINATED).isNotEmpty() }

        assertEquals(
            RecoveryTerminalReason.SUCCESS,
            h.budget(RecoveryBudgetEventKind.CHAIN_TERMINATED).single().terminalReason,
        )
        assertTrue(h.publisher.contains(work.extentSpec.extentId))
        assertEquals(1, h.origin.executions(work.fetchKey))
        assertTrue(h.failures.isEmpty())
    }

    // C-08
    @Test
    fun storageNoSpaceIsTerminalAfterExactlyOneAttempt() {
        assertSingleAttemptTerminal(
            FailureObservation.StorageFailure(StorageFailureKind.NO_SPACE),
            FailureClassification.STORAGE_FAILURE,
            RecoveryDecisionKind.FAIL_TERMINAL,
            RecoveryActionKind.TERMINATE_FAILURE,
        )
    }

    // C-09
    @Test
    fun rangeMismatchIsTerminalAfterExactlyOneAttempt() {
        assertSingleAttemptTerminal(
            FailureObservation.RangeProtocolFailure(RangeProtocolKind.CONTENT_RANGE_MISMATCH),
            FailureClassification.RANGE_REJECTED,
            RecoveryDecisionKind.FAIL_TERMINAL,
            RecoveryActionKind.TERMINATE_FAILURE,
        )
    }

    // C-10
    @Test
    fun integrityFailureFromPublicationIsTerminalAfterExactlyOneAttempt() {
        val h = harness()
        val work = RecoveryHarness.work("c10")
        h.publisher.integrityFailures += work.extentSpec.extentId

        val outcome = h.blocking { h.acquire(work, "playback").await() }

        assertEquals(RecoveryTerminalReason.TERMINAL_FAILURE, outcome.terminalReason)
        assertEquals(FailureClassification.CONTENT_INTEGRITY, outcome.classification)
        assertEquals(1, h.origin.executions(work.fetchKey))
        assertEquals(
            FailureObservation.ContentIntegrityFailure,
            h.failures.single().observation,
        )
    }

    @Test
    fun rateLimitDecidesProviderWaitButFailsClosedUntilM2D() {
        assertSingleAttemptTerminal(
            FailureObservation.HttpResponse(429),
            FailureClassification.PROVIDER_RATE_LIMITED,
            RecoveryDecisionKind.WAIT_UNTIL_PROVIDER,
            RecoveryActionKind.FAIL_CLOSED_ACTION_UNAVAILABLE,
        )
    }

    @Test
    fun bareForbiddenIsARejectionNotAStaleBinding() {
        assertSingleAttemptTerminal(
            FailureObservation.HttpResponse(403),
            FailureClassification.PROVIDER_REJECTED,
            RecoveryDecisionKind.FAIL_TERMINAL,
            RecoveryActionKind.TERMINATE_FAILURE,
        )
    }

    @Test
    fun staleDescriptorDecidesRefreshButFailsClosedUntilM2D() {
        assertSingleAttemptTerminal(
            FailureObservation.DeliveryDescriptorStale,
            FailureClassification.DELIVERY_BINDING_STALE,
            RecoveryDecisionKind.REFRESH_DELIVERY_BINDING,
            RecoveryActionKind.FAIL_CLOSED_ACTION_UNAVAILABLE,
        )
    }

    @Test
    fun unknownObservationFailsClosed() {
        assertSingleAttemptTerminal(
            FailureObservation.HttpResponse(302),
            FailureClassification.UNKNOWN,
            RecoveryDecisionKind.FAIL_TERMINAL,
            RecoveryActionKind.TERMINATE_FAILURE,
        )
    }

    @Test
    fun providerServerErrorIsRetriedAsGenericHttpBehavior() {
        val h = harness()
        val work = RecoveryHarness.work("http-503")
        h.origin.script(work.fetchKey, ScriptedOrigin.failWith(FailureObservation.HttpResponse(503)))

        val outcome = h.blocking { h.acquire(work, "playback").await() }

        assertTrue(outcome.isSuccess)
        assertEquals(
            FailureClassification.PROVIDER_TRANSIENT_RESPONSE,
            h.failures.single().classification,
        )
        assertEquals(2, h.origin.executions(work.fetchKey))
    }

    @Test
    fun storageConflictReconcilesLocallyWithoutANetworkRetry() {
        val h = harness()
        val work = RecoveryHarness.work("conflict")
        h.publisher.seed(work.extentSpec)

        val outcome = h.blocking { h.acquire(work, "playback").await() }

        assertTrue(outcome.isSuccess)
        assertTrue(outcome.reconciledLocally)
        assertEquals(1, h.origin.executions(work.fetchKey))
        val failure = h.failures.single()
        assertEquals(FailureClassification.PUBLICATION_CONFLICT, failure.classification)
        assertEquals(RecoveryDecisionKind.RECONCILE_LOCAL_COVERAGE, failure.decision.kind)
        assertEquals(RecoveryActionKind.LOCAL_COVERAGE_READY, failure.action.kind)
        assertEquals(LocalReconciliation.COVERAGE_PRESENT, failure.action.reconciliation)
    }

    // C-15
    @Test
    fun blockedGateSpendsNothingAndTheFirstPermitStartsTheFirstCharge() {
        val permit = CompletableDeferred<Unit>()
        val h = harness(
            gate = RecoveryAttemptGate {
                permit.await()
                RecoveryAttemptPermit(routeEpoch = 7, reason = RecoveryPermitReason("TEST_PERMIT"))
            },
        )
        val work = RecoveryHarness.work("c15")

        val handle = h.blocking { h.acquire(work, "playback") }

        assertNull(handle.fetchIdAtAcquire)
        assertEquals(0, h.origin.executions.size)
        assertTrue(h.budget(RecoveryBudgetEventKind.CHARGE).isEmpty())
        val waiting = h.budget(RecoveryBudgetEventKind.ATTEMPT_PERMIT_WAIT).single()
        assertEquals(0, waiting.spent.getValue(RecoveryBudgetDimension.REMOTE_ATTEMPT))

        permit.complete(Unit)
        assertTrue(h.blocking { handle.await() }.isSuccess)
        val granted = h.budget(RecoveryBudgetEventKind.ATTEMPT_PERMIT_GRANTED).single()
        assertEquals(7L, granted.permit?.routeEpoch)
        assertEquals(1, h.budget(RecoveryBudgetEventKind.CHARGE).size)
    }

    // C-16
    @Test
    fun gateBlockingBetweenAttemptsNeitherPollsNorSpends() {
        val calls = AtomicInteger()
        val secondPermit = CompletableDeferred<Unit>()
        val h = harness(
            gate = RecoveryAttemptGate {
                if (calls.incrementAndGet() >= 2) {
                    secondPermit.await()
                }
                RecoveryAttemptPermit(null, RecoveryPermitReason.ALWAYS_PERMIT)
            },
        )
        val work = RecoveryHarness.work("c16")
        h.origin.script(work.fetchKey, ScriptedOrigin.TIMEOUT)

        val handle = h.blocking { h.acquire(work, "playback") }
        h.awaitCondition { calls.get() == 2 }
        Thread.sleep(50)

        assertEquals(2, calls.get())
        assertEquals(1, h.origin.executions(work.fetchKey))
        assertEquals(1, h.budget(RecoveryBudgetEventKind.CHARGE).size)
        val lastWait = h.budget(RecoveryBudgetEventKind.ATTEMPT_PERMIT_WAIT).last()
        assertEquals(1, lastWait.spent.getValue(RecoveryBudgetDimension.REMOTE_ATTEMPT))

        secondPermit.complete(Unit)
        assertTrue(h.blocking { handle.await() }.isSuccess)
        assertEquals(2, h.budget(RecoveryBudgetEventKind.CHARGE).size)
        assertEquals(2, calls.get())
    }

    @Test
    fun lastConsumerLeavingDuringGateWaitEndsTheChainWithoutAnAttempt() {
        val h = harness(gate = RecoveryAttemptGate { awaitCancellation() })
        val work = RecoveryHarness.work("gate-release")

        val handle = h.blocking { h.acquire(work, "playback") }
        handle.close()
        h.awaitCondition { h.budget(RecoveryBudgetEventKind.CHAIN_TERMINATED).isNotEmpty() }

        assertEquals(
            RecoveryTerminalReason.NO_REMAINING_DEMAND,
            h.budget(RecoveryBudgetEventKind.CHAIN_TERMINATED).single().terminalReason,
        )
        assertEquals(0, h.origin.executions.size)
        assertTrue(h.budget(RecoveryBudgetEventKind.CHARGE).isEmpty())
    }

    // C-20
    @Test
    fun consumerCancellationDuringBackoffStartsNoNextAttempt() {
        val h = harness(sleeper = RecoverySleeper { awaitCancellation() })
        val work = RecoveryHarness.work("c20")
        h.origin.script(work.fetchKey, ScriptedOrigin.TIMEOUT)

        val handle = h.blocking { h.acquire(work, "playback") }
        h.awaitCondition { h.budget(RecoveryBudgetEventKind.BACKOFF_SCHEDULED).isNotEmpty() }
        handle.close()
        h.awaitCondition { h.budget(RecoveryBudgetEventKind.CHAIN_TERMINATED).isNotEmpty() }

        assertEquals(
            RecoveryTerminalReason.NO_REMAINING_DEMAND,
            h.budget(RecoveryBudgetEventKind.CHAIN_TERMINATED).single().terminalReason,
        )
        assertEquals(1, h.origin.executions(work.fetchKey))
        assertTrue(h.budget(RecoveryBudgetEventKind.BACKOFF_COMPLETED).isEmpty())
    }

    @Test
    fun lastConsumerLeavingAtAnyPointNeverFailsOrHangsTheChain() {
        val h = harness()
        repeat(150) { index ->
            val work = RecoveryHarness.work("race-$index")
            if (index % 2 == 0) {
                h.origin.script(work.fetchKey, ScriptedOrigin.TIMEOUT)
            }
            val handle = h.blocking { h.acquire(work, "playback-$index") }
            if (index % 3 == 0) {
                Thread.yield()
            }
            handle.close()
        }
        h.awaitCondition(10_000) { h.coordinator.activeChainCountForTest() == 0 }

        val terminals = h.budget(RecoveryBudgetEventKind.CHAIN_TERMINATED)
        assertEquals(150, terminals.size)
        assertTrue(
            terminals.all {
                it.terminalReason in setOf(
                    RecoveryTerminalReason.SUCCESS,
                    RecoveryTerminalReason.NO_REMAINING_DEMAND,
                )
            },
            terminals.groupingBy { it.terminalReason }.eachCount().toString(),
        )
        assertEquals(
            h.budget(RecoveryBudgetEventKind.CHARGE).size,
            h.fetchEvents.count { it.event == FetchEventKind.ATTEMPT_STARTED },
        )
        assertTrue(h.failures.none { it.classification == FailureClassification.INTERNAL })
    }

    // Item 86: timeout, timeout, success.
    @Test
    fun fullFailurePathKeepsOneChainAndOneMonotonicLedger() {
        val h = harness()
        val work = RecoveryHarness.work("full-path")
        h.origin.script(work.fetchKey, ScriptedOrigin.TIMEOUT, ScriptedOrigin.TIMEOUT)

        val outcome = h.blocking { h.acquire(work, "playback").await() }

        assertTrue(outcome.isSuccess)
        val chainIds = h.budgetEvents.map { it.recoveryChainId }.toSet()
        assertEquals(setOf(RecoveryChainId("recovery-1")), chainIds)
        assertEquals(
            listOf("fetch-1", "fetch-2", "fetch-3"),
            h.budget(RecoveryBudgetEventKind.OWNER_STARTED).map { it.fetchId },
        )
        assertEquals(
            listOf(0 to 1, 1 to 2, 2 to 3),
            h.budget(RecoveryBudgetEventKind.CHARGE)
                .map { checkNotNull(it.charge).let { c -> c.spentBefore to c.spentAfter } },
        )
        assertEquals(
            listOf(RecoveryActionKind.SCHEDULE_BACKOFF, RecoveryActionKind.SCHEDULE_BACKOFF),
            h.failures.map { it.action.kind },
        )
        assertEquals(
            RecoveryTerminalReason.SUCCESS,
            h.budget(RecoveryBudgetEventKind.CHAIN_TERMINATED).single().terminalReason,
        )
        // No event may carry a smaller ledger than an earlier one.
        val spent = h.budgetEvents.map { it.spent.getValue(RecoveryBudgetDimension.REMOTE_ATTEMPT) }
        assertEquals(spent.sorted(), spent)
    }

    // Item 85: 4 allowed chain attempts = exactly 4 physical origin requests.
    @Test
    fun fourAllowedAttemptsProduceExactlyFourPhysicalOriginRequests() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val work = RecoveryHarness.work("physical")
            val broker = FetchBroker(
                publisher = MemoryPublisher(),
                executor = HttpRangeFetchExecutor(
                    targetFor = {
                        HttpRangeTarget(
                            URL("http://127.0.0.1:${server.address.port}/fixture"),
                            work.extentSpec.expectedLength,
                        )
                    },
                    connectTimeoutMs = 5_000,
                    readTimeoutMs = 5_000,
                ),
                sessionId = "m2-c-physical",
                ownerScope = scope,
                ownsScope = false,
                monotonicClockNs = { 1L },
            )
            val coordinator = RecoveryCoordinator(
                broker = broker,
                sessionId = "m2-c-physical",
                policy = RecoveryPolicy.DEFAULT,
                jitter = RecoveryJitterSource { 0L },
                sleeper = RecordingSleeper(),
                scope = scope,
                ownsScope = false,
                clockNs = { 1L },
            )

            val outcome = runBlocking {
                coordinator.acquire(
                    work,
                    RecoveryConsumer(RecoveryConsumerId("playback"), RecoveryConsumerKind.PLAYBACK),
                ).await()
            }

            assertEquals(RecoveryTerminalReason.BUDGET_EXHAUSTED, outcome.terminalReason)
            assertEquals(RecoveryPolicy.DEFAULT_REMOTE_ATTEMPT_LIMIT, requests.get())
            runBlocking {
                coordinator.shutdown()
                broker.shutdown()
            }
        } finally {
            server.stop(0)
            scope.cancel()
        }
    }

    @Test
    fun transportPreflightFailureConsumesNoRemoteAttemptBudget() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fetchEvents = mutableListOf<FetchEvent>()
        val evidence = RecoveryEvidenceRecorder("m2-c-preflight-run", "m2-c-preflight")
        try {
            val work = RecoveryHarness.work("preflight-no-target")
            val broker = FetchBroker(
                publisher = MemoryPublisher(),
                executor = HttpRangeFetchExecutor(
                    targetFor = { null },
                    connectTimeoutMs = 5_000,
                    readTimeoutMs = 5_000,
                ),
                sessionId = "m2-c-preflight",
                eventListener = FetchEventListener { fetchEvents += it },
                ownerScope = scope,
                ownsScope = false,
                monotonicClockNs = { 1L },
            )
            val coordinator = RecoveryCoordinator(
                broker = broker,
                sessionId = "m2-c-preflight",
                evidence = evidence,
                scope = scope,
                ownsScope = false,
                clockNs = { 1L },
            )

            val outcome = runBlocking {
                coordinator.acquire(
                    work,
                    RecoveryConsumer(
                        RecoveryConsumerId("playback"),
                        RecoveryConsumerKind.PLAYBACK,
                    ),
                ).await()
            }

            assertEquals(RecoveryTerminalReason.TERMINAL_FAILURE, outcome.terminalReason)
            assertTrue(
                evidence.budgetEvents().none { it.kind == RecoveryBudgetEventKind.CHARGE },
            )
            assertTrue(fetchEvents.none { it.event == FetchEventKind.ATTEMPT_STARTED })
            val failure = evidence.failures().single()
            assertEquals(
                FailureObservation.TransportIo(TransportIoKind.TARGET_UNRESOLVED),
                failure.observation,
            )
            runBlocking {
                coordinator.shutdown()
                broker.shutdown()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun requestOutsideResourceConsumesNoRemoteAttemptBudget() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fetchEvents = mutableListOf<FetchEvent>()
        val evidence = RecoveryEvidenceRecorder("m2-c-range-preflight-run", "m2-c-range-preflight")
        try {
            val work = RecoveryHarness.work("preflight-range", length = 8)
            val broker = FetchBroker(
                publisher = MemoryPublisher(),
                executor = HttpRangeFetchExecutor(
                    targetFor = {
                        HttpRangeTarget(
                            URL("http://127.0.0.1:9/never-opened"),
                            resourceLength = 4,
                        )
                    },
                    connectTimeoutMs = 5_000,
                    readTimeoutMs = 5_000,
                    openConnection = { error("preflight must not open a connection") },
                ),
                sessionId = "m2-c-range-preflight",
                eventListener = FetchEventListener { fetchEvents += it },
                ownerScope = scope,
                ownsScope = false,
                monotonicClockNs = { 1L },
            )
            val coordinator = RecoveryCoordinator(
                broker = broker,
                sessionId = "m2-c-range-preflight",
                evidence = evidence,
                scope = scope,
                ownsScope = false,
                clockNs = { 1L },
            )

            val outcome = runBlocking {
                coordinator.acquire(
                    work,
                    RecoveryConsumer(
                        RecoveryConsumerId("playback"),
                        RecoveryConsumerKind.PLAYBACK,
                    ),
                ).await()
            }

            assertEquals(RecoveryTerminalReason.TERMINAL_FAILURE, outcome.terminalReason)
            assertTrue(
                evidence.budgetEvents().none { it.kind == RecoveryBudgetEventKind.CHARGE },
            )
            assertTrue(fetchEvents.none { it.event == FetchEventKind.ATTEMPT_STARTED })
            assertEquals(
                RangeProtocolKind.REQUEST_OUTSIDE_RESOURCE,
                (evidence.failures().single().observation as
                    FailureObservation.RangeProtocolFailure).kind,
            )
            runBlocking {
                coordinator.shutdown()
                broker.shutdown()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancelledDriverBeforeBodyStillCompletesTheChain() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val broker = FetchBroker(
            publisher = MemoryPublisher(),
            executor = ScriptedOrigin(),
            sessionId = "m2-c-cancelled-driver",
            ownerScope = scope,
            ownsScope = false,
        )
        scope.cancel()
        val coordinator = RecoveryCoordinator(
            broker = broker,
            sessionId = "m2-c-cancelled-driver",
            scope = scope,
            ownsScope = false,
        )

        val outcome = runBlocking {
            coordinator.acquire(
                RecoveryHarness.work("cancelled-driver"),
                RecoveryConsumer(
                    RecoveryConsumerId("playback"),
                    RecoveryConsumerKind.PLAYBACK,
                ),
            ).await()
        }

        assertEquals(RecoveryTerminalReason.TERMINAL_FAILURE, outcome.terminalReason)
        assertEquals(0, coordinator.activeChainCountForTest())
        runBlocking {
            coordinator.shutdown()
            broker.shutdown()
        }
    }

    @Test
    fun concurrentShutdownCallersAwaitTheSameCleanup() {
        val h = harness()
        val work = RecoveryHarness.work("shutdown-join")
        val entered = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        h.origin.script(work.fetchKey, { _, _, _ ->
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupEntered.complete(Unit)
                    releaseCleanup.await()
                }
            }
            error("cancelled owner must not resume")
        })

        val handle = h.blocking { h.acquire(work, "playback") }
        h.blocking { entered.await() }

        runBlocking {
            val first = async(Dispatchers.Default) { h.coordinator.shutdown() }
            cleanupEntered.await()
            val second = async(Dispatchers.Default) { h.coordinator.shutdown() }
            Thread.sleep(20)
            assertFalse(second.isCompleted)
            releaseCleanup.complete(Unit)
            first.await()
            second.await()
        }

        assertEquals(
            RecoveryTerminalReason.SESSION_TERMINATION,
            h.blocking { handle.await() }.terminalReason,
        )
    }

    @Test
    fun sameFetchKeyWithDifferentExtentSpecFailsClosed() {
        val h = harness()
        val work = RecoveryHarness.work("identity")
        val entered = CompletableDeferred<Unit>()
        h.origin.script(work.fetchKey, { _, _, _ ->
            entered.complete(Unit)
            awaitCancellation()
        })
        val first = h.blocking { h.acquire(work, "reserve", RecoveryConsumerKind.RESERVE) }
        h.blocking { entered.await() }
        val conflicting = work.copy(
            extentSpec = work.extentSpec.copy(
                extentId = ExtentId("m2c:other"),
            ),
        )

        assertThrows<FetchIdentityConflictException> {
            h.blocking { h.acquire(conflicting, "playback") }
        }
        assertThrows<FetchIdentityConflictException> {
            h.blocking { h.acquire(work, "reserve") }
        }
        assertEquals(1, h.budget(RecoveryBudgetEventKind.CHAIN_STARTED).size)
        assertEquals(1, h.coordinator.activeChainCountForTest())
        first.close()
    }

    @Test
    fun shutdownTerminatesEveryChainAndAdmitsNoNewDemand() {
        val h = harness(gate = RecoveryAttemptGate { awaitCancellation() })
        val waiting = RecoveryHarness.work("shutdown-waiting")
        val running = RecoveryHarness.work("shutdown-running")
        val entered = CompletableDeferred<Unit>()
        val hRunning = harness()
        hRunning.origin.script(running.fetchKey, { _, _, _ ->
            entered.complete(Unit)
            awaitCancellation()
        })

        val waitingHandle = h.blocking { h.acquire(waiting, "playback") }
        val runningHandle = hRunning.blocking { hRunning.acquire(running, "playback") }
        hRunning.blocking { entered.await() }
        h.shutdown()
        hRunning.shutdown()

        assertEquals(
            RecoveryTerminalReason.SESSION_TERMINATION,
            h.blocking { waitingHandle.await() }.terminalReason,
        )
        assertEquals(
            RecoveryTerminalReason.SESSION_TERMINATION,
            hRunning.blocking { runningHandle.await() }.terminalReason,
        )
        assertEquals(RecoveryDecisionKind.COMPLETE_SESSION, hRunning.failures.single().decision.kind)
        assertEquals(0, h.origin.executions.size)
        assertEquals(1, hRunning.origin.executions.size)
        assertThrows<IllegalStateException> {
            h.blocking { h.acquire(waiting, "late") }
        }
    }

    @Test
    fun terminalChainNeverSpendsAgainAndNewDemandStartsANewChain() {
        val h = harness()
        val work = RecoveryHarness.work("rechain")
        h.origin.script(work.fetchKey, ScriptedOrigin.failWith(FailureObservation.HttpResponse(403)))

        val first = h.blocking { h.acquire(work, "playback").await() }
        val second = h.blocking { h.acquire(work, "playback-2").await() }

        assertEquals(RecoveryTerminalReason.TERMINAL_FAILURE, first.terminalReason)
        assertTrue(second.isSuccess)
        assertFalse(first.recoveryChainId == second.recoveryChainId)
        val firstChainEvents = h.budgetEvents.filter { it.recoveryChainId == first.recoveryChainId }
        assertEquals(RecoveryBudgetEventKind.CHAIN_TERMINATED, firstChainEvents.last().kind)
    }

    private fun assertSingleAttemptTerminal(
        observation: FailureObservation,
        classification: FailureClassification,
        decision: RecoveryDecisionKind,
        action: RecoveryActionKind,
    ) {
        val h = harness()
        val work = RecoveryHarness.work("single-" + classification.name.lowercase())
        h.origin.script(work.fetchKey, ScriptedOrigin.failWith(observation))

        val outcome = h.blocking { h.acquire(work, "playback").await() }

        assertEquals(RecoveryTerminalReason.TERMINAL_FAILURE, outcome.terminalReason)
        assertEquals(1, h.origin.executions(work.fetchKey))
        assertEquals(1, h.budget(RecoveryBudgetEventKind.CHARGE).size)
        val failure = h.failures.single()
        assertEquals(observation, failure.observation)
        assertEquals(classification, failure.classification)
        assertEquals(decision, failure.decision.kind)
        assertEquals(action, failure.action.kind)
        assertTrue(h.budget(RecoveryBudgetEventKind.BACKOFF_SCHEDULED).isEmpty())
    }
}
