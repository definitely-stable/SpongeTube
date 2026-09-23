package io.github.definitelystable.spongetube.core.engine

import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import io.github.definitelystable.spongetube.core.storage.Sha256Digest
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@OptIn(SpongeBridgeApi::class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class PlaybackReadSessionTest {
    private val harnesses = mutableListOf<BridgeHarness>()

    @AfterEach
    fun tearDown() {
        harnesses.forEach(BridgeHarness::shutdown)
    }

    private fun harness(budget: Int = 1): BridgeHarness =
        BridgeHarness(budget = budget).also(harnesses::add)

    // 1
    @Test
    fun publishedExtentIsServedLocallyWithoutBroker() {
        val h = harness()
        val f = h.fixture
        f.seed(h.store, f.videoInit, f.video[0])
        h.start()

        val (bytes, sizes) = h.readAll("v-1")

        assertArrayEquals(f.bytesOf(f.video[0]), bytes)
        assertEquals(listOf(10, -1), sizes)
        assertTrue(h.origin.executions.isEmpty())
        assertTrue(h.fetchEvents.isEmpty())
        assertEquals(
            listOf(
                PlaybackBridgeEventKind.OPEN,
                PlaybackBridgeEventKind.LOCAL_SERVE,
                PlaybackBridgeEventKind.CLOSE,
            ),
            h.bridgeEvents.map(PlaybackBridgeEvent::event),
        )
    }

    // 2
    @Test
    fun oneOpenReadsAcrossAdjacentExtentsWithoutFalseEndOfInput() {
        val h = harness()
        val f = h.fixture
        f.seed(h.store, *f.audioParts.toTypedArray())
        h.start()

        val (bytes, sizes) = h.readAll("a-split", position = 5)

        val expected = f.resourceBytes("a-split", 30).copyOfRange(5, 30)
        assertArrayEquals(expected, bytes)
        // Short reads stop exactly at extent boundaries; EOF only at the end.
        assertEquals(listOf(5, 10, 10, -1), sizes)
        assertEquals(3, h.events(PlaybackBridgeEventKind.LOCAL_SERVE).size)
        assertEquals(0, h.store.openHandles.get())
    }

    // 3
    @Test
    fun lengthUnsetUsesPlanLengthAndOutOfRangeIsNotClamped() {
        val h = harness()
        val f = h.fixture
        f.seed(h.store, f.videoInit, f.video[0])
        h.start()

        h.runtime.newReadSession().use { session ->
            assertEquals(4L, session.open("v-1", 6, PlaybackReadSession.LENGTH_UNSET))
        }
        h.runtime.newReadSession().use { session ->
            assertEquals(0L, session.open("v-1", 10, PlaybackReadSession.LENGTH_UNSET))
            assertEquals(
                PlaybackReadSession.END_OF_INPUT,
                session.read(ByteArray(8), 0, 8),
            )
        }
        val beyond = assertThrows(PlaybackBridgeException::class.java) {
            h.runtime.newReadSession().use { it.open("v-1", 11, PlaybackReadSession.LENGTH_UNSET) }
        }
        assertEquals(PlaybackBridgeFailure.POSITION_OUT_OF_RANGE, beyond.failure)
        val tooLong = assertThrows(PlaybackBridgeException::class.java) {
            h.runtime.newReadSession().use { it.open("v-1", 5, 6) }
        }
        assertEquals(PlaybackBridgeFailure.POSITION_OUT_OF_RANGE, tooLong.failure)
        val unknown = assertThrows(PlaybackBridgeException::class.java) {
            h.runtime.newReadSession().use { it.open("nope", 0, 1) }
        }
        assertEquals(PlaybackBridgeFailure.UNKNOWN_RESOURCE, unknown.failure)
    }

    // 4
    @Test
    fun absentExtentIsFetchedAsPlaybackThenReadLocally() {
        val h = harness()
        val f = h.fixture
        f.seed(h.store, f.videoInit)
        h.start()

        val (bytes, _) = h.readAll("v-2")

        assertArrayEquals(f.bytesOf(f.video[1]), bytes)
        assertEquals(listOf(f.video[1].fetchKey.value), h.origin.executions.toList())
        val miss = h.events(PlaybackBridgeEventKind.MISS).single()
        assertEquals("t:v:2", miss.extentId)
        val waitEnd = h.events(PlaybackBridgeEventKind.FETCH_WAIT_END).single()
        assertEquals(miss.fetchId, waitEnd.fetchId)
        assertEquals("SUCCESS", waitEnd.fetchOutcome)
        assertTrue(h.events(PlaybackBridgeEventKind.JOIN).isEmpty())

        val owner = h.fetchEvents.first { it.event == FetchEventKind.OWNER_REGISTERED }
        assertEquals(miss.fetchId, owner.fetchId.value)
        assertEquals(FetchPriority.PLAYBACK, owner.effectivePriority)
        assertEquals(listOf("bridge:r1:t:v:2"), owner.consumerIds)
        // MISS -> wait -> LOCAL_SERVE ordering.
        val kinds = h.bridgeEvents.map(PlaybackBridgeEvent::event)
        assertTrue(
            kinds.indexOf(PlaybackBridgeEventKind.FETCH_WAIT_END) <
                kinds.indexOf(PlaybackBridgeEventKind.LOCAL_SERVE),
        )
    }

    // 5
    @Test
    fun playbackJoinsInFlightReserveFetchAndRaisesPriority() {
        val h = harness()
        val f = h.fixture
        f.seed(h.store, f.videoInit)
        h.start()
        val key = f.video[1].fetchKey
        val gate = h.origin.gate(key)

        val reserve = runBlocking { h.runtime.acquireReserve(key, "harness-reserve") }
        assertFalse(reserve.joinedExisting)
        runBlocking { h.origin.entered(key).await() }

        val result = AtomicReference<ByteArray>()
        val reader = Thread { result.set(h.readAll("v-2").first) }
        reader.start()
        h.awaitCondition { h.events(PlaybackBridgeEventKind.JOIN).isNotEmpty() }
        gate.complete(Unit)
        reader.join(10_000)

        assertArrayEquals(f.bytesOf(f.video[1]), result.get())
        assertEquals("SUCCESS", runBlocking { reserve.awaitOutcome() })
        val join = h.events(PlaybackBridgeEventKind.JOIN).single()
        assertEquals(reserve.fetchId, join.fetchId)
        assertTrue(h.events(PlaybackBridgeEventKind.MISS).isEmpty())
        assertEquals(1, h.origin.executions.size)
        assertEquals(
            1,
            h.fetchEvents.count { it.event == FetchEventKind.PRIORITY_RAISED },
        )
        assertEquals(
            1,
            h.fetchEvents.count { it.event == FetchEventKind.ATTEMPT_STARTED },
        )
    }

    // 6
    @Test
    fun missingInitFetchesOnlyDependencyAndNeverRefetchesPublishedSegment() {
        val h = harness()
        val f = h.fixture
        f.seed(h.store, f.video[0])
        h.start()

        val (bytes, _) = h.readAll("v-1")

        assertArrayEquals(f.bytesOf(f.video[0]), bytes)
        assertEquals(listOf(f.videoInit.fetchKey.value), h.origin.executions.toList())
        val dependencyWait = h.events(PlaybackBridgeEventKind.DEPENDENCY_WAIT).single()
        assertEquals("t:v:1", dependencyWait.extentId)
        assertEquals("t:v:init", dependencyWait.dependencyExtentId)
        assertEquals("t:v:init", h.events(PlaybackBridgeEventKind.MISS).single().extentId)
        assertTrue(
            h.events(PlaybackBridgeEventKind.FETCH_WAIT_END)
                .none { it.fetchOutcome == "STORAGE_CONFLICT" },
        )
    }

    // 7
    @Test
    fun staleIndexStorageConflictRefreshesAndReadsLocally() {
        val h = harness()
        val f = h.fixture
        f.seed(h.store, f.videoInit)
        h.start()
        // Another publisher commits after the bridge projection was built.
        f.seed(h.store, f.video[1])

        val (bytes, _) = h.readAll("v-2")

        assertArrayEquals(f.bytesOf(f.video[1]), bytes)
        assertEquals(
            "STORAGE_CONFLICT",
            h.events(PlaybackBridgeEventKind.FETCH_WAIT_END).single().fetchOutcome,
        )
        assertEquals(1, h.events(PlaybackBridgeEventKind.LOCAL_SERVE).size)
    }

    @Test
    fun publishedExtentIdCollisionFailsClosedBeforeReadOrFetch() {
        val h = harness()
        val f = h.fixture
        f.seed(h.store, f.videoInit)
        val expected = f.video[0]
        val foreignSpec = expected.extentSpec.copy(
            mediaAssetId = MediaAssetId("fixture:OTHER"),
        )
        h.store.seed(foreignSpec, f.bytesOf(expected))
        h.start()

        val error = assertThrows(PlaybackBridgeException::class.java) {
            h.readAll("v-1")
        }

        assertEquals(PlaybackBridgeFailure.FETCH_IDENTITY_CONFLICT, error.failure)
        assertTrue(h.origin.executions.isEmpty())
        assertEquals(0, h.store.openReadCalls.get())
        assertTrue(h.fetchEvents.isEmpty())
    }

    // 8
    @Test
    fun quarantinedExtentIsRepairedThroughBroker() {
        val h = harness()
        val f = h.fixture
        f.seed(h.store, f.videoInit, f.video[0])
        h.start()
        h.store.quarantineOnNextOpen(f.video[0].extentId)

        val (bytes, _) = h.readAll("v-1")

        assertArrayEquals(f.bytesOf(f.video[0]), bytes)
        assertEquals(listOf(f.video[0].fetchKey.value), h.origin.executions.toList())
        assertEquals(2, h.store.openReadCalls.get())
        val close = h.events(PlaybackBridgeEventKind.CLOSE).single()
        assertEquals(2, close.openReadCalls)
    }

    // 9
    @Test
    fun interruptWhileWaitingReleasesLeaseAndCancelsSoleOwner() {
        val h = harness()
        val f = h.fixture
        f.seed(h.store, f.videoInit)
        h.start()
        h.origin.gate(f.video[1].fetchKey)

        val failure = AtomicReference<Throwable>()
        val reader = Thread {
            try {
                h.readAll("v-2")
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        reader.start()
        h.awaitCondition { h.events(PlaybackBridgeEventKind.MISS).isNotEmpty() }
        reader.interrupt()
        reader.join(5_000)

        assertFalse(reader.isAlive)
        assertInstanceOf(InterruptedIOException::class.java, failure.get())
        h.awaitCondition {
            h.fetchEvents.any {
                it.event == FetchEventKind.OWNER_CANCELLED &&
                    it.outcome == FetchOutcomeKind.CANCELLED_NO_CONSUMERS
            }
        }
        assertEquals(0, h.broker.activeFetchCountForTest())
        assertEquals(1, h.events(PlaybackBridgeEventKind.CLOSE).size)
        assertFalse(h.store.contains(f.video[1].extentId))
    }

    // 10
    @Test
    fun closeIsSafeWithoutOpenAfterFailedOpenAndTwice() {
        val h = harness()
        h.start()

        h.runtime.newReadSession().close()
        val failed = h.runtime.newReadSession()
        assertThrows(PlaybackBridgeException::class.java) { failed.open("missing", 0, 1) }
        failed.close()
        failed.close()

        assertTrue(h.events(PlaybackBridgeEventKind.CLOSE).isEmpty())
        assertTrue(h.events(PlaybackBridgeEventKind.OPEN).isEmpty())
    }

    // 11
    @Test
    fun terminalFetchFailureSurfacesTypedOutcome() {
        val h = harness()
        val f = h.fixture
        f.seed(h.store, f.videoInit)
        h.start()
        h.origin.failure = FetchAttemptDisposition.Failure(
            kind = FetchOutcomeKind.RANGE_REJECTED,
            retryable = false,
        )

        val error = assertThrows(PlaybackBridgeException::class.java) { h.readAll("v-2") }

        assertEquals(PlaybackBridgeFailure.FETCH_FAILED, error.failure)
        assertEquals("RANGE_REJECTED", error.fetchOutcome)
    }

    // 12
    @Test
    fun readsFromOpenExtentDoNotTouchMetadata() {
        val h = harness()
        val f = h.fixture
        f.seed(h.store, f.videoInit, f.video[0])
        h.start()
        val loadsAfterStart = h.store.committedExtentsCalls.get()

        val (bytes, sizes) = h.readAll("v-1", bufferSize = 1)

        assertArrayEquals(f.bytesOf(f.video[0]), bytes)
        assertEquals(11, sizes.size)
        assertEquals(loadsAfterStart, h.store.committedExtentsCalls.get())
        assertEquals(1, h.store.openReadCalls.get())
        val close = h.events(PlaybackBridgeEventKind.CLOSE).single()
        assertEquals(1, close.openReadCalls)
        assertEquals(0, close.coverageRefreshCalls)
        assertEquals(10L, close.bytesLocal)
    }

    @Test
    fun inlineManifestIsServedWithoutFetchAndDigestMismatchFailsClosed() {
        val h = harness()
        h.start()

        val (bytes, _) = h.readAll("manifest.mpd")

        assertArrayEquals(h.fixture.manifestBytes, bytes)
        assertTrue(h.fetchEvents.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            InlinePlaybackResource(
                key = "manifest.mpd",
                bytes = "<MPD/>\r\n".toByteArray(),
                expectedLength = 8,
                expectedSha256 = Sha256Digest(sha256Hex(h.fixture.manifestBytes)),
            )
        }
    }

    @Test
    fun planRejectsGapsAndDuplicateIdentities() {
        val f = TestPlanFixture()
        assertThrows(IllegalArgumentException::class.java) {
            ExtentPlaybackResource("gap", 30, f.audioParts.drop(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackPlan(
                f.asset,
                listOf(
                    ExtentPlaybackResource("one", 10, listOf(f.video[0])),
                    ExtentPlaybackResource("two", 10, listOf(f.video[0])),
                ),
            )
        }
    }
}
