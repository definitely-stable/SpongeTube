package io.github.definitelystable.spongetube.core.engine.delivery

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows

/**
 * Delivery binding coordinator behavior (DESIGN 2 / 2.1). Every test injects
 * an explicit clock; refreshers are controlled with [CompletableDeferred]
 * gates instead of real waits.
 */
@Timeout(30)
class DeliveryBindingCoordinatorTest {
    private val coordinators = mutableListOf<DeliveryBindingCoordinator>()
    private val clock = AtomicLong()

    @AfterEach
    fun tearDown() {
        runBlocking { coordinators.forEach { it.shutdown() } }
    }

    private fun coordinator(
        material: DeliveryMaterial = TestMaterial(1),
        refresher: DeliveryBindingRefresher,
        evidence: DeliveryBindingEvidenceListener? = null,
    ): DeliveryBindingCoordinator = DeliveryBindingCoordinator(
        initialMaterial = material,
        refresher = refresher,
        evidence = evidence,
        clockNs = { clock.incrementAndGet() },
    ).also(coordinators::add)

    @Test
    fun initialSnapshotIsBindingOneAndKeepsMaterialIdentity() = runBlocking {
        val material = TestMaterial(7)
        val coordinator = coordinator(
            material,
            RecordingRefresher { _, _ -> DeliveryMaterialRefresh.Failed },
        )

        val snapshot = coordinator.current()

        assertEquals(REVISION_1, snapshot.revision)
        assertSame(material, snapshot.material)
        assertEquals("DeliveryBindingSnapshot(revision=binding-1)", snapshot.toString())
        assertFalse(snapshot.toString().contains("TestMaterial"))
        assertEquals(0, coordinator.refreshOperationCountForTest())
    }

    @Test
    fun refreshSucceedsOnceAndAdvancesRevision() = runBlocking {
        val refreshed = TestMaterial(2)
        val admission = RecordingAdmission()
        val refresher = RecordingRefresher { _, _ ->
            DeliveryMaterialRefresh.Material(refreshed)
        }
        val recorder = DeliveryBindingEvidenceRecorder(RUN_ID, SESSION_ID)
        val coordinator = coordinator(TestMaterial(1), refresher, recorder)

        val result = coordinator.refresh(REVISION_1, caller(1), admission)

        assertEquals(
            DeliveryBindingRefreshResult.Refreshed(REVISION_1, REVISION_2, "refresh-1"),
            result,
        )
        assertEquals(DeliveryBindingRefreshResultKind.REFRESHED, result.kind)
        assertEquals(listOf("refresh-1"), admission.admittedIds.toList())
        assertEquals(listOf("refresh-1"), refresher.calls.toList())
        assertEquals(REVISION_2, coordinator.current().revision)
        assertSame(refreshed, coordinator.current().material)
        assertEquals(1, coordinator.refreshOperationCountForTest())
        assertEquals(
            listOf(
                DeliveryBindingEventKind.REFRESH_REQUESTED,
                DeliveryBindingEventKind.REFRESH_STARTED,
                DeliveryBindingEventKind.REFRESH_SUCCEEDED,
            ),
            recorder.events().map { it.kind },
        )
    }

    @Test
    fun alreadyAdvancedRevisionStartsNoOperation() = runBlocking {
        val admission = RecordingAdmission()
        val refresher = RecordingRefresher { _, _ ->
            DeliveryMaterialRefresh.Material(TestMaterial(2))
        }
        val recorder = DeliveryBindingEvidenceRecorder(RUN_ID, SESSION_ID)
        val coordinator = coordinator(TestMaterial(1), refresher, recorder)
        assertEquals(
            DeliveryBindingRefreshResult.Refreshed(REVISION_1, REVISION_2, "refresh-1"),
            coordinator.refresh(REVISION_1, caller(1), admission),
        )

        val result = coordinator.refresh(REVISION_1, caller(2), admission)

        assertEquals(
            DeliveryBindingRefreshResult.AlreadyAdvanced(REVISION_1, REVISION_2),
            result,
        )
        assertEquals(DeliveryBindingRefreshResultKind.ALREADY_ADVANCED, result.kind)
        assertEquals(listOf("refresh-1"), admission.admittedIds.toList())
        assertEquals(1, coordinator.refreshOperationCountForTest())
        assertEquals(1, refresher.calls.size)
        assertEquals(
            listOf(
                DeliveryBindingEventKind.REFRESH_REQUESTED,
                DeliveryBindingEventKind.REVISION_ALREADY_ADVANCED,
            ),
            recorder.events().takeLast(2).map { it.kind },
        )
    }

    @Test
    fun concurrentRefreshIsSingleFlight() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val admission = RecordingAdmission()
        val refresher = RecordingRefresher(gate) { _, _ ->
            DeliveryMaterialRefresh.Material(TestMaterial(2))
        }
        val recorder = DeliveryBindingEvidenceRecorder(RUN_ID, SESSION_ID)
        val coordinator = coordinator(TestMaterial(1), refresher, recorder)

        val initiator = async { coordinator.refresh(REVISION_1, caller(1), admission) }
        refresher.entered.await()
        val joiner = async { coordinator.refresh(REVISION_1, caller(2), admission) }
        awaitEvent(recorder, DeliveryBindingEventKind.REFRESH_JOINED)
        gate.complete(Unit)

        assertEquals(
            DeliveryBindingRefreshResult.Refreshed(REVISION_1, REVISION_2, "refresh-1"),
            initiator.await(),
        )
        assertEquals(
            DeliveryBindingRefreshResult.JoinedRefresh(REVISION_1, REVISION_2, "refresh-1"),
            joiner.await(),
        )
        assertEquals(listOf("refresh-1"), admission.admittedIds.toList())
        assertEquals(listOf("refresh-1"), refresher.calls.toList())
        assertEquals(1, coordinator.refreshOperationCountForTest())
        val events = recorder.events()
        assertEquals(1, events.count { it.kind == DeliveryBindingEventKind.REFRESH_STARTED })
        assertEquals(1, events.count { it.kind == DeliveryBindingEventKind.REFRESH_JOINED })
        assertEquals(1, events.count { it.kind == DeliveryBindingEventKind.REFRESH_SUCCEEDED })
        val joined = events.single { it.kind == DeliveryBindingEventKind.REFRESH_JOINED }
        assertEquals("recovery-2", joined.recoveryChainId)
        assertEquals("refresh-1", joined.refreshCorrelationId)
        assertEquals(REVISION_1, joined.previousRevision)
        assertEquals(REVISION_1, joined.currentRevision)
    }

    @Test
    fun incompatibleRefreshKeepsRevision() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val admission = RecordingAdmission()
        val refresher = RecordingRefresher(gate) { _, _ ->
            DeliveryMaterialRefresh.Incompatible
        }
        val recorder = DeliveryBindingEvidenceRecorder(RUN_ID, SESSION_ID)
        val material = TestMaterial(1)
        val coordinator = coordinator(material, refresher, recorder)

        val initiator = async { coordinator.refresh(REVISION_1, caller(1), admission) }
        refresher.entered.await()
        val joiner = async { coordinator.refresh(REVISION_1, caller(2), admission) }
        awaitEvent(recorder, DeliveryBindingEventKind.REFRESH_JOINED)
        gate.complete(Unit)

        assertEquals(
            DeliveryBindingRefreshResult.Incompatible(REVISION_1, "refresh-1", true),
            initiator.await(),
        )
        assertEquals(
            DeliveryBindingRefreshResult.Incompatible(REVISION_1, "refresh-1", false),
            joiner.await(),
        )
        assertEquals(REVISION_1, coordinator.current().revision)
        assertSame(material, coordinator.current().material)
        assertEquals(1, refresher.calls.size)
        assertEquals(1, coordinator.refreshOperationCountForTest())
        val events = recorder.events()
        assertEquals(1, events.count { it.kind == DeliveryBindingEventKind.REFRESH_INCOMPATIBLE })
        val incompatible = events.single {
            it.kind == DeliveryBindingEventKind.REFRESH_INCOMPATIBLE
        }
        assertEquals(DeliveryBindingOperationOutcome.INCOMPATIBLE, incompatible.outcome)
        assertEquals(REVISION_1, incompatible.previousRevision)
        assertEquals(REVISION_1, incompatible.currentRevision)
        assertEquals("refresh-1", incompatible.refreshCorrelationId)
    }

    @Test
    fun failedRefreshKeepsRevisionAndDoesNotRetry() = runBlocking {
        val admission = RecordingAdmission()
        val refresher = RecordingRefresher { _, _ -> DeliveryMaterialRefresh.Failed }
        val recorder = DeliveryBindingEvidenceRecorder(RUN_ID, SESSION_ID)
        val material = TestMaterial(1)
        val coordinator = coordinator(material, refresher, recorder)

        assertEquals(
            DeliveryBindingRefreshResult.Failed(REVISION_1, "refresh-1", true),
            coordinator.refresh(REVISION_1, caller(1), admission),
        )
        assertEquals(REVISION_1, coordinator.current().revision)
        assertSame(material, coordinator.current().material)
        assertEquals(
            DeliveryBindingRefreshResult.Failed(REVISION_1, "refresh-2", true),
            coordinator.refresh(REVISION_1, caller(1), admission),
        )

        // No hidden retry: exactly one refresher call per started operation.
        assertEquals(listOf("refresh-1", "refresh-2"), refresher.calls.toList())
        assertEquals(2, coordinator.refreshOperationCountForTest())
        assertEquals(
            2,
            recorder.events().count { it.kind == DeliveryBindingEventKind.REFRESH_FAILED },
        )
    }

    @Test
    fun throwingRefresherIsOneFailedOperation() = runBlocking {
        val refresher = RecordingRefresher { _, _ ->
            throw IllegalStateException("provider exploded")
        }
        val coordinator = coordinator(TestMaterial(1), refresher)

        assertEquals(
            DeliveryBindingRefreshResult.Failed(REVISION_1, "refresh-1", true),
            coordinator.refresh(REVISION_1, caller(1), RecordingAdmission()),
        )
        assertEquals(REVISION_1, coordinator.current().revision)
        assertEquals(1, coordinator.refreshOperationCountForTest())
        assertEquals(1, refresher.calls.size)

        // The next request is a new operation with a new correlation id.
        assertEquals(
            DeliveryBindingRefreshResult.Failed(REVISION_1, "refresh-2", true),
            coordinator.refresh(REVISION_1, caller(1), RecordingAdmission()),
        )
        assertEquals(2, refresher.calls.size)
    }

    @Test
    fun admissionFailureStartsNothing() = runBlocking {
        val admittedIds = CopyOnWriteArrayList<String>()
        var reject = true
        val admission = DeliveryBindingRefreshAdmission { id ->
            admittedIds += id
            if (reject) throw IllegalStateException("not admitted")
        }
        val refresher = RecordingRefresher { _, _ ->
            DeliveryMaterialRefresh.Material(TestMaterial(2))
        }
        val recorder = DeliveryBindingEvidenceRecorder(RUN_ID, SESSION_ID)
        val coordinator = coordinator(TestMaterial(1), refresher, recorder)

        val result = coordinator.refresh(REVISION_1, caller(1), admission)

        assertEquals(DeliveryBindingRefreshResult.NotAdmitted(REVISION_1), result)
        assertEquals(DeliveryBindingRefreshResultKind.NOT_ADMITTED, result.kind)
        assertTrue(refresher.calls.isEmpty())
        assertEquals(0, coordinator.refreshOperationCountForTest())
        assertEquals(
            listOf(
                DeliveryBindingEventKind.REFRESH_REQUESTED,
                DeliveryBindingEventKind.REFRESH_NOT_ADMITTED,
            ),
            recorder.events().map { it.kind },
        )

        // The rejected id is consumed; a following admitted refresh works.
        reject = false
        val retried = coordinator.refresh(REVISION_1, caller(1), admission)

        assertEquals(
            DeliveryBindingRefreshResult.Refreshed(REVISION_1, REVISION_2, "refresh-2"),
            retried,
        )
        assertEquals(listOf("refresh-1", "refresh-2"), admittedIds.toList())
        assertEquals(1, coordinator.refreshOperationCountForTest())
        assertEquals(REVISION_2, coordinator.current().revision)
    }

    @Test
    fun joinerCancellationDoesNotCorruptSharedRefresh() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val admission = RecordingAdmission()
        val refresher = RecordingRefresher(gate) { _, _ ->
            DeliveryMaterialRefresh.Material(TestMaterial(2))
        }
        val recorder = DeliveryBindingEvidenceRecorder(RUN_ID, SESSION_ID)
        val coordinator = coordinator(TestMaterial(1), refresher, recorder)

        val initiator = async { coordinator.refresh(REVISION_1, caller(1), admission) }
        refresher.entered.await()
        val joiner = async { coordinator.refresh(REVISION_1, caller(2), admission) }
        awaitEvent(recorder, DeliveryBindingEventKind.REFRESH_JOINED)

        joiner.cancel()
        joiner.join()
        gate.complete(Unit)

        assertEquals(
            DeliveryBindingRefreshResult.Refreshed(REVISION_1, REVISION_2, "refresh-1"),
            initiator.await(),
        )
        assertEquals(1, refresher.calls.size)
        assertEquals(1, coordinator.refreshOperationCountForTest())
        assertEquals(REVISION_2, coordinator.current().revision)
        assertEquals(
            1,
            recorder.events().count { it.kind == DeliveryBindingEventKind.REFRESH_SUCCEEDED },
        )
        assertEquals(
            0,
            recorder.events().count { it.kind == DeliveryBindingEventKind.REFRESH_CANCELLED },
        )
    }

    @Test
    fun lastWaiterCancellationCancelsOperation() = runBlocking {
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val cancellationObserved = CompletableDeferred<Unit>()
        val refresher = DeliveryBindingRefresher { _, _ ->
            if (calls.getAndIncrement() == 0) {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } catch (cancelled: CancellationException) {
                    cancellationObserved.complete(Unit)
                    throw cancelled
                }
            }
            DeliveryMaterialRefresh.Material(TestMaterial(2))
        }
        val recorder = DeliveryBindingEvidenceRecorder(RUN_ID, SESSION_ID)
        val material = TestMaterial(1)
        val coordinator = coordinator(material, refresher, recorder)
        val sawCancelledEvent = CompletableDeferred<Boolean>()

        val waiter = launch {
            try {
                coordinator.refresh(REVISION_1, caller(1), RecordingAdmission())
                error("a cancelled refresh must not return normally")
            } catch (cancelled: CancellationException) {
                // The completion event must already be recorded here: the
                // operation is cancelled and joined before the caller's
                // cancellation is rethrown.
                sawCancelledEvent.complete(
                    recorder.events().any {
                        it.kind == DeliveryBindingEventKind.REFRESH_CANCELLED
                    },
                )
                throw cancelled
            }
        }
        entered.await()

        waiter.cancel()
        waiter.join()

        assertTrue(cancellationObserved.isCompleted)
        assertTrue(sawCancelledEvent.await())
        assertEquals(REVISION_1, coordinator.current().revision)
        assertSame(material, coordinator.current().material)
        assertEquals(1, coordinator.refreshOperationCountForTest())
        assertEquals(
            1,
            recorder.events().count { it.kind == DeliveryBindingEventKind.REFRESH_CANCELLED },
        )

        // A later refresh starts a brand new operation.
        assertEquals(
            DeliveryBindingRefreshResult.Refreshed(REVISION_1, REVISION_2, "refresh-2"),
            coordinator.refresh(REVISION_1, caller(1), RecordingAdmission()),
        )
        assertEquals(2, coordinator.refreshOperationCountForTest())
    }

    @Test
    fun shutdownCancelsInFlightRefreshAndRejectsNewOnes() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val admission = RecordingAdmission()
        val refresher = RecordingRefresher(gate) { _, _ ->
            DeliveryMaterialRefresh.Material(TestMaterial(2))
        }
        val recorder = DeliveryBindingEvidenceRecorder(RUN_ID, SESSION_ID)
        val material = TestMaterial(1)
        val coordinator = coordinator(material, refresher, recorder)

        val waiter = async { coordinator.refresh(REVISION_1, caller(1), admission) }
        refresher.entered.await()

        coordinator.shutdown()

        // A cancelled operation maps to Failed(cancelled = true) for a caller
        // still waiting, so the waiter never hangs and can tell a cancelled
        // operation from a provider failure.
        assertEquals(
            DeliveryBindingRefreshResult.Failed(
                REVISION_1,
                "refresh-1",
                true,
                cancelled = true,
            ),
            waiter.await(),
        )
        assertEquals(REVISION_1, coordinator.current().revision)
        assertSame(material, coordinator.current().material)
        assertEquals(
            1,
            recorder.events().count { it.kind == DeliveryBindingEventKind.REFRESH_CANCELLED },
        )

        val closed = coordinator.refresh(REVISION_1, caller(1), admission)

        assertEquals(DeliveryBindingRefreshResult.Closed(REVISION_1), closed)
        assertEquals(DeliveryBindingRefreshResultKind.CLOSED, closed.kind)
        assertEquals(1, refresher.calls.size)
        assertEquals(1, admission.admittedIds.size)
        assertEquals(1, coordinator.refreshOperationCountForTest())

        // Shutdown is idempotent and current() keeps returning the snapshot.
        coordinator.shutdown()
        assertEquals(REVISION_1, coordinator.current().revision)
    }

    @Test
    fun revisionsAreNeverReused() = runBlocking {
        val admission = RecordingAdmission()
        val generation = AtomicInteger(1)
        val refresher = RecordingRefresher { _, _ ->
            DeliveryMaterialRefresh.Material(TestMaterial(generation.incrementAndGet()))
        }
        val recorder = DeliveryBindingEvidenceRecorder(RUN_ID, SESSION_ID)
        val coordinator = coordinator(TestMaterial(1), refresher, recorder)

        val first = coordinator.refresh(REVISION_1, caller(1), admission)
        val afterFirst = coordinator.current().revision
        val second = coordinator.refresh(afterFirst, caller(1), admission)
        val afterSecond = coordinator.current().revision
        val third = coordinator.refresh(afterSecond, caller(1), admission)

        assertEquals(
            DeliveryBindingRefreshResult.Refreshed(REVISION_1, REVISION_2, "refresh-1"),
            first,
        )
        assertEquals(
            DeliveryBindingRefreshResult.Refreshed(REVISION_2, REVISION_3, "refresh-2"),
            second,
        )
        assertEquals(
            DeliveryBindingRefreshResult.Refreshed(REVISION_3, REVISION_4, "refresh-3"),
            third,
        )
        assertEquals(
            listOf(REVISION_2, REVISION_3, REVISION_4),
            listOf(afterFirst, afterSecond, coordinator.current().revision),
        )
        assertEquals(listOf("refresh-1", "refresh-2", "refresh-3"), refresher.calls.toList())
        assertEquals(3, coordinator.refreshOperationCountForTest())
        val sequences = recorder.events().map { it.sequence }
        assertEquals(List(sequences.size) { it + 1L }, sequences)
    }

    @Test
    fun recordSelectionEmitsSelectedEvent() = runBlocking {
        val recorder = DeliveryBindingEvidenceRecorder(RUN_ID, SESSION_ID)
        val coordinator = coordinator(
            TestMaterial(1),
            RecordingRefresher { _, _ -> DeliveryMaterialRefresh.Failed },
            recorder,
        )
        val caller = DeliveryBindingCaller(
            recoveryChainId = "recovery-1",
            failureId = "recovery-1:failure-2",
            fetchKey = "fetch-key-1",
            extentId = "extent-1",
        )

        coordinator.recordSelection(caller, "fetch-key-1:attempt-1", REVISION_1)

        val event = recorder.events().single()
        assertEquals(1L, event.sequence)
        assertEquals(DeliveryBindingEventKind.BINDING_SELECTED_FOR_ATTEMPT, event.kind)
        assertEquals("recovery-1", event.recoveryChainId)
        assertEquals("recovery-1:failure-2", event.failureId)
        assertEquals("fetch-key-1", event.fetchKey)
        assertEquals("extent-1", event.extentId)
        assertEquals("fetch-key-1:attempt-1", event.attemptCorrelationId)
        assertEquals(REVISION_1, event.currentRevision)
        assertNull(event.previousRevision)
        assertNull(event.refreshCorrelationId)
        assertNull(event.outcome)

        // An attempt admitted while the session closes stays attributable.
        coordinator.shutdown()
        coordinator.recordSelection(caller, "fetch-key-2:attempt-1", REVISION_1)
        assertEquals(
            listOf("fetch-key-1:attempt-1", "fetch-key-2:attempt-1"),
            recorder.events().map { it.attemptCorrelationId },
        )
    }

    @Test
    fun modelsRejectMalformedIdentities() {
        assertThrows<IllegalArgumentException> { DeliveryBindingRevision("binding-0") }
        assertThrows<IllegalArgumentException> { DeliveryBindingRevision("binding-01") }
        assertThrows<IllegalArgumentException> { DeliveryBindingRevision("binding-") }
        assertEquals("binding-10", DeliveryBindingRevision("binding-10").value)

        assertThrows<IllegalArgumentException> {
            DeliveryBindingCaller("chain-1", null, "fetch-key-1", "extent-1")
        }
        assertThrows<IllegalArgumentException> {
            DeliveryBindingCaller("recovery-1", "recovery-1:failure-0", "fetch-key-1", "extent-1")
        }
        assertThrows<IllegalArgumentException> {
            DeliveryBindingCaller("recovery-1", null, " ", "extent-1")
        }
        assertThrows<IllegalArgumentException> {
            DeliveryBindingCaller("recovery-1", null, "fetch-key-1", "")
        }
        assertEquals(
            "recovery-2:failure-10",
            DeliveryBindingCaller(
                "recovery-2",
                "recovery-2:failure-10",
                "fetch-key-2",
                "extent-2",
            ).failureId,
        )
    }

    private suspend fun awaitEvent(
        recorder: DeliveryBindingEvidenceRecorder,
        kind: DeliveryBindingEventKind,
    ) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (recorder.events().none { it.kind == kind }) {
            check(System.nanoTime() < deadline) { "event $kind was not recorded" }
            delay(1)
        }
    }

    private fun caller(chain: Int, failure: Int? = null): DeliveryBindingCaller =
        DeliveryBindingCaller(
            recoveryChainId = "recovery-$chain",
            failureId = failure?.let { "recovery-$chain:failure-$it" },
            fetchKey = "fetch-key-$chain",
            extentId = "extent-$chain",
        )

    private class RecordingAdmission : DeliveryBindingRefreshAdmission {
        val admittedIds = CopyOnWriteArrayList<String>()

        override fun admit(refreshCorrelationId: String) {
            admittedIds += refreshCorrelationId
        }
    }

    private class RecordingRefresher(
        private val gate: CompletableDeferred<Unit>? = null,
        private val result: (DeliveryBindingSnapshot, String) -> DeliveryMaterialRefresh,
    ) : DeliveryBindingRefresher {
        val calls = CopyOnWriteArrayList<String>()
        val entered = CompletableDeferred<Unit>()

        override suspend fun refresh(
            current: DeliveryBindingSnapshot,
            refreshCorrelationId: String,
        ): DeliveryMaterialRefresh {
            calls += refreshCorrelationId
            entered.complete(Unit)
            gate?.await()
            return result(current, refreshCorrelationId)
        }
    }

    private data class TestMaterial(val generation: Int) : DeliveryMaterial

    private companion object {
        const val RUN_ID = "run-1"
        const val SESSION_ID = "session-1"
    }
}

private val REVISION_1 = DeliveryBindingRevision("binding-1")
private val REVISION_2 = DeliveryBindingRevision("binding-2")
private val REVISION_3 = DeliveryBindingRevision("binding-3")
private val REVISION_4 = DeliveryBindingRevision("binding-4")
