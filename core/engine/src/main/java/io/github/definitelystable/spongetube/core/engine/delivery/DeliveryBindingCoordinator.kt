package io.github.definitelystable.spongetube.core.engine.delivery

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/**
 * Owns the current delivery binding and the single-flight refresh of mutable
 * provider material (M2.md 12).
 *
 * [current] always returns one immutable [DeliveryBindingSnapshot]. At most one
 * provider operation runs per revision: a caller whose `expected` revision is
 * already current joins the in-flight operation instead of opening a second
 * request. Revisions are monotonic (`binding-1`, `binding-2`, ...) and are
 * never reused and never derived from material. Refresh correlation ids
 * (`refresh-1`, `refresh-2`, ...) are consumed per refresh request so they
 * stay unique even when admission rejects a request.
 *
 * Lock order: this class's `lock` is ALWAYS acquired before a caller's lock.
 * [DeliveryBindingRefreshAdmission.admit] and the evidence listener are
 * invoked while the coordinator lock is held, so a caller must never hold its
 * own lock while calling into this class and an admission/listener must not
 * block on a lock that calls back here.
 *
 * No retry: this class performs at most one provider operation per admitted
 * [refresh] and never restarts it. A failed, incompatible or cancelled
 * operation leaves [current] unchanged; the next [refresh] is a new operation.
 */
internal class DeliveryBindingCoordinator(
    initialMaterial: DeliveryMaterial,
    private val refresher: DeliveryBindingRefresher,
    private val evidence: DeliveryBindingEvidenceListener? = null,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val ownsScope: Boolean = true,
    private val clockNs: () -> Long = { SystemClock.elapsedRealtimeNanos() },
) {
    /** Guards the snapshot, the counters, the in-flight slot and evidence. */
    private val lock = Any()
    private var currentSnapshot = DeliveryBindingSnapshot(
        DeliveryBindingRevision("binding-1"),
        initialMaterial,
    )
    private var revisionCounter = 1L
    private var refreshCounter = 0L
    private var startedOperations = 0
    private var eventSequence = 0L
    private var inFlight: InFlightRefresh? = null
    private var closed = false

    /** Completed by the first [shutdown] once it has fully finished. */
    private val shutdownCompletion = CompletableDeferred<Unit>()

    fun current(): DeliveryBindingSnapshot = synchronized(lock) { currentSnapshot }

    /**
     * Records that [revision] was selected for one physical attempt. It is
     * recorded even after [shutdown]: an attempt admitted while the session
     * closes is still a real request and must stay attributable.
     */
    fun recordSelection(
        caller: DeliveryBindingCaller,
        attemptCorrelationId: String,
        revision: DeliveryBindingRevision,
    ) {
        synchronized(lock) {
            emitLocked(
                caller = caller,
                kind = DeliveryBindingEventKind.BINDING_SELECTED_FOR_ATTEMPT,
                previousRevision = null,
                currentRevision = revision,
                attemptCorrelationId = attemptCorrelationId,
            )
        }
    }

    /**
     * Refreshes the binding from [expected] to the next revision, or joins the
     * caller that already does. Admission runs while the coordinator lock is
     * held and throwing means "not admitted": nothing starts.
     */
    suspend fun refresh(
        expected: DeliveryBindingRevision,
        caller: DeliveryBindingCaller,
        admission: DeliveryBindingRefreshAdmission,
    ): DeliveryBindingRefreshResult {
        val operation: InFlightRefresh
        val initiated: Boolean

        synchronized(lock) {
            emitLocked(
                caller = caller,
                kind = DeliveryBindingEventKind.REFRESH_REQUESTED,
                previousRevision = expected,
                currentRevision = currentSnapshot.revision,
            )
            if (closed) {
                emitLocked(
                    caller = caller,
                    kind = DeliveryBindingEventKind.REFRESH_CLOSED,
                    previousRevision = expected,
                    currentRevision = currentSnapshot.revision,
                )
                return DeliveryBindingRefreshResult.Closed(expected)
            }
            if (currentSnapshot.revision != expected) {
                emitLocked(
                    caller = caller,
                    kind = DeliveryBindingEventKind.REVISION_ALREADY_ADVANCED,
                    previousRevision = expected,
                    currentRevision = currentSnapshot.revision,
                )
                return DeliveryBindingRefreshResult.AlreadyAdvanced(
                    expected,
                    currentSnapshot.revision,
                )
            }

            val existing = inFlight
            if (existing != null) {
                existing.waiters += 1
                emitLocked(
                    caller = caller,
                    kind = DeliveryBindingEventKind.REFRESH_JOINED,
                    previousRevision = expected,
                    currentRevision = currentSnapshot.revision,
                    refreshCorrelationId = existing.refreshCorrelationId,
                )
                operation = existing
                initiated = false
            } else {
                val refreshCorrelationId = "refresh-" + ++refreshCounter
                val admitted = try {
                    admission.admit(refreshCorrelationId)
                    true
                } catch (_: Exception) {
                    false
                }
                if (!admitted) {
                    emitLocked(
                        caller = caller,
                        kind = DeliveryBindingEventKind.REFRESH_NOT_ADMITTED,
                        previousRevision = expected,
                        currentRevision = currentSnapshot.revision,
                        refreshCorrelationId = refreshCorrelationId,
                    )
                    return DeliveryBindingRefreshResult.NotAdmitted(expected)
                }
                val snapshot = currentSnapshot
                val started = InFlightRefresh(expected, refreshCorrelationId, caller)
                emitLocked(
                    caller = caller,
                    kind = DeliveryBindingEventKind.REFRESH_STARTED,
                    previousRevision = expected,
                    currentRevision = snapshot.revision,
                    refreshCorrelationId = refreshCorrelationId,
                )
                // Publish the slot before the operation is started, so a
                // concurrent caller can only join this operation.
                inFlight = started
                startedOperations += 1
                val job = scope.async(start = CoroutineStart.LAZY) {
                    runRefresh(started, snapshot)
                }
                started.job = job
                // A job cancelled before its body began never runs the body's
                // own bookkeeping; finish it exactly once here.
                job.invokeOnCompletion { cause ->
                    if (cause != null) {
                        finishOperation(started, RefreshCompletion.Cancelled)
                    }
                }
                started.waiters = 1
                job.start()
                operation = started
                initiated = true
            }
        }

        val outcome = try {
            operation.result.await()
        } catch (cancelled: CancellationException) {
            abandonWaiter(operation)
            throw cancelled
        }
        return synchronized(lock) {
            when (outcome) {
                DeliveryBindingOperationOutcome.SUCCEEDED -> {
                    val current = checkNotNull(operation.installedRevision) {
                        "successful delivery binding refresh without a revision"
                    }
                    if (initiated) {
                        DeliveryBindingRefreshResult.Refreshed(
                            operation.expected,
                            current,
                            operation.refreshCorrelationId,
                        )
                    } else {
                        DeliveryBindingRefreshResult.JoinedRefresh(
                            operation.expected,
                            current,
                            operation.refreshCorrelationId,
                        )
                    }
                }
                DeliveryBindingOperationOutcome.INCOMPATIBLE ->
                    DeliveryBindingRefreshResult.Incompatible(
                        operation.expected,
                        operation.refreshCorrelationId,
                        initiated,
                    )
                DeliveryBindingOperationOutcome.FAILED,
                DeliveryBindingOperationOutcome.CANCELLED,
                ->
                    DeliveryBindingRefreshResult.Failed(
                        operation.expected,
                        operation.refreshCorrelationId,
                        initiated,
                        cancelled = outcome == DeliveryBindingOperationOutcome.CANCELLED,
                    )
            }
        }
    }

    /**
     * Idempotent and joinable session shutdown: no new operation can start, the
     * in-flight operation is cancelled and joined, and the owned scope is
     * cancelled. A second caller waits for the first. [current] keeps working
     * and returns the last snapshot.
     */
    suspend fun shutdown() {
        val owner = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                true
            }
        }
        if (!owner) {
            shutdownCompletion.await()
            return
        }
        try {
            withContext(NonCancellable) {
                val operation = synchronized(lock) { inFlight }
                if (operation != null) {
                    operation.job.cancel()
                    operation.job.join()
                }
                if (ownsScope) {
                    scope.cancel()
                }
            }
        } finally {
            shutdownCompletion.complete(Unit)
        }
    }

    /** Number of refresh operations that were actually started. */
    internal fun refreshOperationCountForTest(): Int =
        synchronized(lock) { startedOperations }

    // -----------------------------------------------------------------------
    // Operation lifecycle
    // -----------------------------------------------------------------------

    private class InFlightRefresh(
        val expected: DeliveryBindingRevision,
        val refreshCorrelationId: String,
        val caller: DeliveryBindingCaller,
    ) {
        val result = CompletableDeferred<DeliveryBindingOperationOutcome>()
        lateinit var job: Job
        var waiters = 0
        var finished = false
        var installedRevision: DeliveryBindingRevision? = null
    }

    private sealed interface RefreshCompletion {
        data class Material(val material: DeliveryMaterial) : RefreshCompletion
        data object Incompatible : RefreshCompletion
        data object Failed : RefreshCompletion
        data object Cancelled : RefreshCompletion
    }

    /**
     * One actual provider operation. Cancellation is rethrown after finishing
     * the operation, so the caller's chain always continues cancelled while
     * the shared operation is still completed exactly once.
     */
    private suspend fun runRefresh(
        operation: InFlightRefresh,
        snapshot: DeliveryBindingSnapshot,
    ) {
        var completion: RefreshCompletion = RefreshCompletion.Failed
        try {
            completion = when (
                val refreshed = refresher.refresh(
                    snapshot,
                    operation.refreshCorrelationId,
                )
            ) {
                is DeliveryMaterialRefresh.Material ->
                    RefreshCompletion.Material(refreshed.material)
                DeliveryMaterialRefresh.Incompatible -> RefreshCompletion.Incompatible
                DeliveryMaterialRefresh.Failed -> RefreshCompletion.Failed
            }
        } catch (cancelled: CancellationException) {
            completion = RefreshCompletion.Cancelled
            throw cancelled
        } catch (_: Exception) {
            // Any other provider exception is one failed operation, never a
            // retry. A fatal Error still reaches the finally below, so the
            // slot can never stay dirty.
            completion = RefreshCompletion.Failed
        } finally {
            withContext(NonCancellable) { finishOperation(operation, completion) }
        }
    }

    /**
     * Exactly-once completion: assigns the next revision on success, records
     * the completion event with the initiator's fields and clears the
     * in-flight slot.
     */
    private fun finishOperation(
        operation: InFlightRefresh,
        completion: RefreshCompletion,
    ) {
        synchronized(lock) {
            if (operation.finished) return
            operation.finished = true
            if (inFlight === operation) {
                inFlight = null
            }
            when (completion) {
                is RefreshCompletion.Material -> {
                    revisionCounter += 1
                    val revision = DeliveryBindingRevision("binding-$revisionCounter")
                    operation.installedRevision = revision
                    currentSnapshot = DeliveryBindingSnapshot(revision, completion.material)
                    emitLocked(
                        caller = operation.caller,
                        kind = DeliveryBindingEventKind.REFRESH_SUCCEEDED,
                        previousRevision = operation.expected,
                        currentRevision = revision,
                        refreshCorrelationId = operation.refreshCorrelationId,
                        outcome = DeliveryBindingOperationOutcome.SUCCEEDED,
                    )
                    operation.result.complete(DeliveryBindingOperationOutcome.SUCCEEDED)
                }
                RefreshCompletion.Incompatible -> {
                    emitLocked(
                        caller = operation.caller,
                        kind = DeliveryBindingEventKind.REFRESH_INCOMPATIBLE,
                        previousRevision = operation.expected,
                        currentRevision = operation.expected,
                        refreshCorrelationId = operation.refreshCorrelationId,
                        outcome = DeliveryBindingOperationOutcome.INCOMPATIBLE,
                    )
                    operation.result.complete(DeliveryBindingOperationOutcome.INCOMPATIBLE)
                }
                RefreshCompletion.Failed -> {
                    emitLocked(
                        caller = operation.caller,
                        kind = DeliveryBindingEventKind.REFRESH_FAILED,
                        previousRevision = operation.expected,
                        currentRevision = operation.expected,
                        refreshCorrelationId = operation.refreshCorrelationId,
                        outcome = DeliveryBindingOperationOutcome.FAILED,
                    )
                    operation.result.complete(DeliveryBindingOperationOutcome.FAILED)
                }
                RefreshCompletion.Cancelled -> {
                    emitLocked(
                        caller = operation.caller,
                        kind = DeliveryBindingEventKind.REFRESH_CANCELLED,
                        previousRevision = operation.expected,
                        currentRevision = operation.expected,
                        refreshCorrelationId = operation.refreshCorrelationId,
                        outcome = DeliveryBindingOperationOutcome.CANCELLED,
                    )
                    operation.result.complete(DeliveryBindingOperationOutcome.CANCELLED)
                }
            }
        }
    }

    /**
     * One waiting caller left before the operation finished. The last one
     * cancels and joins the operation inside [NonCancellable], so the
     * cancelled caller never leaves an orphan operation behind and the
     * completion event is recorded before its cancellation is rethrown.
     */
    private suspend fun abandonWaiter(operation: InFlightRefresh) {
        val cancel = synchronized(lock) {
            operation.waiters -= 1
            if (operation.waiters <= 0 && !operation.finished) {
                operation.job.cancel()
                true
            } else {
                false
            }
        }
        if (cancel) {
            withContext(NonCancellable) { operation.job.join() }
        }
    }

    // -----------------------------------------------------------------------
    // Evidence
    // -----------------------------------------------------------------------

    /** The caller must hold [lock]; the listener is invoked under it. */
    private fun emitLocked(
        caller: DeliveryBindingCaller,
        kind: DeliveryBindingEventKind,
        previousRevision: DeliveryBindingRevision?,
        currentRevision: DeliveryBindingRevision?,
        attemptCorrelationId: String? = null,
        refreshCorrelationId: String? = null,
        outcome: DeliveryBindingOperationOutcome? = null,
    ) {
        val listener = evidence ?: return
        eventSequence += 1
        val event = DeliveryBindingEvent(
            sequence = eventSequence,
            elapsedRealtimeNs = clockNs(),
            recoveryChainId = caller.recoveryChainId,
            failureId = caller.failureId,
            fetchKey = caller.fetchKey,
            extentId = caller.extentId,
            kind = kind,
            previousRevision = previousRevision,
            currentRevision = currentRevision,
            attemptCorrelationId = attemptCorrelationId,
            refreshCorrelationId = refreshCorrelationId,
            outcome = outcome,
        )
        runCatching { listener.onDeliveryBindingEvent(event) }
    }
}
