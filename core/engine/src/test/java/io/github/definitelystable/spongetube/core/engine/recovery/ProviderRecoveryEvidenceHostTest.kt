package io.github.definitelystable.spongetube.core.engine.recovery

import io.github.definitelystable.spongetube.core.engine.FetchAttemptDisposition
import io.github.definitelystable.spongetube.core.engine.FetchEvent
import io.github.definitelystable.spongetube.core.engine.FetchNetworkChunk
import io.github.definitelystable.spongetube.core.engine.FetchRequest
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingEventKind
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingRefresher
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingRevision
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryMaterialRefresh
import io.github.definitelystable.spongetube.core.engine.delivery.RetryAfterKind
import io.github.definitelystable.spongetube.core.engine.delivery.RetryAfterObservation
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Timeout

/**
 * Deterministic M2-D provider-recovery evidence producer for the independent
 * host oracle (`scripts/measurement/m2_provider_oracle.py`, run by
 * `scripts/ci/verify-m2-d-provider-evidence.sh host`).
 *
 * Each case drives the production RecoveryCoordinator, FetchBroker,
 * FailureClassifier, RecoveryPolicy and DeliveryBindingCoordinator over
 * scripted provider attempts and writes one directory per case under
 * `build/m2-d-provider/<caseId>/`: `failure-decision-events-v2`,
 * `recovery-budget-events-v1`, `delivery-binding-events-v1`,
 * `fetch-events-v4` (JSONL) and the scenario definition (`case.json`). The
 * oracle re-derives every classification, decision, executed action, provider
 * wait, refresh lifecycle and ledger transition independently; this test only
 * produces the evidence and pins the anchors that must not regress in Gradle.
 */
@Timeout(120)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ProviderRecoveryEvidenceHostTest {
    @Test
    @Order(1)
    fun deterministicProviderCasesProduceEvidence() {
        File(EVIDENCE_ROOT).deleteRecursively()
        bare403()
        delaySecondsRetryAfter()
        httpDateRetryAfter()
        retryAfterAbsent()
        retryAfterMalformed()
        bindingExpiredRefresh()
        refreshIncompatible()
        refreshFailed()
        alreadyAdvanced()
        concurrentSingleFlight()
        refreshBudgetExhausted()
        providerWaitCancelled()
        shutdownDuringRefresh()
        rateLimitBudgetExhausted()
    }

    /**
     * Every case produced the four artifacts plus its definition, all four
     * carry their frozen schema version and none retains a URL scheme.
     */
    @Test
    @Order(2)
    fun evidenceArtifactsHaveTheExpectedShape() {
        val root = File(EVIDENCE_ROOT)
        assertTrue(root.isDirectory, "missing evidence root: ${root.absolutePath}")
        CASES.forEach { caseId ->
            val directory = File(root, caseId)
            assertTrue(directory.isDirectory, "missing case directory: ${directory.path}")
            val failure = File(directory, "failure-decision-events.json")
            val budget = File(directory, "recovery-budget-events.json")
            val delivery = File(directory, "delivery-binding-events.json")
            val fetch = File(directory, "fetch-events.jsonl")
            val scenario = File(directory, "case.json")
            val artifacts = listOf(failure, budget, delivery, fetch, scenario)
            artifacts.forEach { artifact ->
                assertTrue(artifact.isFile, "missing artifact: ${artifact.path}")
                assertTrue(artifact.length() > 0, "empty artifact: ${artifact.path}")
            }
            assertSchemaVersion(failure, "2")
            assertSchemaVersion(budget, "1")
            assertSchemaVersion(delivery, "1")
            val fetchLines = fetch.readText().trim().lines()
            assertTrue(fetchLines.isNotEmpty(), "$caseId: no fetch rows")
            fetchLines.forEachIndexed { index, line ->
                assertTrue(
                    "\"schemaVersion\":4" in line,
                    "$caseId: fetch[$index] is not fetch-events-v4",
                )
            }
            assertTrue(
                "\"caseId\":\"$caseId\"" in scenario.readText(),
                "$caseId: case.json names another case",
            )
            assertTrue(
                "\"evidenceSource\":\"HOST_SCRIPTED\"" in scenario.readText(),
                "$caseId: case.json names another evidence source",
            )
            artifacts.forEach { artifact ->
                val text = artifact.readText()
                assertFalse("http://" in text, "$caseId: ${artifact.name} retains a URL scheme")
                assertFalse("https://" in text, "$caseId: ${artifact.name} retains a URL scheme")
            }
        }
    }

    /** N8/`HTTP_403_BARE`: a bare 403 never refines to a stale binding. */
    private fun bare403() {
        val refresher = TestRefresher()
        val work = RecoveryHarness.work("n8-bare-403")
        case(
            caseId = "n8-bare-403",
            scenarioFamily = "N8",
            variant = "HTTP_403_BARE",
            works = listOf(work),
            expectedPhysicalAttempts = 1,
            expectedTerminals = mapOf("TERMINAL_FAILURE" to 1),
            expectedRefreshOperations = 0,
            expectedProviderWaits = emptyList(),
            expectedBindingRevisions = listOf(BINDING_1.value),
            refresher = refresher,
        ) { h ->
            h.origin.script(work.fetchKey, h.origin.http(403))
            val outcome = h.blocking { h.acquire(work, "playback").await() }

            assertEquals(RecoveryTerminalReason.TERMINAL_FAILURE, outcome.terminalReason)
            val failure = h.failures.single()
            assertEquals(FailureClassification.PROVIDER_REJECTED, failure.classification)
            assertEquals(
                RecoveryDecisionReason.NON_RETRYABLE_CLASSIFICATION,
                failure.decision.reason,
            )
            assertEquals(RecoveryActionKind.TERMINATE_FAILURE, failure.action.kind)
            assertTrue(refresher.calls.isEmpty())
            assertEquals(0, checkNotNull(h.bindings).refreshOperationCountForTest())
        }
    }

    /** N9/`HTTP_429_RETRY_AFTER_DELAY_SECONDS`: wait as-is, then retry. */
    private fun delaySecondsRetryAfter() {
        val work = RecoveryHarness.work("n9-delay-seconds")
        case(
            caseId = "n9-delay-seconds",
            scenarioFamily = "N9",
            variant = "HTTP_429_RETRY_AFTER_DELAY_SECONDS",
            works = listOf(work),
            expectedPhysicalAttempts = 2,
            expectedTerminals = mapOf("SUCCESS" to 1),
            expectedRefreshOperations = 0,
            expectedProviderWaits = listOf(2_000L),
            expectedBindingRevisions = listOf(BINDING_1.value, BINDING_1.value),
        ) { h ->
            h.origin.script(work.fetchKey, h.origin.http(429, delaySeconds(2)))

            val outcome = h.blocking { h.acquire(work, "playback").await() }

            assertTrue(outcome.isSuccess, "outcome=$outcome failures=${h.failures}")
            val failure = h.failures.single()
            assertEquals(
                FailureClassification.PROVIDER_RATE_LIMITED,
                failure.classification,
            )
            assertEquals(RecoveryDecisionKind.WAIT_UNTIL_PROVIDER, failure.decision.kind)
            assertEquals(RecoveryActionKind.WAIT_PROVIDER, failure.action.kind)
            val wait = checkNotNull(failure.action.providerWait)
            assertEquals(RetryAfterKind.DELAY_SECONDS, wait.rawKind)
            assertEquals(2_000L, wait.waitMs)
            assertEquals(listOf(2_000L), (h.sleeper as RecordingSleeper).delays.toList())
            assertTrue(
                h.chargeEvents(RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH).isEmpty(),
            )
        }
    }

    /** N9/`HTTP_429_RETRY_AFTER_HTTP_DATE`: the wait uses PROVIDER_WALL_CLOCK. */
    private fun httpDateRetryAfter() {
        val work = RecoveryHarness.work("n9-http-date")
        case(
            caseId = "n9-http-date",
            scenarioFamily = "N9",
            variant = "HTTP_429_RETRY_AFTER_HTTP_DATE",
            works = listOf(work),
            expectedPhysicalAttempts = 2,
            expectedTerminals = mapOf("SUCCESS" to 1),
            expectedRefreshOperations = 0,
            expectedProviderWaits = listOf(2_000L),
            expectedBindingRevisions = listOf(BINDING_1.value, BINDING_1.value),
        ) { h ->
            h.origin.script(
                work.fetchKey,
                h.origin.http(
                    429,
                    httpDate(HARNESS_PROVIDER_WALL_CLOCK_UTC_MS + 2_000),
                ),
            )

            val outcome = h.blocking { h.acquire(work, "playback").await() }

            assertTrue(outcome.isSuccess, "outcome=$outcome failures=${h.failures}")
            val failure = h.failures.single()
            assertEquals(RecoveryDecisionKind.WAIT_UNTIL_PROVIDER, failure.decision.kind)
            val wait = checkNotNull(failure.action.providerWait)
            assertEquals(RetryAfterKind.HTTP_DATE, wait.rawKind)
            assertEquals(2_000L, wait.waitMs)
            assertEquals(
                HARNESS_PROVIDER_WALL_CLOCK_UTC_MS + 2_000,
                wait.notBeforeUtcEpochMs,
            )
            assertEquals(HARNESS_PROVIDER_WALL_CLOCK_UTC_MS, wait.wallClockNowUtcEpochMs)
            assertEquals(listOf(2_000L), (h.sleeper as RecordingSleeper).delays.toList())
        }
    }

    /** N9/`HTTP_429_RETRY_AFTER_ABSENT`: fail closed after one attempt. */
    private fun retryAfterAbsent() {
        val work = RecoveryHarness.work("n9-retry-after-absent")
        case(
            caseId = "n9-retry-after-absent",
            scenarioFamily = "N9",
            variant = "HTTP_429_RETRY_AFTER_ABSENT",
            works = listOf(work),
            expectedPhysicalAttempts = 1,
            expectedTerminals = mapOf("TERMINAL_FAILURE" to 1),
            expectedRefreshOperations = 0,
            expectedProviderWaits = emptyList(),
            expectedBindingRevisions = listOf(BINDING_1.value),
        ) { h ->
            h.origin.script(work.fetchKey, h.origin.http(429))

            val outcome = h.blocking { h.acquire(work, "playback").await() }

            assertEquals(RecoveryTerminalReason.TERMINAL_FAILURE, outcome.terminalReason)
            val failure = h.failures.single()
            assertEquals(
                FailureClassification.PROVIDER_RATE_LIMITED,
                failure.classification,
            )
            assertEquals(RecoveryDecisionKind.FAIL_TERMINAL, failure.decision.kind)
            assertEquals(RecoveryDecisionReason.RETRY_AFTER_ABSENT, failure.decision.reason)
            assertEquals(RecoveryActionKind.TERMINATE_FAILURE, failure.action.kind)
            assertTrue((h.sleeper as RecordingSleeper).delays.isEmpty())
        }
    }

    /** N9/`HTTP_429_RETRY_AFTER_MALFORMED`: fail closed after one attempt. */
    private fun retryAfterMalformed() {
        val work = RecoveryHarness.work("n9-retry-after-malformed")
        case(
            caseId = "n9-retry-after-malformed",
            scenarioFamily = "N9",
            variant = "HTTP_429_RETRY_AFTER_MALFORMED",
            works = listOf(work),
            expectedPhysicalAttempts = 1,
            expectedTerminals = mapOf("TERMINAL_FAILURE" to 1),
            expectedRefreshOperations = 0,
            expectedProviderWaits = emptyList(),
            expectedBindingRevisions = listOf(BINDING_1.value),
        ) { h ->
            h.origin.script(work.fetchKey, h.origin.http(429, RetryAfterObservation.MALFORMED))

            val outcome = h.blocking { h.acquire(work, "playback").await() }

            assertEquals(RecoveryTerminalReason.TERMINAL_FAILURE, outcome.terminalReason)
            val failure = h.failures.single()
            assertEquals(RecoveryDecisionKind.FAIL_TERMINAL, failure.decision.kind)
            assertEquals(
                RecoveryDecisionReason.RETRY_AFTER_MALFORMED,
                failure.decision.reason,
            )
            assertEquals(RecoveryActionKind.TERMINATE_FAILURE, failure.action.kind)
            assertTrue((h.sleeper as RecordingSleeper).delays.isEmpty())
        }
    }

    /** N10/`BINDING_EXPIRY_REFRESH`: one stale 403 refreshes to binding-2. */
    private fun bindingExpiredRefresh() {
        val refresher = TestRefresher()
        refresher.enqueue(TestRefresher.material(2))
        val work = RecoveryHarness.work("n10-binding-expired-refresh")
        case(
            caseId = "n10-binding-expired-refresh",
            scenarioFamily = "N10",
            variant = "BINDING_EXPIRY_REFRESH",
            works = listOf(work),
            expectedPhysicalAttempts = 2,
            expectedTerminals = mapOf("SUCCESS" to 1),
            expectedRefreshOperations = 1,
            expectedProviderWaits = emptyList(),
            expectedBindingRevisions = listOf(BINDING_1.value, BINDING_2.value),
            refresher = refresher,
        ) { h ->
            h.origin.script(
                work.fetchKey,
                h.origin.http(403, signal = ProviderSignal.BINDING_STALE_CONFIRMED),
            )

            val outcome = h.blocking { h.acquire(work, "playback").await() }

            assertTrue(outcome.isSuccess, "outcome=$outcome failures=${h.failures}")
            assertEquals(listOf("refresh-1"), refresher.calls.toList())
            assertEquals(1, checkNotNull(h.bindings).refreshOperationCountForTest())
            assertEquals(BINDING_2, checkNotNull(h.bindings).current().revision)

            val failure = h.failures.single()
            assertEquals(FailureClassification.DELIVERY_BINDING_STALE, failure.classification)
            assertEquals(
                RecoveryDecisionReason.STALE_BINDING_SIGNAL,
                failure.decision.reason,
            )
            val record = checkNotNull(failure.action.deliveryBinding)
            assertEquals(DeliveryBindingActionResult.REFRESHED, record.result)
            assertEquals(BINDING_1, record.expectedRevision)
            assertEquals(BINDING_2, record.currentRevision)
            assertEquals("refresh-1", record.refreshCorrelationId)
            assertTrue(record.charged)
            val refreshCharge =
                h.chargeEvents(RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH).single()
            assertEquals(1, checkNotNull(refreshCharge.charge).amount)
            assertEquals(failure.failureId, refreshCharge.failureId)
            assertEquals(1, h.failures.size)
        }
    }

    /** N10/`BINDING_REFRESH_INCOMPATIBLE`: an incompatible refresh fails closed. */
    private fun refreshIncompatible() {
        val refresher = TestRefresher()
        refresher.enqueue(DeliveryMaterialRefresh.Incompatible)
        val work = RecoveryHarness.work("n10-refresh-incompatible")
        case(
            caseId = "n10-refresh-incompatible",
            scenarioFamily = "N10",
            variant = "BINDING_REFRESH_INCOMPATIBLE",
            works = listOf(work),
            expectedPhysicalAttempts = 1,
            expectedTerminals = mapOf("TERMINAL_FAILURE" to 1),
            expectedRefreshOperations = 1,
            expectedProviderWaits = emptyList(),
            expectedBindingRevisions = listOf(BINDING_1.value),
            refresher = refresher,
        ) { h ->
            h.origin.script(
                work.fetchKey,
                h.origin.http(403, signal = ProviderSignal.BINDING_STALE_CONFIRMED),
            )

            val outcome = h.blocking { h.acquire(work, "playback").await() }

            assertEquals(RecoveryTerminalReason.TERMINAL_FAILURE, outcome.terminalReason)
            assertEquals(
                DeliveryBindingActionResult.INCOMPATIBLE,
                outcome.deliveryBindingResult,
            )
            assertEquals(BINDING_1, checkNotNull(h.bindings).current().revision)
            assertEquals(1, checkNotNull(h.bindings).refreshOperationCountForTest())
            assertEquals(
                1,
                h.bindingEvents.count {
                    it.kind == DeliveryBindingEventKind.REFRESH_INCOMPATIBLE
                },
            )
            val record = checkNotNull(h.failures.single().action.deliveryBinding)
            assertEquals(DeliveryBindingActionResult.INCOMPATIBLE, record.result)
            assertEquals(BINDING_1, record.expectedRevision)
            assertNull(record.currentRevision)
            assertEquals("refresh-1", record.refreshCorrelationId)
            assertTrue(record.charged)
        }
    }

    /** N10/`BINDING_REFRESH_FAILED`: a failed refresh fails closed. */
    private fun refreshFailed() {
        val refresher = TestRefresher()
        refresher.enqueue(DeliveryMaterialRefresh.Failed)
        val work = RecoveryHarness.work("n10-refresh-failed")
        case(
            caseId = "n10-refresh-failed",
            scenarioFamily = "N10",
            variant = "BINDING_REFRESH_FAILED",
            works = listOf(work),
            expectedPhysicalAttempts = 1,
            expectedTerminals = mapOf("TERMINAL_FAILURE" to 1),
            expectedRefreshOperations = 1,
            expectedProviderWaits = emptyList(),
            expectedBindingRevisions = listOf(BINDING_1.value),
            refresher = refresher,
        ) { h ->
            h.origin.script(
                work.fetchKey,
                h.origin.http(403, signal = ProviderSignal.BINDING_STALE_CONFIRMED),
            )

            val outcome = h.blocking { h.acquire(work, "playback").await() }

            assertEquals(RecoveryTerminalReason.TERMINAL_FAILURE, outcome.terminalReason)
            assertEquals(DeliveryBindingActionResult.FAILED, outcome.deliveryBindingResult)
            assertEquals(
                1,
                h.bindingEvents.count { it.kind == DeliveryBindingEventKind.REFRESH_FAILED },
            )
            val record = checkNotNull(h.failures.single().action.deliveryBinding)
            assertEquals(DeliveryBindingActionResult.FAILED, record.result)
            assertNull(record.currentRevision)
            assertEquals("refresh-1", record.refreshCorrelationId)
            assertTrue(record.charged)
            assertEquals(0, h.coordinator.activeChainCountForTest())
        }
    }

    /**
     * N10: two chains both stale on binding-1; the second one requests after
     * the first already advanced and consumes no refresh budget.
     */
    private fun alreadyAdvanced() {
        val releaseRefresh = CompletableDeferred<Unit>()
        val refresher = TestRefresher(releaseRefresh)
        refresher.enqueue(TestRefresher.material(2))
        val workA = RecoveryHarness.work("n10-already-advanced-a")
        val workB = RecoveryHarness.work("n10-already-advanced-b")
        val bEntered = CompletableDeferred<Unit>()
        val bRelease = CompletableDeferred<Unit>()
        case(
            caseId = "n10-already-advanced",
            scenarioFamily = "N10",
            variant = null,
            works = listOf(workA, workB),
            expectedPhysicalAttempts = 4,
            expectedTerminals = mapOf("SUCCESS" to 2),
            expectedRefreshOperations = 1,
            expectedProviderWaits = emptyList(),
            expectedBindingRevisions = listOf(
                BINDING_1.value,
                BINDING_1.value,
                BINDING_2.value,
                BINDING_2.value,
            ),
            refresher = refresher,
        ) { h ->
            h.origin.script(
                workA.fetchKey,
                h.origin.http(403, signal = ProviderSignal.BINDING_STALE_CONFIRMED),
                { request, _, emitChunk -> succeedCorrelated(request, emitChunk, "lab-a2") },
            )
            h.origin.scriptBound(
                workB.fetchKey,
                { _, _, _, revision ->
                    bEntered.complete(Unit)
                    bRelease.await()
                    stale403(revision)
                },
            )
            h.origin.script(
                workB.fetchKey,
                { request, _, emitChunk -> succeedCorrelated(request, emitChunk, "lab-b2") },
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
            assertEquals(1, checkNotNull(h.bindings).refreshOperationCountForTest())
            assertEquals(BINDING_2, checkNotNull(h.bindings).current().revision)

            val refreshA = checkNotNull(
                h.failures.single { it.recoveryChainId == outcomeA.recoveryChainId }
                    .action.deliveryBinding,
            )
            assertEquals(DeliveryBindingActionResult.REFRESHED, refreshA.result)
            assertTrue(refreshA.charged)
            val refreshB = checkNotNull(
                h.failures.single { it.recoveryChainId == outcomeB.recoveryChainId }
                    .action.deliveryBinding,
            )
            assertEquals(DeliveryBindingActionResult.ALREADY_ADVANCED, refreshB.result)
            assertFalse(refreshB.charged)

            val advanced = h.bindingEvents.single {
                it.kind == DeliveryBindingEventKind.REVISION_ALREADY_ADVANCED
            }
            assertEquals(outcomeB.recoveryChainId.value, advanced.recoveryChainId)
            assertEquals(BINDING_1.value, advanced.previousRevision?.value)
            assertEquals(BINDING_2.value, advanced.currentRevision?.value)
            val charges =
                h.chargeEvents(RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH)
            assertEquals(1, charges.size)
            assertEquals(outcomeA.recoveryChainId, charges.single().recoveryChainId)
        }
    }

    /** N10: two chains single-flight exactly one refresh operation. */
    private fun concurrentSingleFlight() {
        val releaseRefresh = CompletableDeferred<Unit>()
        val refresher = TestRefresher(releaseRefresh)
        refresher.enqueue(TestRefresher.material(2))
        val workA = RecoveryHarness.work("n10-concurrent-single-flight-a")
        val workB = RecoveryHarness.work("n10-concurrent-single-flight-b")
        case(
            caseId = "n10-concurrent-single-flight",
            scenarioFamily = "N10",
            variant = null,
            works = listOf(workA, workB),
            expectedPhysicalAttempts = 4,
            expectedTerminals = mapOf("SUCCESS" to 2),
            expectedRefreshOperations = 1,
            expectedProviderWaits = emptyList(),
            expectedBindingRevisions = listOf(
                BINDING_1.value,
                BINDING_1.value,
                BINDING_2.value,
                BINDING_2.value,
            ),
            refresher = refresher,
        ) { h ->
            h.origin.scriptBound(workA.fetchKey, { _, _, _, revision -> stale403(revision) })
            h.origin.script(
                workA.fetchKey,
                { request, _, emitChunk -> succeedCorrelated(request, emitChunk, "lab-a2") },
            )
            h.origin.scriptBound(workB.fetchKey, { _, _, _, revision -> stale403(revision) })
            h.origin.script(
                workB.fetchKey,
                { request, _, emitChunk -> succeedCorrelated(request, emitChunk, "lab-b2") },
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
            assertEquals(1, checkNotNull(h.bindings).refreshOperationCountForTest())
            val started = h.bindingEvents.single {
                it.kind == DeliveryBindingEventKind.REFRESH_STARTED
            }
            val joined = h.bindingEvents.single {
                it.kind == DeliveryBindingEventKind.REFRESH_JOINED
            }
            assertEquals(outcomeA.recoveryChainId.value, started.recoveryChainId)
            assertEquals(outcomeB.recoveryChainId.value, joined.recoveryChainId)
            assertEquals(started.refreshCorrelationId, joined.refreshCorrelationId)
            assertEquals("refresh-1", started.refreshCorrelationId)

            val refreshA = checkNotNull(
                h.failures.single { it.recoveryChainId == outcomeA.recoveryChainId }
                    .action.deliveryBinding,
            )
            assertEquals(DeliveryBindingActionResult.REFRESHED, refreshA.result)
            assertTrue(refreshA.charged)
            assertEquals(BINDING_2, refreshA.currentRevision)
            val refreshB = checkNotNull(
                h.failures.single { it.recoveryChainId == outcomeB.recoveryChainId }
                    .action.deliveryBinding,
            )
            assertEquals(DeliveryBindingActionResult.JOINED_REFRESH, refreshB.result)
            assertFalse(refreshB.charged)
            assertEquals(BINDING_2, refreshB.currentRevision)

            val charges =
                h.chargeEvents(RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH)
            assertEquals(1, charges.size)
            assertEquals(outcomeA.recoveryChainId, charges.single().recoveryChainId)
        }
    }

    /** A second stale owner exhausts the single DELIVERY_BINDING_REFRESH charge. */
    private fun refreshBudgetExhausted() {
        val refresher = TestRefresher()
        refresher.enqueue(TestRefresher.material(2))
        val work = RecoveryHarness.work("refresh-budget-exhausted")
        case(
            caseId = "refresh-budget-exhausted",
            scenarioFamily = null,
            variant = null,
            works = listOf(work),
            expectedPhysicalAttempts = 2,
            expectedTerminals = mapOf("BUDGET_EXHAUSTED" to 1),
            expectedRefreshOperations = 1,
            expectedProviderWaits = emptyList(),
            expectedBindingRevisions = listOf(BINDING_1.value, BINDING_2.value),
            refresher = refresher,
        ) { h ->
            h.origin.script(
                work.fetchKey,
                h.origin.http(403, signal = ProviderSignal.BINDING_STALE_CONFIRMED),
                h.origin.http(403, signal = ProviderSignal.BINDING_STALE_CONFIRMED),
            )

            val outcome = h.blocking { h.acquire(work, "playback").await() }

            assertEquals(RecoveryTerminalReason.BUDGET_EXHAUSTED, outcome.terminalReason)
            assertEquals(
                RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH,
                outcome.exhaustedDimension,
            )
            assertEquals(2, h.failures.size)
            assertEquals(
                DeliveryBindingActionResult.REFRESHED,
                checkNotNull(h.failures.first().action.deliveryBinding).result,
            )
            val last = h.failures.last()
            assertEquals(RecoveryActionKind.REFRESH_DELIVERY_BINDING, last.action.kind)
            assertEquals(
                RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH,
                last.action.exhaustedDimension,
            )
            assertEquals(0, last.context.deliveryBindingRefreshesRemaining)
            val record = checkNotNull(last.action.deliveryBinding)
            assertEquals(DeliveryBindingActionResult.NOT_ADMITTED, record.result)
            assertFalse(record.charged)
            assertEquals(
                1,
                h.chargeEvents(RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH).size,
            )
        }
    }

    /**
     * The only consumer releases during the provider wait: the chain ends as
     * NO_REMAINING_DEMAND without waiting the full Retry-After and without
     * advancing the harness clock.
     */
    private fun providerWaitCancelled() {
        val sleeper = SuspendedSleeper()
        val work = RecoveryHarness.work("provider-wait-cancelled")
        case(
            caseId = "provider-wait-cancelled",
            scenarioFamily = null,
            variant = null,
            works = listOf(work),
            expectedPhysicalAttempts = 1,
            expectedTerminals = mapOf("NO_REMAINING_DEMAND" to 1),
            expectedRefreshOperations = 0,
            expectedProviderWaits = listOf(30_000L),
            expectedBindingRevisions = listOf(BINDING_1.value),
            sleeper = sleeper,
        ) { h ->
            h.origin.script(work.fetchKey, h.origin.http(429, delaySeconds(30)))

            val handle = h.blocking { h.acquire(work, "playback") }
            h.blocking { sleeper.entered.await() }
            handle.close()
            h.awaitCondition {
                h.budget(RecoveryBudgetEventKind.CHAIN_TERMINATED).isNotEmpty()
            }

            val failure = h.failures.single()
            assertEquals(
                FailureClassification.PROVIDER_RATE_LIMITED,
                failure.classification,
            )
            assertEquals(RecoveryActionKind.WAIT_PROVIDER, failure.action.kind)
            val wait = checkNotNull(failure.action.providerWait)
            assertEquals(RetryAfterKind.DELAY_SECONDS, wait.rawKind)
            assertEquals(30_000L, wait.waitMs)
            val terminal = h.budget(RecoveryBudgetEventKind.CHAIN_TERMINATED).single()
            assertEquals(RecoveryTerminalReason.NO_REMAINING_DEMAND, terminal.terminalReason)
            assertTrue(
                terminal.elapsedRealtimeNs <
                    failure.elapsedRealtimeNs + 30_000L * 1_000_000,
                "a cancelled provider wait advanced the monotonic clock",
            )
            assertEquals(1, h.origin.executions(work.fetchKey))
        }
    }

    /** Session shutdown cancels the in-flight refresh before the chain ends. */
    private fun shutdownDuringRefresh() {
        val refresher = TestRefresher(CompletableDeferred())
        val work = RecoveryHarness.work("shutdown-during-refresh")
        case(
            caseId = "shutdown-during-refresh",
            scenarioFamily = null,
            variant = null,
            works = listOf(work),
            expectedPhysicalAttempts = 1,
            expectedTerminals = mapOf("SESSION_TERMINATION" to 1),
            expectedRefreshOperations = 1,
            expectedProviderWaits = emptyList(),
            expectedBindingRevisions = listOf(BINDING_1.value),
            refresher = refresher,
        ) { h ->
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
            assertEquals(1, refresher.calls.size)
            assertEquals(
                1,
                h.bindingEvents.count { it.kind == DeliveryBindingEventKind.REFRESH_CANCELLED },
            )
            val record = checkNotNull(h.failures.single().action.deliveryBinding)
            assertEquals(DeliveryBindingActionResult.ABANDONED, record.result)
            assertTrue(record.charged)
            assertEquals(0, h.coordinator.activeChainCountForTest())
        }
    }

    /** Four provider throttles spend the whole REMOTE_ATTEMPT budget. */
    private fun rateLimitBudgetExhausted() {
        val work = RecoveryHarness.work("rate-limit-budget-exhausted")
        case(
            caseId = "rate-limit-budget-exhausted",
            scenarioFamily = null,
            variant = null,
            works = listOf(work),
            expectedPhysicalAttempts = 4,
            expectedTerminals = mapOf("BUDGET_EXHAUSTED" to 1),
            expectedRefreshOperations = 0,
            expectedProviderWaits = listOf(0L, 0L, 0L),
            expectedBindingRevisions = listOf(
                BINDING_1.value,
                BINDING_1.value,
                BINDING_1.value,
                BINDING_1.value,
            ),
        ) { h ->
            h.origin.script(
                work.fetchKey,
                h.origin.http(429, delaySeconds(0)),
                h.origin.http(429, delaySeconds(0)),
                h.origin.http(429, delaySeconds(0)),
                h.origin.http(429, delaySeconds(0)),
            )

            val outcome = h.blocking { h.acquire(work, "playback").await() }

            assertEquals(RecoveryTerminalReason.BUDGET_EXHAUSTED, outcome.terminalReason)
            assertEquals(RecoveryBudgetDimension.REMOTE_ATTEMPT, outcome.exhaustedDimension)
            assertEquals(4, h.failures.size)
            assertTrue(
                h.failures.dropLast(1).all {
                    it.action.kind == RecoveryActionKind.WAIT_PROVIDER
                },
            )
            val last = h.failures.last()
            assertEquals(RecoveryActionKind.TERMINATE_BUDGET_EXHAUSTED, last.action.kind)
            assertEquals(RecoveryBudgetDimension.REMOTE_ATTEMPT, last.action.exhaustedDimension)
            assertNull(last.action.providerWait)
            assertEquals(listOf(0L, 0L, 0L), (h.sleeper as RecordingSleeper).delays.toList())
        }
    }

    /**
     * Runs one case: seeds one unrelated persisted extent, drives the harness,
     * asserts the scenario anchors and writes the five case files.
     */
    private fun case(
        caseId: String,
        scenarioFamily: String?,
        variant: String?,
        works: List<FetchRequest>,
        expectedPhysicalAttempts: Int,
        expectedTerminals: Map<String, Int>,
        expectedRefreshOperations: Int,
        expectedProviderWaits: List<Long>,
        expectedBindingRevisions: List<String>,
        refresher: DeliveryBindingRefresher? = TestRefresher(),
        gate: RecoveryAttemptGate = RecoveryAttemptGate.ALWAYS_PERMIT,
        sleeper: RecoverySleeper? = null,
        script: (RecoveryHarness) -> Unit,
    ) {
        val h = RecoveryHarness(
            policy = TEST_POLICY,
            gate = gate,
            sleeper = sleeper,
            sessionId = "m2-d-host-$caseId",
            refresher = refresher,
        )
        val seed = RecoveryHarness.work("$caseId-persisted")
        val candidates = listOf(seed) + works
        h.publisher.seed(seed.extentSpec)
        val persistedBefore = persistedExtentIds(h, candidates)
        val startedNs = System.nanoTime()
        try {
            script(h)
            h.awaitCondition { h.coordinator.activeChainCountForTest() == 0 }
        } finally {
            h.shutdown()
        }
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
        assertTrue(elapsedMs < 10_000, "$caseId took ${elapsedMs}ms")

        assertEquals(
            listOf(seed.extentSpec.extentId.value),
            persistedBefore,
            "$caseId: the seeded extent must be the only persisted extent before the run",
        )
        assertEquals(expectedPhysicalAttempts, h.origin.executions.size, caseId)
        val remoteCharges = h.chargeEvents(RecoveryBudgetDimension.REMOTE_ATTEMPT)
        val refreshCharges =
            h.chargeEvents(RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH)
        assertEquals(expectedPhysicalAttempts, remoteCharges.size, caseId)
        assertEquals(expectedRefreshOperations, refreshCharges.size, caseId)
        val terminals = h.budget(RecoveryBudgetEventKind.CHAIN_TERMINATED)
            .groupingBy { checkNotNull(it.terminalReason).name }
            .eachCount()
        assertEquals(expectedTerminals, terminals, caseId)
        val ledger = h.budget(RecoveryBudgetEventKind.CHAIN_TERMINATED)
        assertEquals(
            expectedPhysicalAttempts,
            ledger.sumOf { it.spent.getValue(RecoveryBudgetDimension.REMOTE_ATTEMPT) },
            "$caseId: remote-attempt ledger",
        )
        assertEquals(
            expectedRefreshOperations,
            ledger.sumOf {
                it.spent.getValue(RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH)
            },
            "$caseId: refresh ledger",
        )
        remoteCharges.groupBy { it.recoveryChainId }.values.forEach { charges ->
            assertEquals(
                (1..charges.size).toList(),
                charges.map { checkNotNull(it.charge).spentAfter },
                "$caseId: per-chain remote ledger",
            )
        }
        assertEquals(
            expectedProviderWaits,
            h.failures
                .filter { it.action.kind == RecoveryActionKind.WAIT_PROVIDER }
                .map { checkNotNull(it.action.providerWait).waitMs },
            caseId,
        )
        val selections = h.bindingEvents
            .filter { it.kind == DeliveryBindingEventKind.BINDING_SELECTED_FOR_ATTEMPT }
            .associateBy { checkNotNull(it.attemptCorrelationId) }
        assertEquals(
            expectedBindingRevisions,
            remoteCharges.map { charge ->
                checkNotNull(selections[charge.attemptCorrelationId]) {
                    "$caseId: no binding selection for ${charge.attemptCorrelationId}"
                }.currentRevision?.value
            },
            caseId,
        )
        val persistedAfter = persistedExtentIds(h, candidates)
        assertTrue(
            persistedAfter.containsAll(persistedBefore),
            "$caseId removed a valid persisted extent: $persistedBefore -> $persistedAfter",
        )

        val root = File(EVIDENCE_ROOT, caseId).apply { mkdirs() }
        File(root, "failure-decision-events.json")
            .writeText(recoveryJson(h.evidence.failureArtifact(TEST_POLICY.policyId)) + "\n")
        File(root, "recovery-budget-events.json")
            .writeText(recoveryJson(h.evidence.budgetArtifact(TEST_POLICY.policyId)) + "\n")
        File(root, "delivery-binding-events.json")
            .writeText(recoveryJson(h.deliveryEvidence.artifact()) + "\n")
        File(root, "fetch-events.jsonl").writeText(
            h.fetchEvents.sortedBy(FetchEvent::eventSequence)
                .joinToString(separator = "\n", postfix = "\n") { recoveryJson(it.toArtifactMap()) },
        )
        File(root, "case.json").writeText(
            recoveryJson(
                linkedMapOf(
                    "schemaVersion" to 1,
                    "caseId" to caseId,
                    "evidenceSource" to "HOST_SCRIPTED",
                    "policyId" to TEST_POLICY.policyId,
                    "scenarioFamily" to scenarioFamily,
                    "variant" to variant,
                    "expectedPhysicalAttempts" to expectedPhysicalAttempts,
                    "expectedTerminals" to expectedTerminals,
                    "expectedRefreshOperations" to expectedRefreshOperations,
                    "expectedProviderWaits" to expectedProviderWaits,
                    "expectedBindingRevisions" to expectedBindingRevisions,
                    "persistedExtentIdsBefore" to persistedBefore,
                    "persistedExtentIdsAfter" to persistedAfter,
                    "bindingsEnabled" to (refresher != null),
                ),
            ) + "\n",
        )
    }

    private fun assertSchemaVersion(file: File, version: String) {
        assertTrue(
            Regex("\"schemaVersion\":$version,").containsMatchIn(file.readText()),
            "${file.path} is not schemaVersion $version",
        )
    }

    private companion object {
        const val EVIDENCE_ROOT = "build/m2-d-provider"

        val BINDING_1 = DeliveryBindingRevision("binding-1")
        val BINDING_2 = DeliveryBindingRevision("binding-2")

        val CASES = listOf(
            "n8-bare-403",
            "n9-delay-seconds",
            "n9-http-date",
            "n9-retry-after-absent",
            "n9-retry-after-malformed",
            "n10-binding-expired-refresh",
            "n10-refresh-incompatible",
            "n10-refresh-failed",
            "n10-already-advanced",
            "n10-concurrent-single-flight",
            "refresh-budget-exhausted",
            "provider-wait-cancelled",
            "shutdown-during-refresh",
            "rate-limit-budget-exhausted",
        )
    }
}

/** Sleeper that parks a provider wait until the chain is cancelled. */
private class SuspendedSleeper : RecoverySleeper {
    val entered = CompletableDeferred<Unit>()

    override suspend fun sleep(delayMs: Long) {
        entered.complete(Unit)
        awaitCancellation()
    }
}

/** A stale 403 observation for the revision of the execution that ran it. */
private fun stale403(revision: DeliveryBindingRevision?): FetchAttemptDisposition =
    FetchAttemptDisposition.Failure(
        FailureObservation.HttpResponse(
            statusCode = 403,
            providerSignal = ProviderSignal.BINDING_STALE_CONFIRMED,
            deliveryBindingRevision = revision,
        ),
    )

/**
 * A successful attempt with a unique transport correlation: two chains of one
 * harness must never share the fixed `lab-ok` correlation of the scripted
 * origin (the provider oracle joins transport correlations one-to-one).
 */
private suspend fun succeedCorrelated(
    request: FetchRequest,
    emitChunk: suspend (FetchNetworkChunk) -> Unit,
    transportCorrelationId: String,
): FetchAttemptDisposition {
    emitChunk(
        FetchNetworkChunk(
            byteStart = request.extentSpec.byteStart ?: 0L,
            bytes = ScriptedOrigin.bytesFor(request.extentSpec),
        ),
    )
    return FetchAttemptDisposition.Success(transportCorrelationId = transportCorrelationId)
}

private fun delaySeconds(seconds: Long): RetryAfterObservation =
    RetryAfterObservation(RetryAfterKind.DELAY_SECONDS, delaySeconds = seconds)

private fun httpDate(notBeforeUtcEpochMs: Long): RetryAfterObservation =
    RetryAfterObservation(RetryAfterKind.HTTP_DATE, notBeforeUtcEpochMs = notBeforeUtcEpochMs)

private fun RecoveryHarness.chargeEvents(
    dimension: RecoveryBudgetDimension,
): List<RecoveryBudgetEvent> =
    budget(RecoveryBudgetEventKind.CHARGE)
        .filter { checkNotNull(it.charge).dimension == dimension }

/** Persisted extent ids of the candidates, in candidate order. */
private fun persistedExtentIds(
    h: RecoveryHarness,
    candidates: List<FetchRequest>,
): List<String> = candidates
    .map { it.extentSpec }
    .distinctBy { it.extentId }
    .filter { h.publisher.contains(it.extentId) }
    .map { it.extentId.value }
