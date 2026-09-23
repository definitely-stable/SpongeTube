package io.github.definitelystable.spongetube.core.engine

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentSpec
import io.github.definitelystable.spongetube.core.storage.ExtentStore
import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import io.github.definitelystable.spongetube.core.storage.Sha256Digest
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FetchBrokerOriginAndroidTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        cleanStoreRoot()
    }

    @After
    fun tearDown() {
        cleanStoreRoot()
    }

    @Test
    fun playbackJoinsReserveOwnerAndOneOriginRequestPublishesExtent() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            val baseUrl = arguments
                .getString(ORIGIN_BASE_URL_ARGUMENT)
                ?.trimEnd('/')
            assumeTrue(
                "M1-D origin acceptance requires " +
                    ORIGIN_BASE_URL_ARGUMENT,
                !baseUrl.isNullOrBlank(),
            )

            val requestStarted = CompletableDeferred<Unit>()
            val allowBodyRead = CompletableDeferred<Unit>()
            val events = CopyOnWriteArrayList<FetchEvent>()
            val store = ExtentStore.open(context)
            val broker = FetchBroker(
                extentStore = store,
                executor = FetchAttemptExecutor {
                        request,
                        attempt,
                        priority,
                        emitChunk,
                    ->
                    check(attempt == 1)
                    val spec = request.extentSpec
                    val start = checkNotNull(spec.byteStart)
                    val endExclusive = checkNotNull(spec.byteEndExclusive)
                    val connection = (
                        URL(
                            baseUrl +
                                "/fixtures/F1/segment-1-00001.m4s",
                        ).openConnection() as HttpURLConnection
                        ).apply {
                        requestMethod = "GET"
                        connectTimeout = 10_000
                        readTimeout = 10_000
                        useCaches = false
                        setRequestProperty(
                            "Range",
                            "bytes=" + start + "-" + (endExclusive - 1),
                        )
                    }

                    try {
                        val status = connection.responseCode
                        check(status == HttpURLConnection.HTTP_PARTIAL) {
                            "expected 206, got " + status
                        }
                        val expectedContentRange =
                            "bytes 0-81810/81811"
                        check(
                            connection.getHeaderField("Content-Range") ==
                                expectedContentRange,
                        ) {
                            "unexpected Content-Range: " +
                                connection.getHeaderField("Content-Range")
                        }

                        val transportCorrelationId = checkNotNull(
                            connection.getHeaderField(
                                "X-Sponge-Lab-Request",
                            ),
                        )
                        requestStarted.complete(Unit)
                        allowBodyRead.await()

                        check(priority.value == FetchPriority.PLAYBACK) {
                            "playback join did not escalate owner priority"
                        }

                        var position = start
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(8 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) {
                                    break
                                }
                                if (read == 0) {
                                    continue
                                }
                                emitChunk(
                                    FetchNetworkChunk(
                                        byteStart = position,
                                        bytes = buffer.copyOf(read),
                                    ),
                                )
                                position += read
                            }
                        }
                        check(position == endExclusive) {
                            "origin body ended at " + position +
                                ", expected " + endExclusive
                        }

                        FetchAttemptDisposition.Success(
                            transportCorrelationId =
                                transportCorrelationId,
                        )
                    } finally {
                        connection.disconnect()
                    }
                },
                attemptBudget = FetchAttemptBudget(1),
                sessionId = SESSION_ID,
                eventListener = FetchEventListener { event -> events += event },
            )

            try {
                val request = FetchRequest(
                    fetchKey = FetchKey(
                        "fixture:F1/audio-main/f1-audio-1/" +
                            "segment-1-00001",
                    ),
                    extentSpec = ExtentSpec(
                        mediaAssetId = MediaAssetId("fixture:F1"),
                        extentId = ExtentId("m1d:f1:audio:1:1"),
                        trackId = "audio-main",
                        representationId = "f1-audio-1",
                        mediaStartUs = 0,
                        mediaEndUs = 9_941_333,
                        byteStart = 0,
                        byteEndExclusive = RESOURCE_LENGTH,
                        expectedLength = RESOURCE_LENGTH,
                        expectedSha256 = Sha256Digest(RESOURCE_SHA256),
                    ),
                )

                val reserve = broker.acquire(
                    request,
                    FetchConsumer(
                        FetchConsumerId("reserve-origin"),
                        FetchConsumerKind.RESERVE,
                    ),
                )
                requestStarted.await()

                val playback = broker.acquire(
                    request,
                    FetchConsumer(
                        FetchConsumerId("playback-origin"),
                        FetchConsumerKind.PLAYBACK,
                    ),
                )

                assertEquals(reserve.fetchId, playback.fetchId)
                allowBodyRead.complete(Unit)

                val reserveOutcome = reserve.await()
                val playbackOutcome = playback.await()
                assertEquals(FetchOutcomeKind.SUCCESS, reserveOutcome.kind)
                assertEquals(FetchOutcomeKind.SUCCESS, playbackOutcome.kind)
                assertEquals(1, reserveOutcome.attempts)
                assertEquals(RESOURCE_LENGTH, reserveOutcome.bytes.networkBytes)
                assertEquals(
                    RESOURCE_LENGTH,
                    reserveOutcome.bytes.uniqueRangeBytes,
                )
                assertEquals(0L, reserveOutcome.bytes.duplicateRangeBytes)

                val committed = store.committedExtents()
                assertTrue(
                    committed.any {
                        it.extentId.value == "m1d:f1:audio:1:1" &&
                            it.length == RESOURCE_LENGTH
                    },
                )

                assertEquals(
                    1,
                    events.count {
                        it.event == FetchEventKind.OWNER_REGISTERED
                    },
                )
                assertEquals(
                    1,
                    events.count {
                        it.event == FetchEventKind.CONSUMER_JOINED
                    },
                )
                assertEquals(
                    1,
                    events.count {
                        it.event == FetchEventKind.PRIORITY_RAISED
                    },
                )
                val completed = events.single {
                    it.event == FetchEventKind.ATTEMPT_COMPLETED
                }
                assertTrue(
                    !completed.transportCorrelationId.isNullOrBlank(),
                )

                writeFetchEvents(events)
            } finally {
                broker.shutdown()
                store.close()
            }
        }

    private fun writeFetchEvents(events: List<FetchEvent>) {
        val output = PlatformTestStorageRegistry.getInstance()
        output.openOutputFile(
            "m1-d-evidence/fetch-events-v2.jsonl",
        ).bufferedWriter().use { writer ->
            events.sortedBy(FetchEvent::eventSequence).forEach { event ->
                writer.write(JSONObject(event.toArtifactMap()).toString())
                writer.newLine()
            }
        }
    }

    private fun cleanStoreRoot() {
        File(context.filesDir, "sponge").deleteRecursively()
    }

    private companion object {
        const val ORIGIN_BASE_URL_ARGUMENT =
            "spongetube.m1d.originBaseUrl"
        const val SESSION_ID = "m1-d-origin"
        const val RESOURCE_LENGTH = 81_811L
        const val RESOURCE_SHA256 =
            "08ac93538dcb3f5eece5996b0abab1e4e7677afbc7b21cc3292a63c776ef4943"
    }
}
