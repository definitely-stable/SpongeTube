package io.github.definitelystable.spongetube.core.engine.recovery

import io.github.definitelystable.spongetube.core.engine.DeliveryBoundFetchAttemptExecutor
import io.github.definitelystable.spongetube.core.engine.FetchAttemptDisposition
import io.github.definitelystable.spongetube.core.engine.FetchBroker
import io.github.definitelystable.spongetube.core.engine.FetchEvent
import io.github.definitelystable.spongetube.core.engine.FetchEventListener
import io.github.definitelystable.spongetube.core.engine.FetchKey
import io.github.definitelystable.spongetube.core.engine.FetchNetworkChunk
import io.github.definitelystable.spongetube.core.engine.FetchPriority
import io.github.definitelystable.spongetube.core.engine.FetchPublishSink
import io.github.definitelystable.spongetube.core.engine.FetchPublisher
import io.github.definitelystable.spongetube.core.engine.FetchRequest
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingCoordinator
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingEvent
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingEvidenceRecorder
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingRefresher
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingRevision
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingSnapshot
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryMaterial
import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryMaterialRefresh
import io.github.definitelystable.spongetube.core.engine.delivery.ProviderWallClock
import io.github.definitelystable.spongetube.core.engine.delivery.RetryAfterObservation
import io.github.definitelystable.spongetube.core.storage.CommittedExtent
import io.github.definitelystable.spongetube.core.storage.ExtentConflictException
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentIntegrityException
import io.github.definitelystable.spongetube.core.storage.ExtentSpec
import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import io.github.definitelystable.spongetube.core.storage.Sha256Digest
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** One scripted physical attempt. */
internal typealias AttemptScript =
    suspend (FetchRequest, StateFlow<FetchPriority>, suspend (FetchNetworkChunk) -> Unit) ->
    FetchAttemptDisposition

/**
 * One scripted physical attempt of a delivery-bound owner; it additionally
 * receives the revision the owner selected for this execution.
 */
internal typealias BoundAttemptScript =
    suspend (
        FetchRequest,
        StateFlow<FetchPriority>,
        suspend (FetchNetworkChunk) -> Unit,
        DeliveryBindingRevision?,
    ) -> FetchAttemptDisposition

/**
 * Deterministic origin: per FetchKey, attempts consume a queue of scripts;
 * an empty queue serves the unit bytes successfully.
 *
 * The origin is both a plain and a [DeliveryBoundFetchAttemptExecutor]; it
 * records every execution and the delivery binding revision that execution
 * ran under (null on the unbound path), so binding lineage stays checkable.
 */
internal class ScriptedOrigin : DeliveryBoundFetchAttemptExecutor {
    private val scripts = ConcurrentHashMap<String, ConcurrentLinkedQueue<AttemptScript>>()
    private val boundScripts =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<BoundAttemptScript>>()
    val executions = CopyOnWriteArrayList<String>()
    val bindingRevisions = CopyOnWriteArrayList<DeliveryBindingRevision?>()

    fun script(
        key: FetchKey,
        vararg attempts: AttemptScript,
    ) {
        scripts.getOrPut(key.value) { ConcurrentLinkedQueue() }.addAll(attempts)
    }

    fun scriptBound(
        key: FetchKey,
        vararg attempts: BoundAttemptScript,
    ) {
        boundScripts.getOrPut(key.value) { ConcurrentLinkedQueue() }.addAll(attempts)
    }

    fun executions(key: FetchKey): Int = executions.count { it == key.value }

    override suspend fun execute(
        request: FetchRequest,
        attempt: Int,
        priority: StateFlow<FetchPriority>,
        emitChunk: suspend (FetchNetworkChunk) -> Unit,
    ): FetchAttemptDisposition {
        check(attempt == 1) { "M2-C owners make exactly one attempt" }
        executions += request.fetchKey.value
        bindingRevisions += null
        val script = scripts[request.fetchKey.value]?.poll()
        return script?.invoke(request, priority, emitChunk) ?: serve(request, emitChunk)
    }

    override suspend fun executeCorrelated(
        request: FetchRequest,
        attempt: Int,
        priority: StateFlow<FetchPriority>,
        onTransportCorrelation: suspend (String) -> Unit,
        emitChunk: suspend (FetchNetworkChunk) -> Unit,
    ): FetchAttemptDisposition = execute(request, attempt, priority, emitChunk)

    override suspend fun executeWithDeliveryBinding(
        request: FetchRequest,
        attempt: Int,
        priority: StateFlow<FetchPriority>,
        deliveryBinding: DeliveryBindingSnapshot,
        onPhysicalAttemptStart: () -> Unit,
        onTransportCorrelation: suspend (String) -> Unit,
        emitChunk: suspend (FetchNetworkChunk) -> Unit,
    ): FetchAttemptDisposition {
        check(attempt == 1) { "M2-C owners make exactly one attempt" }
        executions += request.fetchKey.value
        bindingRevisions += deliveryBinding.revision
        onPhysicalAttemptStart()
        val bound = boundScripts[request.fetchKey.value]?.poll()
        if (bound != null) {
            return bound(request, priority, emitChunk, deliveryBinding.revision)
        }
        val script = scripts[request.fetchKey.value]?.poll()
        return script?.invoke(request, priority, emitChunk) ?: serve(request, emitChunk)
    }

    /**
     * One scripted provider status; the delivery binding revision is filled
     * from the revision of the execution that runs this script.
     */
    fun http(
        status: Int,
        retryAfter: RetryAfterObservation = RetryAfterObservation.ABSENT,
        signal: ProviderSignal = ProviderSignal.NONE,
    ): AttemptScript = { _, _, _ ->
        FetchAttemptDisposition.Failure(
            FailureObservation.HttpResponse(
                statusCode = status,
                retryAfter = retryAfter,
                providerSignal = signal,
                deliveryBindingRevision = bindingRevisions.lastOrNull(),
            ),
        )
    }

    companion object {
        suspend fun serve(
            request: FetchRequest,
            emitChunk: suspend (FetchNetworkChunk) -> Unit,
        ): FetchAttemptDisposition {
            emitChunk(
                FetchNetworkChunk(
                    byteStart = request.extentSpec.byteStart ?: 0L,
                    bytes = bytesFor(request.extentSpec),
                ),
            )
            return FetchAttemptDisposition.Success(transportCorrelationId = "lab-ok")
        }

        fun failWith(observation: FailureObservation): AttemptScript =
            { _, _, _ -> FetchAttemptDisposition.Failure(observation) }

        val TIMEOUT: AttemptScript =
            failWith(FailureObservation.TransportIo(TransportIoKind.READ_TIMEOUT))

        fun bytesFor(spec: ExtentSpec): ByteArray =
            ByteArray(spec.expectedLength.toInt()) { index -> (index + 1).toByte() }
    }
}

/** Opaque test delivery material; the generation is never identity. */
internal class TestDeliveryMaterial(val generation: Int) : DeliveryMaterial

/**
 * Scripted [DeliveryBindingRefresher]: one queue of outcomes, an optional
 * one-shot gate and a call counter. An exhausted queue fails the operation.
 */
internal class TestRefresher(
    private val gate: CompletableDeferred<Unit>? = null,
) : DeliveryBindingRefresher {
    private val outcomes = ConcurrentLinkedQueue<DeliveryMaterialRefresh>()
    val calls = CopyOnWriteArrayList<String>()

    fun enqueue(vararg results: DeliveryMaterialRefresh) {
        outcomes.addAll(results)
    }

    override suspend fun refresh(
        current: DeliveryBindingSnapshot,
        refreshCorrelationId: String,
    ): DeliveryMaterialRefresh {
        calls += refreshCorrelationId
        gate?.await()
        return outcomes.poll() ?: DeliveryMaterialRefresh.Failed
    }

    companion object {
        fun material(generation: Int): DeliveryMaterialRefresh =
            DeliveryMaterialRefresh.Material(TestDeliveryMaterial(generation))
    }
}

/** In-memory publisher that can inject integrity/conflict failures. */
internal class MemoryPublisher : FetchPublisher {
    private val committed = ConcurrentHashMap<ExtentId, CommittedExtent>()
    val integrityFailures: MutableSet<ExtentId> = ConcurrentHashMap.newKeySet()

    fun contains(extentId: ExtentId): Boolean = committed.containsKey(extentId)

    fun seed(spec: ExtentSpec) {
        committed[spec.extentId] = committed(spec)
    }

    /** Like ExtentStore, a conflicting publisher is detected at commit. */
    override suspend fun publish(
        spec: ExtentSpec,
        producer: suspend (FetchPublishSink) -> Unit,
    ): CommittedExtent {
        val out = ByteArrayOutputStream()
        producer(
            object : FetchPublishSink {
                override suspend fun write(bytes: ByteArray) {
                    out.write(bytes)
                }
            },
        )
        if (spec.extentId in integrityFailures) {
            val digest = Sha256Digest("0".repeat(64))
            throw ExtentIntegrityException(
                extentId = spec.extentId,
                expectedLength = spec.expectedLength,
                receivedLength = out.size().toLong(),
                persistedLength = out.size().toLong(),
                expectedSha256 = Sha256Digest("1".repeat(64)),
                receivedSha256 = digest,
                persistedSha256 = digest,
            )
        }
        check(out.size().toLong() == spec.expectedLength) { "short publish" }
        val extent = committed(spec)
        if (committed.putIfAbsent(spec.extentId, extent) != null) {
            throw ExtentConflictException("extent already committed: ${spec.extentId}")
        }
        return extent
    }

    companion object {
        fun committed(spec: ExtentSpec): CommittedExtent =
            CommittedExtent(
                mediaAssetId = spec.mediaAssetId,
                extentId = spec.extentId,
                trackId = spec.trackId,
                representationId = spec.representationId,
                mediaStartUs = spec.mediaStartUs,
                mediaEndUs = spec.mediaEndUs,
                byteStart = spec.byteStart,
                byteEndExclusive = spec.byteEndExclusive,
                dependencyExtentIds = spec.dependencyExtentIds,
                length = spec.expectedLength,
                sha256 = spec.expectedSha256 ?: Sha256Digest("0".repeat(64)),
            )
    }
}

/**
 * Records every requested wait without real sleeping. A harness sleeper also
 * advances the harness monotonic clock by `delayMs * 1_000_000` so
 * elapsed-time lineage of a provider wait is checkable (DESIGN 8).
 */
internal class RecordingSleeper(
    private val onSleep: (Long) -> Unit = {},
) : RecoverySleeper {
    val delays = CopyOnWriteArrayList<Long>()

    override suspend fun sleep(delayMs: Long) {
        delays += delayMs
        onSleep(delayMs)
    }
}

/**
 * Deterministic test policy (M2-D): `sponge-recovery-test-v2` with
 * REMOTE_ATTEMPT = 4, DELIVERY_BINDING_REFRESH = 1 and a backoff whose
 * windows are 100, 200, 400 ms; with [FULL_WINDOW_JITTER] the delays are
 * exactly those windows.
 */
internal val TEST_POLICY = RecoveryPolicy(
    budget = RecoveryBudgetPolicy(
        policyId = "sponge-recovery-test-v2",
        limits = mapOf(
            RecoveryBudgetDimension.REMOTE_ATTEMPT to 4,
            RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH to 1,
        ),
    ),
    backoff = RecoveryBackoff(baseMs = 100, capMs = 400),
)

internal val FULL_WINDOW_JITTER = RecoveryJitterSource { window -> window }

/** 2026-09-25T12:00:00Z: the fixed PROVIDER_WALL_CLOCK instant of the harness. */
internal const val HARNESS_PROVIDER_WALL_CLOCK_UTC_MS = 1_790_337_600_000L

/**
 * Real FetchBroker + RecoveryCoordinator over deterministic fakes.
 *
 * [refresher], when given, wires a [DeliveryBindingCoordinator] built with the
 * harness scope and clock; without it the coordinator runs unbound and every
 * provider action fails closed. The harness sleeper records each wait and
 * advances the harness monotonic clock (DESIGN 8).
 */
internal class RecoveryHarness(
    val policy: RecoveryPolicy = TEST_POLICY,
    gate: RecoveryAttemptGate = RecoveryAttemptGate.ALWAYS_PERMIT,
    sleeper: RecoverySleeper? = null,
    jitter: RecoveryJitterSource = FULL_WINDOW_JITTER,
    reconciler: RecoveryLocalReconciler? = null,
    val sessionId: String = "m2-c-host",
    refresher: DeliveryBindingRefresher? = null,
    val providerWallClock: ProviderWallClock =
        ProviderWallClock { HARNESS_PROVIDER_WALL_CLOCK_UTC_MS },
) {
    private val clock = AtomicLong(1_000_000)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val sleeper: RecoverySleeper = sleeper ?: RecordingSleeper { delayMs ->
        clock.addAndGet(delayMs * 1_000_000)
    }

    val origin = ScriptedOrigin()
    val publisher = MemoryPublisher()
    val fetchEvents = CopyOnWriteArrayList<FetchEvent>()
    val evidence = RecoveryEvidenceRecorder("$sessionId-run", sessionId)
    val deliveryEvidence = DeliveryBindingEvidenceRecorder("$sessionId-run", sessionId)

    val bindings: DeliveryBindingCoordinator? = refresher?.let {
        DeliveryBindingCoordinator(
            initialMaterial = TestDeliveryMaterial(1),
            refresher = it,
            evidence = deliveryEvidence,
            scope = scope,
            ownsScope = false,
            clockNs = { clock.addAndGet(1_000) },
        )
    }

    val broker = FetchBroker(
        publisher = publisher,
        executor = origin,
        sessionId = sessionId,
        eventListener = FetchEventListener { fetchEvents += it },
        ownerScope = scope,
        ownsScope = false,
        monotonicClockNs = { clock.addAndGet(1_000) },
    )

    val coordinator = RecoveryCoordinator(
        broker = broker,
        sessionId = sessionId,
        policy = policy,
        attemptGate = gate,
        reconciler = reconciler ?: RecoveryLocalReconciler { request ->
            if (publisher.contains(request.extentSpec.extentId)) {
                LocalReconciliation.COVERAGE_PRESENT
            } else {
                LocalReconciliation.ABSENT
            }
        },
        bindings = bindings,
        providerWallClock = providerWallClock,
        jitter = jitter,
        sleeper = this.sleeper,
        evidence = evidence,
        scope = scope,
        ownsScope = false,
        clockNs = { clock.addAndGet(1_000) },
    )

    val budgetEvents: List<RecoveryBudgetEvent>
        get() = evidence.budgetEvents()

    val failures: List<FailureDecisionEvent>
        get() = evidence.failures()

    val bindingEvents: List<DeliveryBindingEvent>
        get() = deliveryEvidence.events()

    fun budget(kind: RecoveryBudgetEventKind): List<RecoveryBudgetEvent> =
        budgetEvents.filter { it.kind == kind }

    suspend fun acquire(
        work: FetchRequest,
        consumer: String,
        kind: RecoveryConsumerKind = RecoveryConsumerKind.PLAYBACK,
    ): RecoveryHandle =
        coordinator.acquire(work, RecoveryConsumer(RecoveryConsumerId(consumer), kind))

    fun <T> blocking(block: suspend () -> T): T =
        runBlocking { withTimeout(10_000) { block() } }

    fun awaitCondition(
        timeoutMs: Long = 5_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not reached in ${timeoutMs}ms" }
            Thread.sleep(2)
        }
    }

    fun shutdown() {
        runBlocking {
            coordinator.shutdown()
            broker.shutdown()
        }
    }

    companion object {
        private val ASSET = MediaAssetId("fixture:M2C")

        fun work(
            name: String,
            length: Long = 8,
            extentSuffix: String = "",
        ): FetchRequest =
            FetchRequest(
                fetchKey = FetchKey("fixture:M2C/video/$name"),
                extentSpec = ExtentSpec(
                    mediaAssetId = ASSET,
                    extentId = ExtentId("m2c:video:$name$extentSuffix"),
                    trackId = "video",
                    representationId = "v1",
                    mediaStartUs = null,
                    mediaEndUs = null,
                    byteStart = 0,
                    byteEndExclusive = length,
                    expectedLength = length,
                ),
            )
    }
}

/** Minimal deterministic JSON writer for evidence artifacts. */
internal fun recoveryJson(value: Any?): String = when (value) {
    null -> "null"
    is String -> buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                else -> append(char)
            }
        }
        append('"')
    }
    is Number, is Boolean -> value.toString()
    is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
        recoveryJson(key.toString()) + ":" + recoveryJson(item)
    }
    is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { recoveryJson(it) }
    else -> error("unsupported JSON value: $value")
}
