package io.github.definitelystable.spongetube.core.engine

import android.os.SystemClock
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentReadHandle
import io.github.definitelystable.spongetube.core.storage.ExtentStore
import java.io.Closeable
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/** Provisional M1-E bridge runtime parameters; recorded in evidence. */
@SpongeBridgeApi
class PlaybackBridgeConfig(
    val sessionId: String,
    val maxAttemptsPerFetch: Int = 2,
    val connectTimeoutMs: Int = 10_000,
    val readTimeoutMs: Int = 15_000,
    val transportGate: PlaybackTransportGate? = null,
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(maxAttemptsPerFetch > 0) { "maxAttemptsPerFetch must be > 0" }
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be > 0" }
        require(readTimeoutMs > 0) { "readTimeoutMs must be > 0" }
    }

    fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
        "sessionId" to sessionId,
        "maxAttemptsPerFetch" to maxAttemptsPerFetch,
        "connectTimeoutMs" to connectTimeoutMs,
        "readTimeoutMs" to readTimeoutMs,
        "transportGate" to (transportGate != null),
    )
}

/**
 * Evidence-harness hook invoked inside the broker-owned attempt before the
 * physical request is opened. It can only delay/cancel an attempt, never
 * create one; production wiring leaves it null.
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
 * projection, one FetchBroker (the only remote owner) and the read-session
 * factory used by the Media3 adapter.
 *
 * Close order (M1-E F9): player.release() -> [shutdown] -> ExtentStore.close().
 */
@SpongeBridgeApi
class PlaybackBridgeRuntime internal constructor(
    val plan: PlaybackPlan,
    val coverageIndex: CoverageIndex,
    private val broker: FetchBroker,
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
     * joins or starts the same single-flight owner the bridge would use.
     */
    suspend fun acquireReserve(
        fetchKey: FetchKey,
        consumerId: String,
    ): PlaybackReserveLease {
        val unit = requireNotNull(plan.unitForFetchKey(fetchKey)) {
            "fetch key is not part of the playback plan: $fetchKey"
        }
        return PlaybackReserveLease(
            acquire(unit, consumerId, FetchConsumerKind.RESERVE),
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

    suspend fun shutdown() {
        closed = true
        broker.shutdown()
    }

    internal suspend fun acquire(
        unit: PlaybackFetchUnit,
        consumerId: String,
        kind: FetchConsumerKind,
    ): FetchHandle =
        broker.acquire(
            FetchRequest(unit.fetchKey, unit.extentSpec),
            FetchConsumer(FetchConsumerId(consumerId), kind),
        )

    internal fun emit(
        readId: String?,
        resourceKey: String?,
        requestedRangeStart: Long?,
        requestedRangeEndExclusive: Long?,
        event: PlaybackBridgeEventKind,
        extentId: ExtentId? = null,
        dependencyExtentId: ExtentId? = null,
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
                attemptBudget = FetchAttemptBudget(config.maxAttemptsPerFetch),
                sessionId = config.sessionId,
                eventListener = fetchEventListener?.let { sink ->
                    FetchEventListener { event -> sink.onEvent(event.toArtifactMap()) }
                },
            )
            return PlaybackBridgeRuntime(
                plan = plan,
                coverageIndex = index,
                broker = broker,
                reader = ExtentStoreLocalReader(store),
                sessionId = config.sessionId,
                clockNs = { SystemClock.elapsedRealtimeNanos() },
                listener = bridgeEventListener,
            )
        }
    }
}

/** Harness-only RESERVE consumer lease; see [PlaybackBridgeRuntime.acquireReserve]. */
@SpongeBridgeApi
class PlaybackReserveLease internal constructor(
    private val handle: FetchHandle,
) : AutoCloseable {
    val fetchId: String
        get() = handle.fetchId.value

    val joinedExisting: Boolean
        get() = handle.joinedExisting

    /** Awaits the terminal outcome and returns its FetchOutcomeKind name. */
    suspend fun awaitOutcome(): String = handle.await().kind.name

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
