package io.github.definitelystable.spongetube.core.engine

import android.os.SystemClock
import io.github.definitelystable.spongetube.core.engine.recovery.LocalReconciliation
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryAttemptGate
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumer
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumerId
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumerKind
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryCoordinator
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryEvidenceListener
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryHandle
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryJitterSource
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryLocalReconciler
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryPolicy
import io.github.definitelystable.spongetube.core.engine.recovery.RecoverySleeper
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentReadHandle
import io.github.definitelystable.spongetube.core.storage.ExtentStore
import java.io.Closeable
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * Provisional bridge runtime parameters; recorded in evidence.
 *
 * Recovery is not configurable here: since M2-D the internal
 * RecoveryCoordinator applies the versioned `sponge-recovery-v2` policy.
 */
@SpongeBridgeApi
class PlaybackBridgeConfig(
    val sessionId: String,
    val connectTimeoutMs: Int = 10_000,
    val readTimeoutMs: Int = 15_000,
    val transportGate: PlaybackTransportGate? = null,
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be > 0" }
        require(readTimeoutMs > 0) { "readTimeoutMs must be > 0" }
    }

    fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
        "sessionId" to sessionId,
        "recoveryPolicyId" to RecoveryPolicy.DEFAULT_POLICY_ID,
        "connectTimeoutMs" to connectTimeoutMs,
        "readTimeoutMs" to readTimeoutMs,
        "transportGate" to (transportGate != null),
    )
}

/**
 * Evidence-harness hook invoked inside the broker-owned attempt before the
 * physical request is opened. It can only delay/cancel an attempt, never
 * create one; production wiring leaves it null. Since M2-C every owner makes
 * exactly one attempt, so [attempt] is always 1.
 */
@SpongeBridgeApi
fun interface PlaybackTransportGate {
    suspend fun beforeAttempt(
        fetchKey: FetchKey,
        attempt: Int,
    )
}

/**
 * Deterministic fixture origin (Media Lab) locator. Only `http`/`https` base
 * URLs; the transport key of a plan unit is resolved below `/fixtures/`.
 * Provider/URL selection is not an M1 concern (#50/M2).
 */
@SpongeBridgeApi
class FixtureTransportSession(
    baseUrl: String,
) {
    val baseUrl: String = baseUrl.trimEnd('/')

    init {
        val scheme = this.baseUrl.substringBefore("://", "")
        require(scheme == "http" || scheme == "https") {
            "fixture transport requires an http(s) base URL"
        }
    }

    internal fun urlFor(transportKey: String): URL =
        URL(baseUrl + "/fixtures/" + transportKey)
}

/**
 * Owns the per-session engine side of PlaybackBridge: one CoverageIndex
 * projection, one RecoveryCoordinator (the only logical retry owner), one
 * FetchBroker (the only remote owner) and the read-session factory used by
 * the Media3 adapter.
 *
 * Close order (M1-E F9): player.release() -> [shutdown] -> ExtentStore.close().
 */
@SpongeBridgeApi
class PlaybackBridgeRuntime internal constructor(
    val plan: PlaybackPlan,
    val coverageIndex: CoverageIndex,
    private val broker: FetchBroker,
    private val recovery: RecoveryCoordinator,
    internal val reader: LocalExtentReader,
    val sessionId: String,
    private val clockNs: () -> Long,
    private val listener: PlaybackBridgeEventListener?,
) {
    private val readCounter = AtomicLong()
    private val eventCounter = AtomicLong()
    private val eventLock = Any()

    @Volatile
    private var closed = false

    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
    }

    fun newReadSession(): PlaybackReadSession {
        if (closed) {
            throw PlaybackBridgeException(
                failure = PlaybackBridgeFailure.RUNTIME_CLOSED,
                message = "playback bridge runtime is closed",
            )
        }
        return PlaybackReadSession(
            runtime = this,
            readId = "r" + readCounter.incrementAndGet(),
        )
    }

    /**
     * Harness-only RESERVE demand (M1-E has no ReserveController). The lease
     * joins or starts the same RecoveryChain the bridge would use.
     */
    suspend fun acquireReserve(
        fetchKey: FetchKey,
        consumerId: String,
    ): PlaybackReserveLease {
        val unit = requireNotNull(plan.unitForFetchKey(fetchKey)) {
            "fetch key is not part of the playback plan: $fetchKey"
        }
        return PlaybackReserveLease(
            acquire(unit, consumerId, RecoveryConsumerKind.RESERVE),
        )
    }

    /**
     * Records an ordered harness marker, e.g. SEEK_ISSUED/SEEK_SETTLED, with
     * the FetchBroker event-sequence watermark so windows can be checked
     * without comparing clocks.
     */
    fun emitHarnessMarker(name: String) {
        emit(
            readId = null,
            resourceKey = null,
            requestedRangeStart = null,
            requestedRangeEndExclusive = null,
            event = PlaybackBridgeEventKind.HARNESS_MARKER,
            markerName = name,
            fetchEventSequenceWatermark = broker.eventSequenceWatermark(),
        )
    }

    /**
     * Session shutdown (M2-C order): stop recovery (every open chain ends as
     * SESSION_TERMINATION and no chain starts another attempt), then shut
     * down the FetchBroker.
     */
    suspend fun shutdown() {
        closed = true
        recovery.shutdown()
        broker.shutdown()
    }

    internal suspend fun acquire(
        unit: PlaybackFetchUnit,
        consumerId: String,
        kind: RecoveryConsumerKind,
    ): RecoveryHandle =
        recovery.acquire(
            FetchRequest(unit.fetchKey, unit.extentSpec),
            RecoveryConsumer(RecoveryConsumerId(consumerId), kind),
        )

    internal fun emit(
        readId: String?,
        resourceKey: String?,
        requestedRangeStart: Long?,
        requestedRangeEndExclusive: Long?,
        event: PlaybackBridgeEventKind,
        extentId: ExtentId? = null,
        dependencyExtentId: ExtentId? = null,
        recoveryChainId: String? = null,
        recoveryDisposition: String? = null,
        fetchId: String? = null,
        fetchOutcome: String? = null,
        bytesLocal: Long? = null,
        openReadCalls: Int? = null,
        coverageRefreshCalls: Int? = null,
        markerName: String? = null,
        fetchEventSequenceWatermark: Long? = null,
    ) {
        val target = listener ?: return
        synchronized(eventLock) {
            val row = PlaybackBridgeEvent(
                eventSequence = eventCounter.getAndIncrement(),
                eventElapsedRealtimeNs = clockNs(),
                sessionId = sessionId,
                readId = readId,
                resourceKey = resourceKey,
                requestedRangeStart = requestedRangeStart,
                requestedRangeEndExclusive = requestedRangeEndExclusive,
                event = event,
                extentId = extentId?.value,
                dependencyExtentId = dependencyExtentId?.value,
                recoveryChainId = recoveryChainId,
                recoveryDisposition = recoveryDisposition,
                fetchId = fetchId,
                fetchOutcome = fetchOutcome,
                bytesLocal = bytesLocal,
                openReadCalls = openReadCalls,
                coverageRefreshCalls = coverageRefreshCalls,
                markerName = markerName,
                fetchEventSequenceWatermark = fetchEventSequenceWatermark,
            )
            runCatching { target.onEvent(row) }
        }
    }

    companion object {
        /**
         * Builds the runtime over a real ExtentStore with the bounded
         * HttpRangeFetchExecutor. Refreshes the CoverageIndex once before
         * returning so the first read sees committed coverage.
         */
        suspend fun open(
            store: ExtentStore,
            plan: PlaybackPlan,
            transport: FixtureTransportSession,
            config: PlaybackBridgeConfig,
            bridgeEventListener: PlaybackBridgeEventListener? = null,
            fetchEventListener: PlaybackFetchEvidenceListener? = null,
        ): PlaybackBridgeRuntime =
            openInternal(
                store = store,
                plan = plan,
                transport = transport,
                config = config,
                bridgeEventListener = bridgeEventListener,
                fetchEventListener = fetchEventListener,
            )

        /**
         * Internal factory with recovery injection for tests and evidence
         * harnesses. Production uses [RecoveryPolicy.DEFAULT].
         */
        internal suspend fun openInternal(
            store: ExtentStore,
            plan: PlaybackPlan,
            transport: FixtureTransportSession,
            config: PlaybackBridgeConfig,
            bridgeEventListener: PlaybackBridgeEventListener? = null,
            fetchEventListener: PlaybackFetchEvidenceListener? = null,
            recoveryPolicy: RecoveryPolicy = RecoveryPolicy.DEFAULT,
            attemptGate: RecoveryAttemptGate = RecoveryAttemptGate.ALWAYS_PERMIT,
            recoveryEvidence: RecoveryEvidenceListener? = null,
            jitter: RecoveryJitterSource = RecoveryJitterSource.RANDOM,
            sleeper: RecoverySleeper = RecoverySleeper.COROUTINE_DELAY,
        ): PlaybackBridgeRuntime {
            val index = CoverageIndex(store)
            index.refresh()
            val executor = HttpRangeFetchExecutor(
                targetFor = { request ->
                    val unit = plan.unitForFetchKey(request.fetchKey)
                    val resourceLength =
                        plan.transportResourceLength(request.fetchKey)
                    if (unit == null || resourceLength == null) {
                        null
                    } else {
                        HttpRangeTarget(
                            url = transport.urlFor(unit.transportKey),
                            resourceLength = resourceLength,
                        )
                    }
                },
                connectTimeoutMs = config.connectTimeoutMs,
                readTimeoutMs = config.readTimeoutMs,
            )
            val gate = config.transportGate
            val gatedExecutor = if (gate == null) {
                executor
            } else {
                FetchAttemptExecutor { request, attempt, priority, emitChunk ->
                    gate.beforeAttempt(request.fetchKey, attempt)
                    executor.execute(request, attempt, priority, emitChunk)
                }
            }
            val broker = FetchBroker(
                extentStore = store,
                executor = gatedExecutor,
                sessionId = config.sessionId,
                eventListener = fetchEventListener?.let { sink ->
                    FetchEventListener { event -> sink.onEvent(event.toArtifactMap()) }
                },
            )
            val recovery = RecoveryCoordinator(
                broker = broker,
                sessionId = config.sessionId,
                policy = recoveryPolicy,
                attemptGate = attemptGate,
                reconciler = coverageReconciler(index),
                jitter = jitter,
                sleeper = sleeper,
                evidence = recoveryEvidence,
            )
            return PlaybackBridgeRuntime(
                plan = plan,
                coverageIndex = index,
                broker = broker,
                recovery = recovery,
                reader = ExtentStoreLocalReader(store),
                sessionId = config.sessionId,
                clockNs = { SystemClock.elapsedRealtimeNanos() },
                listener = bridgeEventListener,
            )
        }
    }
}

/**
 * STORAGE_CONFLICT reconciliation (M1 semantics, owned by recovery since
 * M2-C): refresh the projection and resolve the immutable extent locally.
 */
internal fun coverageReconciler(index: CoverageIndex): RecoveryLocalReconciler =
    RecoveryLocalReconciler { request ->
        index.refresh()
        when (index.resolveExtent(request.extentSpec)) {
            ExtentResolution.Ready,
            is ExtentResolution.PublishedNotReady,
            -> LocalReconciliation.COVERAGE_PRESENT
            ExtentResolution.IdentityConflict -> LocalReconciliation.IDENTITY_CONFLICT
            ExtentResolution.Absent -> LocalReconciliation.ABSENT
        }
    }

/** Harness-only RESERVE consumer lease; see [PlaybackBridgeRuntime.acquireReserve]. */
@SpongeBridgeApi
class PlaybackReserveLease internal constructor(
    private val handle: RecoveryHandle,
) : AutoCloseable {
    /**
     * FetchBroker owner current when the lease was issued (the chain's first
     * owner for a new chain). Falls back to the recovery chain id only while
     * a chain has not opened any owner yet.
     */
    val fetchId: String
        get() = handle.fetchIdAtAcquire?.value ?: handle.recoveryChainId.value

    val recoveryChainId: String
        get() = handle.recoveryChainId.value

    val joinedExisting: Boolean
        get() = handle.joinedExisting

    /**
     * Awaits the chain terminal. Returns `SUCCESS`, otherwise the legacy
     * outcome name of the last owner (or the terminal reason if no owner ran).
     */
    suspend fun awaitOutcome(): String {
        val outcome = handle.await()
        return when {
            outcome.isSuccess -> FetchOutcomeKind.SUCCESS.name
            else -> outcome.lastFetchOutcome?.name ?: outcome.terminalReason.name
        }
    }

    override fun close() {
        handle.close()
    }
}

/** Local read seam over published immutable extents (1 metadata lookup per open). */
internal fun interface LocalExtentReader {
    suspend fun openRead(extentId: ExtentId): LocalExtentHandle?
}

internal interface LocalExtentHandle : Closeable {
    val length: Long

    fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int
}

private class ExtentStoreLocalReader(
    private val store: ExtentStore,
) : LocalExtentReader {
    override suspend fun openRead(extentId: ExtentId): LocalExtentHandle? =
        store.openRead(extentId)?.let(::StoreHandle)

    private class StoreHandle(
        private val delegate: ExtentReadHandle,
    ) : LocalExtentHandle {
        override val length: Long
            get() = delegate.length

        override fun readAt(
            position: Long,
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = delegate.readAt(position, buffer, offset, length)

        override fun close() {
            delegate.close()
        }
    }
}
