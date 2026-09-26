package io.github.definitelystable.spongetube.core.engine.recovery

import android.os.SystemClock
import io.github.definitelystable.spongetube.core.engine.FetchAcquireDisposition
import io.github.definitelystable.spongetube.core.engine.FetchAttemptAdmission
import io.github.definitelystable.spongetube.core.engine.FetchBroker
import io.github.definitelystable.spongetube.core.engine.FetchConsumer
import io.github.definitelystable.spongetube.core.engine.FetchConsumerId
import io.github.definitelystable.spongetube.core.engine.FetchConsumerKind
import io.github.definitelystable.spongetube.core.engine.FetchHandle
import io.github.definitelystable.spongetube.core.engine.FetchIdentityConflictException
import io.github.definitelystable.spongetube.core.engine.FetchKey
import io.github.definitelystable.spongetube.core.engine.FetchOutcome
import io.github.definitelystable.spongetube.core.engine.FetchPriority
import io.github.definitelystable.spongetube.core.engine.FetchRequest
import io.github.definitelystable.spongetube.core.engine.attemptCorrelationId
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingCaller
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingCoordinator
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingRefreshAdmission
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingRefreshResult
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingRevision
import io.github.definitelystable.spongetube.core.engine.delivery.ProviderWallClock
import io.github.definitelystable.spongetube.core.engine.delivery.RetryAfter
import io.github.definitelystable.spongetube.core.engine.delivery.RetryAfterKind
import io.github.definitelystable.spongetube.core.engine.delivery.RetryAfterObservation
import io.github.definitelystable.spongetube.core.storage.CommittedExtent
import io.github.definitelystable.spongetube.core.storage.ExtentSpec
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/**
 * The only logical retry owner (M2-C / M2-D, ADR-0003).
 *
 * ```text
 * Playback / Reserve demand
 *   -> RecoveryCoordinator (one open RecoveryChain per immutable FetchKey)
 *        attempt gate -> budget admission -> FetchBroker owner (one attempt,
 *        carrying the delivery binding selected for that owner)
 *        -> raw observation -> FailureClassifier -> RecoveryPolicy -> action
 * ```
 *
 * Executed actions beyond a plain retry are the server-directed provider wait
 * (`Retry-After`, as-is and free of charge) and one delivery-binding refresh
 * outside the coordinator lock; the executed refresh result is recorded on the
 * failure row. Neither FetchBroker, the transport executor nor Media3 decide
 * to try again. Consumers join the chain; the coordinator is the only
 * FetchBroker consumer (`recovery:<chainId>:<ownerOrdinal>`). A new owner,
 * backoff, priority escalation, attempt-gate wait, provider wait or binding
 * refresh never grants fresh budget: the chain ledger is charged exactly once
 * per physical attempt, inside the broker owner, immediately before the
 * request, and once per ACTUAL provider refresh operation.
 *
 * The delivery binding of an owner is execution context, never identity: it is
 * selected outside the coordinator lock and a join never changes it. Lock
 * order is always DeliveryBindingCoordinator -> RecoveryCoordinator.
 */
internal class RecoveryCoordinator(
    private val broker: FetchBroker,
    private val sessionId: String,
    val policy: RecoveryPolicy = RecoveryPolicy.DEFAULT,
    private val attemptGate: RecoveryAttemptGate = RecoveryAttemptGate.ALWAYS_PERMIT,
    private val reconciler: RecoveryLocalReconciler? = null,
    private val bindings: DeliveryBindingCoordinator? = null,
    private val providerWallClock: ProviderWallClock = ProviderWallClock.SYSTEM,
    private val jitter: RecoveryJitterSource = RecoveryJitterSource.RANDOM,
    private val sleeper: RecoverySleeper = RecoverySleeper.COROUTINE_DELAY,
    private val evidence: RecoveryEvidenceListener? = null,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val ownsScope: Boolean = true,
    private val clockNs: () -> Long = { SystemClock.elapsedRealtimeNanos() },
) {
    /** Guards every chain transition and the evidence sequence. */
    private val lock = Any()
    private val activeChains = mutableMapOf<FetchKey, RecoveryChain>()
    private val chainCounter = AtomicLong()
    private val budgetSequence = AtomicLong()
    private val failureSequence = AtomicLong()

    @Volatile
    private var closing = false

    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
    }

    /**
     * Joins the open chain for [request]'s FetchKey or starts one. The same
     * FetchKey with a different immutable ExtentSpec fails closed.
     */
    suspend fun acquire(
        request: FetchRequest,
        consumer: RecoveryConsumer,
    ): RecoveryHandle {
        var created: RecoveryChain? = null
        var handleToRaise: FetchHandle? = null
        val chain: RecoveryChain
        val disposition: RecoveryAcquireDisposition

        synchronized(lock) {
            check(!closing) { "recovery coordinator is closed" }
            val existing = activeChains[request.fetchKey]
            if (existing == null) {
                chain = RecoveryChain(
                    id = RecoveryChainId("recovery-" + chainCounter.incrementAndGet()),
                    request = request,
                    policy = policy,
                    initialPriority = consumer.kind.priority,
                )
                chain.consumers[consumer.id] = consumer
                val job = scope.launch(start = CoroutineStart.LAZY) { drive(chain) }
                chain.job = job
                activeChains[request.fetchKey] = chain
                created = chain
                disposition = RecoveryAcquireDisposition.NEW_CHAIN
                emitBudget(chain, RecoveryBudgetEventKind.CHAIN_STARTED)
                emitBudget(
                    chain,
                    RecoveryBudgetEventKind.CONSUMER_JOINED,
                    consumerId = consumer.id.value,
                    consumerKind = consumer.kind,
                )
                // Register only after the chain and its start evidence are
                // published. If the parent scope was already cancelled,
                // invokeOnCompletion may run synchronously here; the monitor
                // is re-entrant and onDriverCompleted can then remove the
                // correctly-published chain without creating an orphan.
                job.invokeOnCompletion { onDriverCompleted(chain) }
            } else {
                if (!existing.request.extentSpec.isSameWorkAs(request.extentSpec)) {
                    throw FetchIdentityConflictException(
                        "same FetchKey was acquired with a different immutable " +
                            "ExtentSpec: " + request.fetchKey,
                    )
                }
                if (existing.consumers.containsKey(consumer.id)) {
                    throw FetchIdentityConflictException(
                        "consumer id already joined recovery chain: " + consumer.id,
                    )
                }
                chain = existing
                chain.consumers[consumer.id] = consumer
                chain.stopWaiting.value = false
                disposition = if (chain.state == RecoveryChainState.CANCELLING) {
                    RecoveryAcquireDisposition.JOINED_CANCELLING
                } else {
                    RecoveryAcquireDisposition.JOINED_ACTIVE
                }
                emitBudget(
                    chain,
                    RecoveryBudgetEventKind.CONSUMER_JOINED,
                    consumerId = consumer.id.value,
                    consumerKind = consumer.kind,
                )
                val requested = consumer.kind.priority
                if (requested.ordinal > chain.effectivePriority.ordinal) {
                    val before = chain.effectivePriority
                    chain.effectivePriority = requested
                    handleToRaise = chain.brokerHandle
                    // Escalation changes priority only: the ledger, the
                    // current owner and its attempt are untouched.
                    emitBudget(
                        chain,
                        RecoveryBudgetEventKind.PRIORITY_RAISED,
                        priorityBefore = before.name,
                    )
                }
            }
        }

        handleToRaise?.raisePriority(consumer.kind.priority)
        if (created != null) {
            // The driver Job was installed before the chain became visible to
            // shutdown/acquire. Starting it here is race-safe: shutdown can
            // always observe and join the Job even if this coroutine is
            // descheduled between publication and start.
            checkNotNull(chain.job).start()
        }

        val handle = Handle(chain, consumer.id, disposition)
        try {
            chain.settled.await()
        } catch (cancelled: CancellationException) {
            handle.close()
            throw cancelled
        }
        handle.fetchIdAtAcquire = synchronized(lock) { chain.lastFetchId }
        return handle
    }

    /**
     * Session shutdown: stop admitting chains, terminate every open chain as
     * SESSION_TERMINATION (closing its current broker handle and waking any
     * wait, including a provider wait or refresh await), settle the delivery
     * binding coordinator and wait for every chain. FetchBroker is shut down
     * afterwards by the runtime; no chain starts an attempt after this
     * returns.
     */
    private val shutdownCompletion = CompletableDeferred<Unit>()

    suspend fun shutdown() {
        var chains: List<RecoveryChain> = emptyList()
        val handles = mutableListOf<FetchHandle>()
        val owner = synchronized(lock) {
            if (closing) {
                false
            } else {
                closing = true
                true
            }
        }
        if (!owner) {
            shutdownCompletion.await()
            return
        }

        try {
            withContext(NonCancellable) {
                synchronized(lock) {
                    chains = activeChains.values.toList()
                    chains.forEach { chain ->
                        chain.stopWaiting.value = true
                        chain.brokerHandle?.let(handles::add)
                    }
                }
                handles.forEach(FetchHandle::close)
                // Lock order stays DeliveryBindingCoordinator -> Recovery
                // Coordinator: no coordinator lock is held here. Closing the
                // binding coordinator after the chains were stopped leaves no
                // refresh operation running when this returns.
                bindings?.shutdown()
                chains.forEach { chain -> chain.job?.join() }
                if (ownsScope) {
                    scope.cancel()
                }
            }
        } finally {
            shutdownCompletion.complete(Unit)
        }
    }

    internal fun activeChainCountForTest(): Int =
        synchronized(lock) { activeChains.size }

    /**
     * Last-resort lifecycle barrier. A lazy driver can be cancelled before its
     * body runs, and a fatal Error may escape [drive]. Neither is allowed to
     * leave a published RecoveryChain with unresolved settled/result deferreds.
     */
    private fun onDriverCompleted(chain: RecoveryChain) {
        val outcome = synchronized(lock) {
            if (chain.state == RecoveryChainState.TERMINAL) {
                null
            } else {
                terminateLocked(
                    chain,
                    if (closing) {
                        RecoveryTerminalReason.SESSION_TERMINATION
                    } else if (chain.consumers.isEmpty()) {
                        RecoveryTerminalReason.NO_REMAINING_DEMAND
                    } else {
                        RecoveryTerminalReason.TERMINAL_FAILURE
                    },
                )
            }
        }
        if (outcome != null) {
            complete(chain, outcome)
        }
    }

    // -----------------------------------------------------------------------
    // Chain driver
    // -----------------------------------------------------------------------

    private suspend fun drive(chain: RecoveryChain) {
        try {
            while (true) {
                if (terminateIfNoAttemptPossible(chain, checkBudget = false)) {
                    return
                }

                setState(chain, RecoveryChainState.WAITING_ATTEMPT_PERMIT)
                emitBudget(chain, RecoveryBudgetEventKind.ATTEMPT_PERMIT_WAIT)
                val permit = awaitWhileDemanded(chain) {
                    attemptGate.awaitPermit(chain.id)
                } ?: continue
                synchronized(lock) { chain.lastRouteEpoch = permit.routeEpoch }
                emitBudget(
                    chain,
                    RecoveryBudgetEventKind.ATTEMPT_PERMIT_GRANTED,
                    permit = permit,
                )

                if (terminateIfNoAttemptPossible(chain, checkBudget = true)) {
                    return
                }
                val ownerOrdinal: Int
                val consumerKind: FetchConsumerKind
                synchronized(lock) {
                    chain.state = RecoveryChainState.ACTIVE
                    chain.ownerOrdinal += 1
                    ownerOrdinal = chain.ownerOrdinal
                    consumerKind = chain.effectivePriority.consumerKind
                }

                val outcome = runOwner(chain, ownerOrdinal, consumerKind)
                    ?: return
                if (outcome.isSuccess) {
                    finish(
                        chain,
                        RecoveryTerminalReason.SUCCESS,
                        committedExtent = outcome.committedExtent,
                    )
                    return
                }
                val observation = checkNotNull(outcome.failure) {
                    "failed owner without observation"
                }
                val next = decideAndAct(chain, outcome, observation)
                if (next == Next.TERMINATED) {
                    return
                }
            }
        } catch (cancelled: CancellationException) {
            finish(chain, RecoveryTerminalReason.SESSION_TERMINATION)
            throw cancelled
        } catch (_: Exception) {
            recordInternalFailure(chain)
        }
    }

    /** Opens one owner and waits for its terminal; null if the chain ended. */
    private suspend fun runOwner(
        chain: RecoveryChain,
        ownerOrdinal: Int,
        consumerKind: FetchConsumerKind,
    ): FetchOutcome? {
        val admitted = AtomicBoolean()
        // The binding is execution context, never identity: it is read outside
        // the coordinator lock and passed to the broker owner.
        val binding = bindings?.current()
        synchronized(lock) { chain.lastSelectedBinding = binding?.revision }
        val handle = try {
            broker.acquire(
                request = chain.request,
                consumer = FetchConsumer(
                    FetchConsumerId("recovery:${chain.id}:$ownerOrdinal"),
                    consumerKind,
                ),
                admission = FetchAttemptAdmission { fetchId, attemptCorrelationId ->
                    val charge = chain.ledger.charge(
                        RecoveryBudgetDimension.REMOTE_ATTEMPT,
                    )
                    admitted.set(true)
                    emitBudget(
                        chain,
                        RecoveryBudgetEventKind.CHARGE,
                        charge = charge,
                        ownerOrdinal = ownerOrdinal,
                        fetchId = fetchId.value,
                        attemptCorrelationId = attemptCorrelationId,
                    )
                    emitBudget(
                        chain,
                        RecoveryBudgetEventKind.OWNER_STARTED,
                        ownerOrdinal = ownerOrdinal,
                        fetchId = fetchId.value,
                        attemptCorrelationId = attemptCorrelationId,
                    )
                    if (binding != null) {
                        // The failure snapshot is taken under the coordinator
                        // lock; recordSelection is never called while holding
                        // it (lock order is binding -> recovery).
                        val failureId = synchronized(lock) { chain.lastFailureId }
                        bindings.recordSelection(
                            DeliveryBindingCaller(
                                recoveryChainId = chain.id.value,
                                failureId = failureId,
                                fetchKey = chain.fetchKey.value,
                                extentId = chain.request.extentSpec.extentId.value,
                            ),
                            attemptCorrelationId,
                            binding.revision,
                        )
                    }
                },
                deliveryBinding = binding,
            )
        } catch (closedBroker: IllegalStateException) {
            if (closing) {
                finish(chain, RecoveryTerminalReason.SESSION_TERMINATION)
                return null
            }
            throw closedBroker
        }

        if (handle.acquireDisposition != FetchAcquireDisposition.NEW_OWNER) {
            // The chain registry guarantees owners never overlap; anything
            // else is a broken invariant and fails closed.
            handle.close()
            error("recovery chain did not receive a new FetchBroker owner")
        }

        val closeNow: Boolean
        val raiseTo: FetchPriority?
        synchronized(lock) {
            chain.brokerHandle = handle
            chain.lastFetchId = handle.fetchId
            closeNow = closing || chain.consumers.isEmpty()
            if (closeNow && chain.state == RecoveryChainState.ACTIVE) {
                chain.state = RecoveryChainState.CANCELLING
            }
            raiseTo = chain.effectivePriority
                .takeIf { it.ordinal > consumerKind.priority.ordinal }
        }
        chain.settled.complete(Unit)
        raiseTo?.let(handle::raisePriority)
        if (closeNow) {
            handle.close()
        }

        // The chain's lease may be released at any time by the last consumer
        // leaving (release()); the chain still waits for the physical owner
        // to be terminal before deciding anything (M1 cancellation barrier).
        val outcome = try {
            handle.awaitTerminal()
        } finally {
            handle.close()
            synchronized(lock) {
                if (chain.brokerHandle === handle) {
                    chain.brokerHandle = null
                }
            }
        }
        synchronized(lock) { chain.lastFetchOutcome = outcome.kind }
        emitBudget(
            chain,
            RecoveryBudgetEventKind.OWNER_FINISHED,
            ownerOrdinal = ownerOrdinal,
            fetchId = handle.fetchId.value,
            attemptCorrelationId = if (admitted.get()) {
                attemptCorrelationId(handle.fetchId)
            } else {
                null
            },
            ownerOutcome = outcome.kind.name,
        )
        return outcome
    }

    private enum class Next { CONTINUE, TERMINATED }

    private suspend fun decideAndAct(
        chain: RecoveryChain,
        outcome: FetchOutcome,
        observation: FailureObservation,
    ): Next {
        val classification = FailureClassifier.classify(observation)

        // Demand-dependent decisions and their terminal transition happen in
        // one critical section, so a consumer joining concurrently either is
        // counted as demand or joins a new chain; it never receives a
        // NO_REMAINING_DEMAND that it did not cause. Executable refreshes run
        // outside the lock; a provider wait is only stored here.
        var reconcileWith: Pending? = null
        var refreshWith: Pending? = null
        val immediate: Next? = synchronized(lock) {
            val pending = pendingFailure(chain, outcome, observation, classification)
            val action = if (
                pending.decision.kind == RecoveryDecisionKind.RECONCILE_LOCAL_COVERAGE
            ) {
                null
            } else {
                actionFor(chain, pending)
            }
            when {
                action == null -> {
                    reconcileWith = pending
                    null
                }
                // The executable-refresh marker carries no result yet: the
                // operation runs outside the lock and is applied afterwards.
                action.kind == RecoveryActionKind.REFRESH_DELIVERY_BINDING &&
                    action.deliveryBinding == null -> {
                    refreshWith = pending
                    null
                }
                else -> applyLocked(chain, pending, action)
            }
        }
        if (immediate == Next.TERMINATED) {
            complete(chain, checkNotNull(chain.terminalOutcome))
            return Next.TERMINATED
        }

        val pending = reconcileWith
        if (pending != null) {
            val reconciliation = reconcile(chain.request)
            val action = RecoveryActionRecord(
                kind = when (reconciliation) {
                    LocalReconciliation.COVERAGE_PRESENT ->
                        RecoveryActionKind.LOCAL_COVERAGE_READY
                    LocalReconciliation.IDENTITY_CONFLICT ->
                        RecoveryActionKind.FAIL_CLOSED_IDENTITY_CONFLICT
                    LocalReconciliation.ABSENT,
                    LocalReconciliation.FAILED,
                    -> RecoveryActionKind.TERMINATE_FAILURE
                },
                reconciliation = reconciliation,
            )
            val next = synchronized(lock) { applyLocked(chain, pending, action) }
            if (next == Next.TERMINATED) {
                complete(chain, checkNotNull(chain.terminalOutcome))
            }
            return next
        }

        val pendingRefresh = refreshWith
        if (pendingRefresh != null) {
            return executeRefresh(chain, pendingRefresh)
        }

        // A decided provider wait runs outside the lock and spends nothing:
        // no budget event and no extra backoff on top of `Retry-After`.
        val providerWait = synchronized(lock) {
            chain.pendingProviderWait.also { chain.pendingProviderWait = null }
        }
        if (providerWait != null) {
            awaitWhileDemanded(chain) { sleeper.sleep(providerWait.first) }
            return Next.CONTINUE
        }

        // SCHEDULE_BACKOFF or START_NEXT_OWNER.
        val backoff = synchronized(lock) { chain.pendingBackoff.also { chain.pendingBackoff = null } }
        if (backoff != null) {
            val completed = awaitWhileDemanded(chain) { sleeper.sleep(backoff.first.delayMs) }
            if (completed != null) {
                synchronized(lock) {
                    if (chain.state != RecoveryChainState.TERMINAL) {
                        emitBudget(
                            chain,
                            RecoveryBudgetEventKind.BACKOFF_COMPLETED,
                            backoff = backoff.first,
                            failureId = backoff.second,
                        )
                    }
                }
            }
        }
        return Next.CONTINUE
    }

    /**
     * One executed delivery-binding refresh, outside the coordinator lock
     * (lock order is DeliveryBindingCoordinator -> RecoveryCoordinator). The
     * failure-decision row is emitted after the operation returns, carrying
     * its result.
     */
    private suspend fun executeRefresh(
        chain: RecoveryChain,
        pending: Pending,
    ): Next {
        val expected = checkNotNull(
            (pending.observation as? FailureObservation.HttpResponse)
                ?.deliveryBindingRevision,
        ) { "an executable refresh carries the selected revision" }
        val coordinator = checkNotNull(bindings) {
            "an executable refresh has a delivery binding coordinator"
        }
        setState(chain, RecoveryChainState.REFRESHING_BINDING)
        val charged = AtomicBoolean()
        val admission = DeliveryBindingRefreshAdmission {
            val charge = chain.ledger.charge(
                RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH,
            )
            charged.set(true)
            emitBudget(
                chain,
                RecoveryBudgetEventKind.CHARGE,
                charge = charge,
                failureId = pending.failureId,
            )
        }
        // null means the chain lost its demand or the session closed while
        // awaiting: the refresh is abandoned and the loop re-checks state. A
        // result that did arrive is recorded as it is, even when the chain is
        // being stopped, so the failure row never contradicts the
        // delivery-binding evidence; an operation cancelled by shutdown (or
        // by every waiter leaving) is recorded as ABANDONED.
        val result = awaitWhileDemanded(chain) {
            coordinator.refresh(
                expected = expected,
                caller = DeliveryBindingCaller(
                    recoveryChainId = chain.id.value,
                    failureId = pending.failureId,
                    fetchKey = chain.fetchKey.value,
                    extentId = chain.request.extentSpec.extentId.value,
                ),
                admission = admission,
            )
        }
        val action = RecoveryActionRecord(
            kind = RecoveryActionKind.REFRESH_DELIVERY_BINDING,
            exhaustedDimension = if (result is DeliveryBindingRefreshResult.NotAdmitted) {
                RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH
            } else {
                null
            },
            deliveryBinding = deliveryBindingRecord(expected, result, charged.get()),
        )
        val next = synchronized(lock) { applyLocked(chain, pending, action) }
        if (next == Next.TERMINATED) {
            complete(chain, checkNotNull(chain.terminalOutcome))
        }
        return next
    }

    private fun deliveryBindingRecord(
        expected: DeliveryBindingRevision,
        result: DeliveryBindingRefreshResult?,
        charged: Boolean,
    ): DeliveryBindingActionRecord = when (result) {
        null -> DeliveryBindingActionRecord(
            expectedRevision = expected,
            result = DeliveryBindingActionResult.ABANDONED,
            currentRevision = null,
            refreshCorrelationId = null,
            charged = charged,
        )
        is DeliveryBindingRefreshResult.Refreshed -> DeliveryBindingActionRecord(
            expectedRevision = expected,
            result = DeliveryBindingActionResult.REFRESHED,
            currentRevision = result.current,
            refreshCorrelationId = result.refreshCorrelationId,
            charged = charged,
        )
        is DeliveryBindingRefreshResult.AlreadyAdvanced -> DeliveryBindingActionRecord(
            expectedRevision = expected,
            result = DeliveryBindingActionResult.ALREADY_ADVANCED,
            currentRevision = result.current,
            refreshCorrelationId = null,
            charged = charged,
        )
        is DeliveryBindingRefreshResult.JoinedRefresh -> DeliveryBindingActionRecord(
            expectedRevision = expected,
            result = DeliveryBindingActionResult.JOINED_REFRESH,
            currentRevision = result.current,
            refreshCorrelationId = result.refreshCorrelationId,
            charged = charged,
        )
        is DeliveryBindingRefreshResult.Incompatible -> DeliveryBindingActionRecord(
            expectedRevision = expected,
            result = DeliveryBindingActionResult.INCOMPATIBLE,
            currentRevision = null,
            refreshCorrelationId = result.refreshCorrelationId,
            charged = charged,
        )
        is DeliveryBindingRefreshResult.Failed -> DeliveryBindingActionRecord(
            expectedRevision = expected,
            result = if (result.cancelled) {
                DeliveryBindingActionResult.ABANDONED
            } else {
                DeliveryBindingActionResult.FAILED
            },
            currentRevision = null,
            refreshCorrelationId = result.refreshCorrelationId,
            charged = charged,
        )
        is DeliveryBindingRefreshResult.NotAdmitted -> DeliveryBindingActionRecord(
            expectedRevision = expected,
            result = DeliveryBindingActionResult.NOT_ADMITTED,
            currentRevision = null,
            refreshCorrelationId = null,
            charged = charged,
        )
        is DeliveryBindingRefreshResult.Closed -> DeliveryBindingActionRecord(
            expectedRevision = expected,
            result = DeliveryBindingActionResult.CLOSED,
            currentRevision = null,
            refreshCorrelationId = null,
            charged = charged,
        )
    }

    private class Pending(
        val failureId: String,
        val fetchId: String?,
        val attemptCorrelationId: String?,
        val routeEpoch: Long?,
        val observation: FailureObservation,
        val classification: FailureClassification,
        val context: RecoveryDecisionContext,
        val decision: RecoveryDecision,
    )

    private fun pendingFailure(
        chain: RecoveryChain,
        outcome: FetchOutcome?,
        observation: FailureObservation,
        classification: FailureClassification,
    ): Pending {
        val context = RecoveryDecisionContext(
            demandPresent = chain.consumers.isNotEmpty(),
            sessionClosing = closing,
            remoteAttemptsRemaining =
                chain.ledger.remaining(RecoveryBudgetDimension.REMOTE_ATTEMPT),
            deliveryBindingRefreshesRemaining =
                deliveryBindingRefreshesRemaining(chain),
        )
        chain.failureOrdinal += 1
        val failureId = "${chain.id}:failure-${chain.failureOrdinal}"
        chain.lastFailureId = failureId
        val fetchId = chain.lastFetchId
        return Pending(
            failureId = failureId,
            fetchId = fetchId?.takeIf { outcome != null }?.value,
            attemptCorrelationId = fetchId
                ?.takeIf { outcome != null && outcome.attempts > 0 }
                ?.let { attemptCorrelationId(it) },
            routeEpoch = chain.lastRouteEpoch,
            observation = observation,
            classification = classification,
            context = context,
            decision = policy.decide(classification, observation, context),
        )
    }

    /**
     * Remaining DELIVERY_BINDING_REFRESH charges when the policy declares the
     * dimension; 0 when it does not (M2.md 20, M2-D).
     */
    private fun deliveryBindingRefreshesRemaining(chain: RecoveryChain): Int {
        val dimension = RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH
        return if (policy.budget.limit(dimension) != null) {
            chain.ledger.remaining(dimension)
        } else {
            0
        }
    }

    /**
     * Executed action for every decision except RECONCILE_LOCAL_COVERAGE and
     * an executable REFRESH_DELIVERY_BINDING, which run outside the lock.
     */
    private fun actionFor(
        chain: RecoveryChain,
        pending: Pending,
    ): RecoveryActionRecord {
        val remaining = pending.context.remoteAttemptsRemaining
        return when (pending.decision.kind) {
            RecoveryDecisionKind.RETRY_AFTER_BACKOFF ->
                if (remaining == 0) {
                    RecoveryActionRecord(
                        kind = RecoveryActionKind.TERMINATE_BUDGET_EXHAUSTED,
                        exhaustedDimension = RecoveryBudgetDimension.REMOTE_ATTEMPT,
                    )
                } else {
                    chain.retryOrdinal += 1
                    RecoveryActionRecord(
                        kind = RecoveryActionKind.SCHEDULE_BACKOFF,
                        delayMs = policy.backoff.delayMs(chain.retryOrdinal, jitter),
                        retryOrdinal = chain.retryOrdinal,
                    )
                }
            RecoveryDecisionKind.CONTINUE_FOR_DEMAND,
            RecoveryDecisionKind.WAIT_FOR_ROUTE,
            ->
                if (remaining == 0) {
                    RecoveryActionRecord(
                        kind = RecoveryActionKind.TERMINATE_BUDGET_EXHAUSTED,
                        exhaustedDimension = RecoveryBudgetDimension.REMOTE_ATTEMPT,
                    )
                } else {
                    // The next iteration waits for the attempt gate first.
                    RecoveryActionRecord(RecoveryActionKind.START_NEXT_OWNER)
                }
            RecoveryDecisionKind.WAIT_UNTIL_PROVIDER -> waitUntilProvider(pending)
            RecoveryDecisionKind.REFRESH_DELIVERY_BINDING -> refreshDecision(chain, pending)
            RecoveryDecisionKind.RERESOLVE_PROVIDER ->
                // Re-resolve semantics are not defined by #50; stays
                // fail-closed instead of becoming a retry.
                RecoveryActionRecord(RecoveryActionKind.FAIL_CLOSED_ACTION_UNAVAILABLE)
            RecoveryDecisionKind.FAIL_TERMINAL ->
                RecoveryActionRecord(RecoveryActionKind.TERMINATE_FAILURE)
            RecoveryDecisionKind.COMPLETE_NO_DEMAND ->
                RecoveryActionRecord(RecoveryActionKind.TERMINATE_NO_DEMAND)
            RecoveryDecisionKind.COMPLETE_SESSION ->
                RecoveryActionRecord(RecoveryActionKind.TERMINATE_SESSION)
            RecoveryDecisionKind.RECONCILE_LOCAL_COVERAGE ->
                error("reconciliation is executed outside the lock")
        }
    }

    /**
     * `WAIT_UNTIL_PROVIDER`: exhaustion of the remote-attempt dimension is
     * decided first, then the `Retry-After` observation is turned into a
     * provider wait inside PROVIDER_WALL_CLOCK. ABSENT/MALFORMED cannot reach
     * this action from the v2 policy and fail closed defensively.
     */
    private fun waitUntilProvider(pending: Pending): RecoveryActionRecord {
        if (pending.context.remoteAttemptsRemaining == 0) {
            return RecoveryActionRecord(
                kind = RecoveryActionKind.TERMINATE_BUDGET_EXHAUSTED,
                exhaustedDimension = RecoveryBudgetDimension.REMOTE_ATTEMPT,
            )
        }
        val retryAfter = (pending.observation as? FailureObservation.HttpResponse)
            ?.retryAfter
            ?: RetryAfterObservation.ABSENT
        val nowUtcEpochMs = providerWallClock.nowUtcEpochMs()
        val waitMs = RetryAfter.waitMs(retryAfter, nowUtcEpochMs)
            ?: return RecoveryActionRecord(RecoveryActionKind.FAIL_CLOSED_ACTION_UNAVAILABLE)
        val httpDate = retryAfter.rawKind == RetryAfterKind.HTTP_DATE
        return RecoveryActionRecord(
            kind = RecoveryActionKind.WAIT_PROVIDER,
            providerWait = ProviderWaitRecord(
                rawKind = retryAfter.rawKind,
                waitMs = waitMs,
                notBeforeUtcEpochMs = if (httpDate) retryAfter.notBeforeUtcEpochMs else null,
                wallClockNowUtcEpochMs = if (httpDate) nowUtcEpochMs else null,
            ),
        )
    }

    /**
     * `REFRESH_DELIVERY_BINDING`: the REMOTE_ATTEMPT dimension is checked
     * first, then binding availability. The refresh budget is intentionally
     * NOT checked here: an exhausted chain must still be able to observe
     * ALREADY_ADVANCED or JOINED_REFRESH, both of which are zero-charge paths.
     * Only the actual refresh initiator reaches DeliveryBindingRefreshAdmission,
     * where DELIVERY_BINDING_REFRESH is charged atomically; exhaustion there
     * becomes NOT_ADMITTED / BUDGET_EXHAUSTED.
     */
    private fun refreshDecision(chain: RecoveryChain, pending: Pending): RecoveryActionRecord {
        if (pending.context.remoteAttemptsRemaining == 0) {
            return RecoveryActionRecord(
                kind = RecoveryActionKind.TERMINATE_BUDGET_EXHAUSTED,
                exhaustedDimension = RecoveryBudgetDimension.REMOTE_ATTEMPT,
            )
        }
        if (!refreshExecutable(chain, pending)) {
            return RecoveryActionRecord(RecoveryActionKind.FAIL_CLOSED_ACTION_UNAVAILABLE)
        }
        return RecoveryActionRecord(RecoveryActionKind.REFRESH_DELIVERY_BINDING)
    }

    /**
     * True when a refresh is available: a binding coordinator participates,
     * the policy declares the dimension, and the failed owner's response
     * carries exactly the revision the coordinator selected for that owner.
     */
    private fun refreshExecutable(chain: RecoveryChain, pending: Pending): Boolean {
        if (bindings == null) {
            return false
        }
        if (policy.budget.limit(RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH) == null) {
            return false
        }
        val response = pending.observation as? FailureObservation.HttpResponse
            ?: return false
        val revision = response.deliveryBindingRevision ?: return false
        return revision == chain.lastSelectedBinding
    }

    /** Records the four failure layers, then executes a terminal or a wait. */
    private fun applyLocked(
        chain: RecoveryChain,
        pending: Pending,
        action: RecoveryActionRecord,
    ): Next {
        emitFailure(
            FailureDecisionEvent(
                sequence = failureSequence.incrementAndGet(),
                elapsedRealtimeNs = clockNs(),
                sessionId = sessionId,
                recoveryChainId = chain.id,
                failureId = pending.failureId,
                fetchKey = chain.fetchKey.value,
                fetchId = pending.fetchId,
                attemptCorrelationId = pending.attemptCorrelationId,
                routeEpoch = pending.routeEpoch,
                observation = pending.observation,
                classification = pending.classification,
                decision = pending.decision,
                context = pending.context,
                action = action,
            ),
        )
        val terminal = when (action.kind) {
            RecoveryActionKind.SCHEDULE_BACKOFF,
            RecoveryActionKind.START_NEXT_OWNER,
            RecoveryActionKind.WAIT_PROVIDER,
            -> null
            RecoveryActionKind.REFRESH_DELIVERY_BINDING ->
                when (checkNotNull(action.deliveryBinding).result) {
                    DeliveryBindingActionResult.REFRESHED,
                    DeliveryBindingActionResult.ALREADY_ADVANCED,
                    DeliveryBindingActionResult.JOINED_REFRESH,
                    DeliveryBindingActionResult.ABANDONED,
                    -> null
                    DeliveryBindingActionResult.INCOMPATIBLE,
                    DeliveryBindingActionResult.FAILED,
                    -> RecoveryTerminalReason.TERMINAL_FAILURE
                    DeliveryBindingActionResult.NOT_ADMITTED ->
                        RecoveryTerminalReason.BUDGET_EXHAUSTED
                    DeliveryBindingActionResult.CLOSED ->
                        RecoveryTerminalReason.SESSION_TERMINATION
                }
            RecoveryActionKind.LOCAL_COVERAGE_READY -> RecoveryTerminalReason.SUCCESS
            RecoveryActionKind.TERMINATE_BUDGET_EXHAUSTED ->
                RecoveryTerminalReason.BUDGET_EXHAUSTED
            RecoveryActionKind.TERMINATE_NO_DEMAND ->
                RecoveryTerminalReason.NO_REMAINING_DEMAND
            RecoveryActionKind.TERMINATE_SESSION ->
                RecoveryTerminalReason.SESSION_TERMINATION
            RecoveryActionKind.TERMINATE_FAILURE,
            RecoveryActionKind.FAIL_CLOSED_ACTION_UNAVAILABLE,
            RecoveryActionKind.FAIL_CLOSED_IDENTITY_CONFLICT,
            -> RecoveryTerminalReason.TERMINAL_FAILURE
        }
        if (terminal != null) {
            terminateLocked(
                chain,
                terminal,
                classification = pending.classification,
                action = action.kind,
                failureId = pending.failureId,
                exhaustedDimension = action.exhaustedDimension,
                deliveryBindingResult = action.deliveryBinding?.result,
            )
            return Next.TERMINATED
        }
        when (action.kind) {
            RecoveryActionKind.SCHEDULE_BACKOFF -> {
                val retryOrdinal = checkNotNull(action.retryOrdinal)
                val record = RecoveryBackoffRecord(
                    retryOrdinal = retryOrdinal,
                    windowMs = policy.backoff.windowMs(retryOrdinal),
                    delayMs = checkNotNull(action.delayMs),
                )
                chain.state = RecoveryChainState.WAITING_BACKOFF
                emitBudget(
                    chain,
                    RecoveryBudgetEventKind.BACKOFF_SCHEDULED,
                    backoff = record,
                    failureId = pending.failureId,
                )
                chain.pendingBackoff = record to pending.failureId
            }
            RecoveryActionKind.WAIT_PROVIDER -> {
                val record = checkNotNull(action.providerWait)
                chain.state = RecoveryChainState.WAITING_PROVIDER
                chain.pendingProviderWait = record.waitMs to pending.failureId
            }
            else -> Unit
        }
        return Next.CONTINUE
    }

    private suspend fun reconcile(request: FetchRequest): LocalReconciliation {
        val local = reconciler ?: return LocalReconciliation.ABSENT
        return try {
            local.reconcile(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LocalReconciliation.FAILED
        }
    }

    private fun recordInternalFailure(chain: RecoveryChain) {
        synchronized(lock) {
            if (chain.state == RecoveryChainState.TERMINAL) {
                return
            }
            val observation = FailureObservation.InternalFailure
            val pending = pendingFailure(
                chain,
                outcome = null,
                observation = observation,
                classification = FailureClassifier.classify(observation),
            )
            applyLocked(chain, pending, actionFor(chain, pending))
        }
        chain.terminalOutcome?.let { complete(chain, it) }
    }

    /**
     * Runs [block] unless the chain loses all demand (or the session closes)
     * first. Returns null when the wait was abandoned; the caller re-checks
     * demand under the lock, so a late join simply continues the chain.
     */
    private suspend fun <T : Any> awaitWhileDemanded(
        chain: RecoveryChain,
        block: suspend () -> T,
    ): T? = coroutineScope {
        val work = async(start = CoroutineStart.UNDISPATCHED) { block() }
        if (work.isCompleted) {
            return@coroutineScope work.await()
        }
        chain.settled.complete(Unit)
        val stop = async { chain.stopWaiting.first { it } }
        select<T?> {
            work.onAwait { value ->
                stop.cancel()
                value
            }
            stop.onAwait {
                work.cancel()
                null
            }
        }
    }

    /**
     * Atomically terminates the chain when no further attempt may start:
     * session closing, no remaining demand, or (with [checkBudget]) no
     * REMOTE_ATTEMPT left. Returns true when the chain is now terminal.
     */
    private fun terminateIfNoAttemptPossible(
        chain: RecoveryChain,
        checkBudget: Boolean,
    ): Boolean {
        val outcome = synchronized(lock) {
            if (chain.state == RecoveryChainState.TERMINAL) {
                return true
            }
            val reason = when {
                closing -> RecoveryTerminalReason.SESSION_TERMINATION
                chain.consumers.isEmpty() -> RecoveryTerminalReason.NO_REMAINING_DEMAND
                checkBudget &&
                    !chain.ledger.canCharge(RecoveryBudgetDimension.REMOTE_ATTEMPT) ->
                    RecoveryTerminalReason.BUDGET_EXHAUSTED
                else -> return false
            }
            terminateLocked(chain, reason)
        }
        complete(chain, outcome)
        return true
    }

    private fun setState(
        chain: RecoveryChain,
        state: RecoveryChainState,
    ) {
        synchronized(lock) {
            if (chain.state != RecoveryChainState.TERMINAL) {
                chain.state = state
            }
        }
    }

    private fun finish(
        chain: RecoveryChain,
        reason: RecoveryTerminalReason,
        committedExtent: CommittedExtent? = null,
    ) {
        val outcome = synchronized(lock) {
            if (chain.state == RecoveryChainState.TERMINAL) {
                return
            }
            terminateLocked(chain, reason, committedExtent = committedExtent)
        }
        complete(chain, outcome)
    }

    /**
     * Terminal transition. The terminal event is recorded before the chain
     * leaves the registry, so no consumer event can be sequenced after it.
     */
    private fun terminateLocked(
        chain: RecoveryChain,
        reason: RecoveryTerminalReason,
        committedExtent: CommittedExtent? = null,
        classification: FailureClassification? = null,
        action: RecoveryActionKind? = null,
        failureId: String? = null,
        exhaustedDimension: RecoveryBudgetDimension? = null,
        deliveryBindingResult: DeliveryBindingActionResult? = null,
    ): RecoveryOutcome {
        check(chain.state != RecoveryChainState.TERMINAL)
        emitBudget(
            chain,
            RecoveryBudgetEventKind.CHAIN_TERMINATED,
            failureId = failureId,
            terminalReason = reason,
        )
        chain.state = RecoveryChainState.TERMINAL
        if (activeChains[chain.fetchKey] === chain) {
            activeChains.remove(chain.fetchKey)
        }
        chain.consumers.clear()
        chain.stopWaiting.value = true
        val outcome = RecoveryOutcome(
            recoveryChainId = chain.id,
            fetchKey = chain.fetchKey,
            terminalReason = reason,
            lastFetchId = chain.lastFetchId,
            lastFetchOutcome = chain.lastFetchOutcome,
            classification = classification,
            action = action,
            committedExtent = committedExtent,
            exhaustedDimension = exhaustedDimension,
            deliveryBindingResult = deliveryBindingResult,
        )
        chain.terminalOutcome = outcome
        return outcome
    }

    private fun complete(
        chain: RecoveryChain,
        outcome: RecoveryOutcome,
    ) {
        chain.result.complete(outcome)
        chain.settled.complete(Unit)
    }

    private fun release(
        chain: RecoveryChain,
        consumerId: RecoveryConsumerId,
    ) {
        var cancel: FetchHandle? = null
        synchronized(lock) {
            if (chain.state == RecoveryChainState.TERMINAL) {
                return
            }
            if (chain.consumers.remove(consumerId) == null) {
                return
            }
            emitBudget(
                chain,
                RecoveryBudgetEventKind.CONSUMER_RELEASED,
                consumerId = consumerId.value,
            )
            if (chain.consumers.isEmpty()) {
                chain.stopWaiting.value = true
                if (chain.state == RecoveryChainState.ACTIVE) {
                    chain.state = RecoveryChainState.CANCELLING
                    cancel = chain.brokerHandle
                }
            }
        }
        // The chain stays registered until the broker owner is terminal
        // (M1 cancellation barrier); a late consumer joins this chain.
        cancel?.close()
    }

    // -----------------------------------------------------------------------
    // Evidence
    // -----------------------------------------------------------------------

    private fun emitBudget(
        chain: RecoveryChain,
        kind: RecoveryBudgetEventKind,
        consumerId: String? = null,
        consumerKind: RecoveryConsumerKind? = null,
        priorityBefore: String? = null,
        charge: RecoveryBudgetCharge? = null,
        ownerOrdinal: Int? = null,
        fetchId: String? = null,
        attemptCorrelationId: String? = null,
        ownerOutcome: String? = null,
        permit: RecoveryAttemptPermit? = null,
        backoff: RecoveryBackoffRecord? = null,
        failureId: String? = null,
        terminalReason: RecoveryTerminalReason? = null,
    ) {
        val listener = evidence ?: return
        synchronized(lock) {
            val event = RecoveryBudgetEvent(
                    sequence = budgetSequence.incrementAndGet(),
                    elapsedRealtimeNs = clockNs(),
                    sessionId = sessionId,
                    recoveryChainId = chain.id,
                    kind = kind,
                    fetchKey = chain.fetchKey.value,
                    extentId = chain.request.extentSpec.extentId.value,
                    policyId = policy.policyId,
                    limits = policy.budget.limits,
                    spent = chain.ledger.snapshot(),
                    effectivePriority = chain.effectivePriority.name,
                    consumerId = consumerId,
                    consumerKind = consumerKind,
                    priorityBefore = priorityBefore,
                    charge = charge,
                    ownerOrdinal = ownerOrdinal,
                    fetchId = fetchId,
                    attemptCorrelationId = attemptCorrelationId,
                    ownerOutcome = ownerOutcome,
                    permit = permit,
                    backoff = backoff,
                    failureId = failureId,
                terminalReason = terminalReason,
            )
            runCatching { listener.onBudgetEvent(event) }
        }
    }

    private fun emitFailure(event: FailureDecisionEvent) {
        val listener = evidence ?: return
        runCatching { listener.onFailureDecision(event) }
    }

    private inner class Handle(
        private val chain: RecoveryChain,
        private val consumerId: RecoveryConsumerId,
        override val acquireDisposition: RecoveryAcquireDisposition,
    ) : RecoveryHandle {
        private val released = AtomicBoolean()

        @Volatile
        override var fetchIdAtAcquire: io.github.definitelystable.spongetube.core.engine.FetchId? =
            null

        override val recoveryChainId: RecoveryChainId
            get() = chain.id

        override val fetchKey: FetchKey
            get() = chain.fetchKey

        override suspend fun await(): RecoveryOutcome {
            check(!released.get()) { "recovery handle already released" }
            return try {
                chain.result.await()
            } finally {
                close()
            }
        }

        override fun close() {
            if (released.compareAndSet(false, true)) {
                release(chain, consumerId)
            }
        }
    }
}

private val RecoveryConsumerKind.priority: FetchPriority
    get() = when (this) {
        RecoveryConsumerKind.RESERVE -> FetchPriority.RESERVE
        RecoveryConsumerKind.PLAYBACK -> FetchPriority.PLAYBACK
    }

private val FetchPriority.consumerKind: FetchConsumerKind
    get() = when (this) {
        FetchPriority.RESERVE -> FetchConsumerKind.RESERVE
        FetchPriority.PLAYBACK -> FetchConsumerKind.PLAYBACK
    }

/** Full immutable identity check, same fields as the FetchBroker check. */
internal fun ExtentSpec.isSameWorkAs(other: ExtentSpec): Boolean =
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
