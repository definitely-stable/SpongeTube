package io.github.definitelystable.spongetube.core.engine.recovery

import io.github.definitelystable.spongetube.core.engine.FetchHandle
import io.github.definitelystable.spongetube.core.engine.FetchId
import io.github.definitelystable.spongetube.core.engine.FetchKey
import io.github.definitelystable.spongetube.core.engine.FetchOutcomeKind
import io.github.definitelystable.spongetube.core.engine.FetchPriority
import io.github.definitelystable.spongetube.core.engine.FetchRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Runtime state of one RecoveryChain: one logical recovery of one immutable
 * `FetchKey + ExtentSpec` (M2.md 11.1).
 *
 * The chain owns its [ledger] for its whole life and at most one current
 * FetchBroker handle; consumers attach above it. All mutable fields are
 * guarded by the coordinator lock.
 */
internal class RecoveryChain(
    val id: RecoveryChainId,
    val request: FetchRequest,
    policy: RecoveryPolicy,
    initialPriority: FetchPriority,
) {
    val fetchKey: FetchKey
        get() = request.fetchKey

    val ledger = RecoveryBudgetLedger(policy.budget)
    val consumers = LinkedHashMap<RecoveryConsumerId, RecoveryConsumer>()

    var state: RecoveryChainState = RecoveryChainState.ACTIVE
    var effectivePriority: FetchPriority = initialPriority
    var brokerHandle: FetchHandle? = null
    var ownerOrdinal = 0
    var failureOrdinal = 0
    var retryOrdinal = 0
    var lastFetchId: FetchId? = null
    var lastFetchOutcome: FetchOutcomeKind? = null
    var lastRouteEpoch: Long? = null
    var pendingBackoff: Pair<RecoveryBackoffRecord, String>? = null
    var terminalOutcome: RecoveryOutcome? = null
    var job: Job? = null

    /**
     * True when the chain should stop waiting: no consumer remains or the
     * session is shutting down. Waits observe it instead of polling.
     */
    val stopWaiting = MutableStateFlow(false)

    /**
     * Completes once acquirers can observe a stable owner view: the first
     * owner is registered, the chain suspends in a wait, or it is terminal.
     */
    val settled = CompletableDeferred<Unit>()
    val result = CompletableDeferred<RecoveryOutcome>()
}

/** One consumer's lease on a RecoveryChain. */
internal interface RecoveryHandle : AutoCloseable {
    val recoveryChainId: RecoveryChainId
    val fetchKey: FetchKey
    val acquireDisposition: RecoveryAcquireDisposition

    /**
     * Current (or last) physical owner when the handle was issued; null only
     * while the chain has not opened any owner yet.
     */
    val fetchIdAtAcquire: FetchId?

    val joinedExisting: Boolean
        get() = acquireDisposition != RecoveryAcquireDisposition.NEW_CHAIN

    /** Awaits the chain terminal; releases this consumer when it returns. */
    suspend fun await(): RecoveryOutcome

    override fun close()
}
