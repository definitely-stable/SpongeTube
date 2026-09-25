package io.github.definitelystable.spongetube.core.engine.recovery

import io.github.definitelystable.spongetube.core.engine.FetchAttemptDisposition
import io.github.definitelystable.spongetube.core.engine.FetchEvent
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows

/**
 * Deterministic `failure-decision-events-v1` / `recovery-budget-events-v1`
 * producer for the host oracle (`scripts/measurement/m2_recovery_oracle.py`,
 * run by `scripts/ci/verify-m2-c-recovery-evidence.sh host`).
 *
 * Each case drives the production RecoveryCoordinator, FetchBroker,
 * FailureClassifier and RecoveryPolicy over scripted physical attempts and
 * writes the three artifacts plus the scenario definition (`case.json`). The
 * oracle re-derives every classification, decision, action and ledger
 * transition independently; this test only produces and checks anchors.
 */
@Timeout(60)
class RecoveryEvidenceHostTest {
    @Test
    fun canonicalRecoveryCasesProduceEvidence() {
        File(EVIDENCE_ROOT).deleteRecursively()
        transientThenSuccess()
        budgetExhausted()
        reservePlaybackJoin()
        terminalClassifications()
        cancellationBarrier()
        attemptGateWait()
        finalConsumerCancellation()
    }

    @Test
    fun recorderRefusesToRenderOverflowedEvidence() {
        val recorder = RecoveryEvidenceRecorder("run", "session", capacity = 1)
        val h = RecoveryHarness()
        val work = RecoveryHarness.work("overflow")
        h.blocking { h.acquire(work, "playback").await() }
        h.budgetEvents.forEach(recorder::onBudgetEvent)
        h.shutdown()

        assertThrows<IllegalStateException> { recorder.budgetArtifact(TEST_POLICY.policyId) }
    }

    /** Item 86: timeout, timeout, success in one chain. */
    private fun transientThenSuccess() = case(
        "transient-then-success",
        expectedPhysicalAttempts = 3,
        expectedTerminals = mapOf("SUCCESS" to 1),
    ) { h ->
        val work = RecoveryHarness.work("transient")
        h.origin.script(work.fetchKey, ScriptedOrigin.TIMEOUT, ScriptedOrigin.TIMEOUT)
        assertTrue(h.blocking { h.acquire(work, "playback").await() }.isSuccess)
    }

    /** C-02: four transient failures, no fifth attempt. */
    private fun budgetExhausted() = case(
        "budget-exhausted",
        expectedPhysicalAttempts = 4,
        expectedTerminals = mapOf("BUDGET_EXHAUSTED" to 1),
    ) { h ->
        val work = RecoveryHarness.work("exhausted")
        h.origin.script(
            work.fetchKey,
            ScriptedOrigin.TIMEOUT,
            ScriptedOrigin.failWith(FailureObservation.HttpResponse(503)),
            ScriptedOrigin.failWith(FailureObservation.TransportIo(TransportIoKind.CONNECTION_RESET)),
            ScriptedOrigin.failWith(FailureObservation.TransportIo(TransportIoKind.PREMATURE_EOF)),
            ScriptedOrigin.TIMEOUT,
        )
        assertEquals(
            RecoveryTerminalReason.BUDGET_EXHAUSTED,
            h.blocking { h.acquire(work, "playback").await() }.terminalReason,
        )
    }

    /** C-03: reserve starts, playback joins, priority rises, budget unchanged. */
    private fun reservePlaybackJoin() = case(
        "reserve-playback-join",
        expectedPhysicalAttempts = 2,
        expectedTerminals = mapOf("SUCCESS" to 1),
    ) { h ->
        val work = RecoveryHarness.work("join")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        h.origin.script(
            work.fetchKey,
            { _, _, _ ->
                entered.complete(Unit)
                release.await()
                FetchAttemptDisposition.Failure(
                    FailureObservation.TransportIo(TransportIoKind.READ_TIMEOUT),
                )
            },
        )
        val reserve = h.blocking { h.acquire(work, "reserve", RecoveryConsumerKind.RESERVE) }
        h.blocking { entered.await() }
        val playback = h.blocking { h.acquire(work, "playback") }
        release.complete(Unit)
        assertTrue(h.blocking { playback.await() }.isSuccess)
        reserve.close()
    }

    /** One chain per anchored classification; each is terminal after one attempt. */
    private fun terminalClassifications() = case(
        "terminal-classifications",
        expectedPhysicalAttempts = 8,
        expectedTerminals = mapOf("TERMINAL_FAILURE" to 7, "SUCCESS" to 1),
    ) { h ->
        val scripted = listOf(
            "http-403" to FailureObservation.HttpResponse(403),
            "http-429" to FailureObservation.HttpResponse(429),
            "http-302" to FailureObservation.HttpResponse(302),
            "range" to FailureObservation.RangeProtocolFailure(
                RangeProtocolKind.FULL_BODY_FOR_RANGE_REQUEST,
            ),
            "stale" to FailureObservation.DeliveryDescriptorStale,
            "enospc" to FailureObservation.StorageFailure(StorageFailureKind.NO_SPACE),
        )
        scripted.forEach { (name, observation) ->
            val work = RecoveryHarness.work(name)
            h.origin.script(work.fetchKey, ScriptedOrigin.failWith(observation))
            assertEquals(
                RecoveryTerminalReason.TERMINAL_FAILURE,
                h.blocking { h.acquire(work, "playback-$name").await() }.terminalReason,
            )
        }
        val integrity = RecoveryHarness.work("integrity")
        h.publisher.integrityFailures += integrity.extentSpec.extentId
        assertEquals(
            RecoveryTerminalReason.TERMINAL_FAILURE,
            h.blocking { h.acquire(integrity, "playback-integrity").await() }.terminalReason,
        )
        val conflict = RecoveryHarness.work("conflict")
        h.publisher.seed(conflict.extentSpec)
        assertTrue(h.blocking { h.acquire(conflict, "playback-conflict").await() }.reconciledLocally)
    }

    /** C-06: late demand during the cancellation barrier keeps chain and ledger. */
    private fun cancellationBarrier() = case(
        "cancellation-barrier",
        expectedPhysicalAttempts = 2,
        expectedTerminals = mapOf("SUCCESS" to 1),
    ) { h ->
        val work = RecoveryHarness.work("barrier")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        h.origin.script(work.fetchKey, { _, _, _ ->
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            currentCoroutineContext().ensureActive()
            error("owner must observe its cancellation")
        })
        val first = h.blocking { h.acquire(work, "reserve", RecoveryConsumerKind.RESERVE) }
        h.blocking { entered.await() }
        first.close()
        h.awaitCondition { h.budget(RecoveryBudgetEventKind.CONSUMER_RELEASED).isNotEmpty() }
        val late = h.blocking { h.acquire(work, "playback") }
        release.complete(Unit)
        assertTrue(h.blocking { late.await() }.isSuccess)
    }

    /** C-16: the gate blocks between attempts; waiting spends nothing. */
    private fun attemptGateWait() {
        val calls = AtomicInteger()
        val second = CompletableDeferred<Unit>()
        case(
            "attempt-gate-wait",
            expectedPhysicalAttempts = 2,
            expectedTerminals = mapOf("SUCCESS" to 1),
            gate = RecoveryAttemptGate {
                val call = calls.incrementAndGet()
                if (call == 2) {
                    second.await()
                }
                RecoveryAttemptPermit(
                    routeEpoch = call.toLong(),
                    reason = RecoveryPermitReason("HARNESS_ROUTE_READY"),
                )
            },
        ) { h ->
            val work = RecoveryHarness.work("gate")
            h.origin.script(work.fetchKey, ScriptedOrigin.TIMEOUT)
            val handle = h.blocking { h.acquire(work, "playback") }
            h.awaitCondition { calls.get() == 2 }
            second.complete(Unit)
            assertTrue(h.blocking { handle.await() }.isSuccess)
        }
    }

    /** C-05: the last consumer leaves; the attempt is cancelled, none follows. */
    private fun finalConsumerCancellation() = case(
        "final-consumer-cancellation",
        expectedPhysicalAttempts = 1,
        expectedTerminals = mapOf("NO_REMAINING_DEMAND" to 1),
    ) { h ->
        val work = RecoveryHarness.work("cancel")
        val entered = CompletableDeferred<Unit>()
        h.origin.script(work.fetchKey, { _, _, _ ->
            entered.complete(Unit)
            awaitCancellation()
        })
        val handle = h.blocking { h.acquire(work, "playback") }
        h.blocking { entered.await() }
        handle.close()
        h.awaitCondition { h.budget(RecoveryBudgetEventKind.CHAIN_TERMINATED).isNotEmpty() }
    }

    private fun case(
        caseId: String,
        expectedPhysicalAttempts: Int,
        expectedTerminals: Map<String, Int>,
        gate: RecoveryAttemptGate = RecoveryAttemptGate.ALWAYS_PERMIT,
        script: (RecoveryHarness) -> Unit,
    ) {
        val h = RecoveryHarness(
            policy = TEST_POLICY,
            gate = gate,
            sessionId = "m2-c-host-$caseId",
        )
        try {
            script(h)
            h.awaitCondition { h.coordinator.activeChainCountForTest() == 0 }
        } finally {
            h.shutdown()
        }
        assertEquals(expectedPhysicalAttempts, h.origin.executions.size, caseId)

        val root = File(EVIDENCE_ROOT, caseId).apply { mkdirs() }
        File(root, "failure-decision-events.json")
            .writeText(recoveryJson(h.evidence.failureArtifact(TEST_POLICY.policyId)) + "\n")
        File(root, "recovery-budget-events.json")
            .writeText(recoveryJson(h.evidence.budgetArtifact(TEST_POLICY.policyId)) + "\n")
        File(root, "fetch-events.jsonl").writeText(
            h.fetchEvents.sortedBy(FetchEvent::eventSequence)
                .joinToString(separator = "\n", postfix = "\n") { recoveryJson(it.toArtifactMap()) },
        )
        File(root, "case.json").writeText(
            recoveryJson(
                linkedMapOf(
                    "schemaVersion" to 1,
                    "caseId" to caseId,
                    "policyId" to TEST_POLICY.policyId,
                    "expectedPhysicalAttempts" to expectedPhysicalAttempts,
                    "expectedTerminals" to expectedTerminals,
                ),
            ) + "\n",
        )
    }

    private companion object {
        const val EVIDENCE_ROOT = "build/m2-c-recovery"
    }
}
