package io.github.definitelystable.spongetube.core.engine

import io.github.definitelystable.spongetube.core.storage.CommittedExtent
import io.github.definitelystable.spongetube.core.storage.ExtentConflictException
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentSpec
import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import io.github.definitelystable.spongetube.core.storage.Sha256Digest
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/** In-memory ExtentStore stand-in with call counters (no Room). */
internal class FakeExtentStore {
    private val lock = Any()
    private val rows = LinkedHashMap<ExtentId, Pair<CommittedExtent, ByteArray>>()
    private val quarantineOnOpen = mutableSetOf<ExtentId>()

    val committedExtentsCalls = AtomicInteger()
    val openReadCalls = AtomicInteger()
    val openHandles = AtomicInteger()

    val loader: suspend () -> List<CommittedExtent> = {
        committedExtentsCalls.incrementAndGet()
        synchronized(lock) { rows.values.map { it.first } }
    }

    fun seed(spec: ExtentSpec, bytes: ByteArray) {
        synchronized(lock) {
            require(bytes.size.toLong() == spec.expectedLength)
            rows[spec.extentId] = committed(spec) to bytes.copyOf()
        }
    }

    fun contains(extentId: ExtentId): Boolean =
        synchronized(lock) { extentId in rows }

    fun quarantineOnNextOpen(extentId: ExtentId) {
        synchronized(lock) { quarantineOnOpen += extentId }
    }

    val publisher = FetchPublisher { spec, producer ->
        if (contains(spec.extentId)) {
            throw ExtentConflictException("extent already committed: ${spec.extentId}")
        }
        val out = ByteArrayOutputStream()
        producer(
            object : FetchPublishSink {
                override suspend fun write(bytes: ByteArray) {
                    out.write(bytes)
                }
            },
        )
        val bytes = out.toByteArray()
        check(bytes.size.toLong() == spec.expectedLength) { "short publish" }
        synchronized(lock) {
            if (spec.extentId in rows) {
                throw ExtentConflictException("extent already committed: ${spec.extentId}")
            }
            rows[spec.extentId] = committed(spec) to bytes
            rows.getValue(spec.extentId).first
        }
    }

    val reader = LocalExtentReader { extentId ->
        openReadCalls.incrementAndGet()
        val bytes = synchronized(lock) {
            if (quarantineOnOpen.remove(extentId)) {
                rows.remove(extentId)
                null
            } else {
                rows[extentId]?.second
            }
        } ?: return@LocalExtentReader null
        openHandles.incrementAndGet()
        object : LocalExtentHandle {
            private var closed = false

            override val length: Long = bytes.size.toLong()

            override fun readAt(
                position: Long,
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                check(!closed)
                if (position >= bytes.size) {
                    return -1
                }
                val count = minOf(length.toLong(), bytes.size - position).toInt()
                System.arraycopy(bytes, position.toInt(), buffer, offset, count)
                return count
            }

            override fun close() {
                if (!closed) {
                    closed = true
                    openHandles.decrementAndGet()
                }
            }
        }
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

/** Serves plan unit bytes; optional per-key gates hold an attempt open. */
internal class FakeOrigin(
    private val bytesFor: (FetchRequest) -> ByteArray,
) : FetchAttemptExecutor {
    val executions = CopyOnWriteArrayList<String>()
    val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    val entered = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    @Volatile
    var failure: FetchAttemptDisposition.Failure? = null

    fun gate(fetchKey: FetchKey): CompletableDeferred<Unit> =
        gates.getOrPut(fetchKey.value) { CompletableDeferred() }

    fun entered(fetchKey: FetchKey): CompletableDeferred<Unit> =
        entered.getOrPut(fetchKey.value) { CompletableDeferred() }

    override suspend fun execute(
        request: FetchRequest,
        attempt: Int,
        priority: kotlinx.coroutines.flow.StateFlow<FetchPriority>,
        emitChunk: suspend (FetchNetworkChunk) -> Unit,
    ): FetchAttemptDisposition {
        executions += request.fetchKey.value
        entered(request.fetchKey).complete(Unit)
        gates[request.fetchKey.value]?.await()
        failure?.let { return it }
        val bytes = bytesFor(request)
        emitChunk(FetchNetworkChunk(request.extentSpec.byteStart ?: 0L, bytes))
        return FetchAttemptDisposition.Success(
            transportCorrelationId = "lab-" + executions.size,
        )
    }
}

/** Small deterministic plan: inline manifest, video init + 3 segments, split audio. */
@OptIn(SpongeBridgeApi::class)
internal class TestPlanFixture {
    val asset = MediaAssetId("fixture:TEST")
    val manifestBytes = "<MPD/>".toByteArray()

    val videoInit = unit("v-init", "t:v:init", length = 4, start = null)
    val video = (1..3).map { number ->
        unit(
            key = "v-$number",
            extentId = "t:v:$number",
            length = 10,
            start = (number - 1) * 10_000_000L,
            dependencies = listOf("t:v:init"),
        )
    }
    val audioParts = (0 until 3).map { part ->
        val start = part * 10L
        PlaybackFetchUnit(
            fetchKey = FetchKey("fixture:TEST/audio/a-split#$start"),
            extentSpec = ExtentSpec(
                mediaAssetId = asset,
                extentId = ExtentId("t:a:split@$start"),
                trackId = "audio",
                representationId = "a1",
                mediaStartUs = null,
                mediaEndUs = null,
                byteStart = start,
                byteEndExclusive = start + 10,
                expectedLength = 10,
            ),
            resourceStart = start,
            transportKey = "TEST/a-split",
        )
    }

    val plan: PlaybackPlan = PlaybackPlan(
        mediaAssetId = asset,
        resources = buildList {
            add(
                InlinePlaybackResource(
                    key = "manifest.mpd",
                    bytes = manifestBytes,
                    expectedLength = manifestBytes.size.toLong(),
                    expectedSha256 = Sha256Digest(sha256Hex(manifestBytes)),
                ),
            )
            add(ExtentPlaybackResource("v-init", 4, listOf(videoInit)))
            video.forEachIndexed { index, unit ->
                add(ExtentPlaybackResource("v-${index + 1}", 10, listOf(unit)))
            }
            add(ExtentPlaybackResource("a-split", 30, audioParts))
        },
    )

    /** Deterministic resource bytes; unit bytes are the matching slice. */
    fun resourceBytes(key: String, length: Int): ByteArray =
        ByteArray(length) { index -> (index * 7 + key.hashCode()).toByte() }

    fun bytesOf(unit: PlaybackFetchUnit): ByteArray {
        val resource = plan.resources.first { resource ->
            resource is ExtentPlaybackResource && unit in resource.units
        }
        val all = resourceBytes(resource.key, resource.length.toInt())
        return all.copyOfRange(
            unit.resourceStart.toInt(),
            unit.resourceEndExclusive.toInt(),
        )
    }

    fun bytesFor(request: FetchRequest): ByteArray =
        bytesOf(checkNotNull(plan.unitForFetchKey(request.fetchKey)))

    fun seed(store: FakeExtentStore, vararg units: PlaybackFetchUnit) {
        units.forEach { store.seed(it.extentSpec, bytesOf(it)) }
    }

    private fun unit(
        key: String,
        extentId: String,
        length: Long,
        start: Long?,
        dependencies: List<String> = emptyList(),
    ): PlaybackFetchUnit =
        PlaybackFetchUnit(
            fetchKey = FetchKey("fixture:TEST/video/$key"),
            extentSpec = ExtentSpec(
                mediaAssetId = asset,
                extentId = ExtentId(extentId),
                trackId = "video",
                representationId = "v1",
                mediaStartUs = start,
                mediaEndUs = start?.plus(10_000_000L),
                byteStart = null,
                byteEndExclusive = null,
                dependencyExtentIds = dependencies.map(::ExtentId),
                expectedLength = length,
            ),
            resourceStart = 0,
            transportKey = "TEST/$key",
        )
}

/** Real FetchBroker + CoverageIndex over the fakes. */
@OptIn(SpongeBridgeApi::class)
internal class BridgeHarness(
    val fixture: TestPlanFixture = TestPlanFixture(),
    val store: FakeExtentStore = FakeExtentStore(),
    budget: Int = 1,
) {
    val origin = FakeOrigin(fixture::bytesFor)
    val bridgeEvents = CopyOnWriteArrayList<PlaybackBridgeEvent>()
    val fetchEvents = CopyOnWriteArrayList<FetchEvent>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val index: CoverageIndex = CoverageIndex.forTest(store.loader)

    val broker = FetchBroker(
        publisher = store.publisher,
        executor = origin,
        attemptBudget = FetchAttemptBudget(budget),
        sessionId = "jvm-bridge",
        eventListener = FetchEventListener { fetchEvents += it },
        ownerScope = scope,
        ownsScope = true,
        monotonicClockNs = { 1L },
    )

    lateinit var runtime: PlaybackBridgeRuntime
        private set

    fun start(): BridgeHarness {
        runBlocking { index.refresh() }
        runtime = PlaybackBridgeRuntime(
            plan = fixture.plan,
            coverageIndex = index,
            broker = broker,
            reader = store.reader,
            sessionId = "jvm-bridge",
            clockNs = { 1L },
            listener = PlaybackBridgeEventListener { bridgeEvents += it },
        )
        return this
    }

    fun readAll(
        key: String,
        position: Long = 0,
        length: Long = PlaybackReadSession.LENGTH_UNSET,
        bufferSize: Int = 64,
    ): Pair<ByteArray, List<Int>> {
        val out = ByteArrayOutputStream()
        val sizes = mutableListOf<Int>()
        runtime.newReadSession().use { session ->
            session.open(key, position, length)
            val buffer = ByteArray(bufferSize)
            while (true) {
                val read = session.read(buffer, 0, buffer.size)
                sizes += read
                if (read == PlaybackReadSession.END_OF_INPUT) {
                    break
                }
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray() to sizes
    }

    fun events(kind: PlaybackBridgeEventKind): List<PlaybackBridgeEvent> =
        bridgeEvents.filter { it.event == kind }

    fun awaitCondition(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not reached in ${timeoutMs}ms" }
            Thread.sleep(5)
        }
    }

    fun shutdown() {
        runBlocking { broker.shutdown() }
    }
}
