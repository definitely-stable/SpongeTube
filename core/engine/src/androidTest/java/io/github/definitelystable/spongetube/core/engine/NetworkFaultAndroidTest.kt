package io.github.definitelystable.spongetube.core.engine

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import io.github.definitelystable.spongetube.core.engine.recovery.FailureClassification
import io.github.definitelystable.spongetube.core.engine.recovery.FailureObservation
import io.github.definitelystable.spongetube.core.engine.recovery.ObservationPlane
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumer
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumerId
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumerKind
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryCoordinator
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryEvidenceRecorder
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryPolicy
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryTerminalReason
import io.github.definitelystable.spongetube.core.engine.recovery.TransportIoKind
import io.github.definitelystable.spongetube.core.engine.recovery.toArtifactMap
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentSpec
import io.github.definitelystable.spongetube.core.storage.ExtentStore
import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import io.github.definitelystable.spongetube.core.storage.Sha256Digest
import java.io.File
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M2-E NETWORK proof through the E0-proven direct namespace/veth media path.
 *
 * The external harness owns the NETWORK cause. Production recovery still sees
 * only socket/body observations; this test deliberately does not invent a
 * runtime "packet loss" observation.
 */
@RunWith(AndroidJUnit4::class)
class NetworkFaultAndroidTest {
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
    fun packetFaultKeepsRecoveryBoundedAndPublishesOnlyCompleteExtent() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val baseUrl = args.getString(BASE_URL_ARGUMENT)?.trimEnd('/')
        val variant = args.getString(SCENARIO_ARGUMENT)
        assumeTrue(
            "M2-E network test requires $BASE_URL_ARGUMENT and $SCENARIO_ARGUMENT",
            !baseUrl.isNullOrBlank() && !variant.isNullOrBlank(),
        )

        val scenario = Scenario.parse(checkNotNull(variant))
        val runId = "m2-e-" + scenario.caseId
        val sessionId = runId + "-session"
        val fetchEvents = CopyOnWriteArrayList<FetchEvent>()
        val recorder = RecoveryEvidenceRecorder(runId, sessionId)
        val store = ExtentStore.open(context)
        val broker = FetchBroker(
            extentStore = store,
            executor = HttpRangeFetchExecutor(
                targetFor = {
                    HttpRangeTarget(
                        url = URL(baseUrl + "/fixtures/F1/segment-1-00001.m4s"),
                        resourceLength = RESOURCE_LENGTH,
                    )
                },
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
            ),
            sessionId = sessionId,
            eventListener = FetchEventListener { fetchEvents += it },
        )
        val coordinator = RecoveryCoordinator(
            broker = broker,
            sessionId = sessionId,
            policy = RecoveryPolicy.DEFAULT,
            evidence = recorder,
        )

        val startedNs = SystemClock.elapsedRealtimeNanos()
        try {
            val outcome = withTimeout(TEST_TIMEOUT_MS) {
                coordinator.acquire(
                    request(scenario.caseId),
                    RecoveryConsumer(
                        RecoveryConsumerId("playback-" + scenario.caseId),
                        RecoveryConsumerKind.PLAYBACK,
                    ),
                ).await()
            }
            val durationNs = SystemClock.elapsedRealtimeNanos() - startedNs
            val attempts = fetchEvents.count { it.event == FetchEventKind.ATTEMPT_STARTED }
            val failures = recorder.failures()
            val timeoutCount = failures.count {
                val observation = it.observation as? FailureObservation.TransportIo
                observation?.kind == TransportIoKind.CONNECT_TIMEOUT ||
                    observation?.kind == TransportIoKind.READ_TIMEOUT
            }

            assertEquals(
                "canonical packet scenarios must recover without a new retry owner",
                RecoveryTerminalReason.SUCCESS,
                outcome.terminalReason,
            )
            assertTrue("at least one physical attempt must start", attempts >= 1)
            assertTrue("RecoveryPolicy remote-attempt budget exceeded", attempts <= EXPECTED_MAX_ATTEMPTS)
            assertEquals(attempts - 1, failures.size)
            assertTrue(
                "packet causes must remain runtime transport observations",
                failures.all {
                    it.observation.plane == ObservationPlane.TRANSPORT &&
                        it.classification == FailureClassification.TRANSIENT_TRANSPORT
                },
            )
            assertFalse(
                "NETWORK cause must never appear as provider classification",
                failures.any {
                    it.classification.name.startsWith("PROVIDER_")
                },
            )

            val committed = store.committedExtents().filter {
                it.extentId.value == extentId(scenario.caseId)
            }
            assertEquals(1, committed.size)
            assertEquals(RESOURCE_LENGTH, committed.single().length)

            writeEvidence(
                scenario = scenario,
                recorder = recorder,
                fetchEvents = fetchEvents,
                attempts = attempts,
                failures = failures.size,
                timeoutCount = timeoutCount,
                durationNs = durationNs,
            )
        } finally {
            coordinator.shutdown()
            broker.shutdown()
            store.close()
        }
    }

    private fun request(caseId: String): FetchRequest =
        FetchRequest(
            fetchKey = FetchKey("fixture:F1/audio-main/f1-audio-1/" + caseId),
            extentSpec = ExtentSpec(
                mediaAssetId = MediaAssetId("fixture:F1"),
                extentId = ExtentId(extentId(caseId)),
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

    private fun writeEvidence(
        scenario: Scenario,
        recorder: RecoveryEvidenceRecorder,
        fetchEvents: List<FetchEvent>,
        attempts: Int,
        failures: Int,
        timeoutCount: Int,
        durationNs: Long,
    ) {
        val policyId = RecoveryPolicy.DEFAULT.policyId
        val root = "$EVIDENCE_ROOT/${scenario.caseId}"
        val output = PlatformTestStorageRegistry.getInstance()

        fun write(name: String, text: String) {
            output.openOutputFile("$root/$name").bufferedWriter().use { it.write(text) }
        }

        write("failure-decision-events.json", json(recorder.failureArtifact(policyId)) + "\n")
        write("recovery-budget-events.json", json(recorder.budgetArtifact(policyId)) + "\n")
        write(
            "fetch-events.jsonl",
            fetchEvents.sortedBy(FetchEvent::eventSequence)
                .joinToString(separator = "\n", postfix = "\n") { json(it.toArtifactMap()) },
        )
        write(
            "case.json",
            json(
                linkedMapOf(
                    "schemaVersion" to 1,
                    "caseId" to scenario.caseId,
                    "policyId" to policyId,
                    "expectedPhysicalAttempts" to attempts,
                    "expectedTerminals" to mapOf(RecoveryTerminalReason.SUCCESS.name to 1),
                ),
            ) + "\n",
        )
        write(
            "network-result.json",
            json(
                linkedMapOf(
                    "schemaVersion" to 1,
                    "caseId" to scenario.caseId,
                    "terminalReason" to RecoveryTerminalReason.SUCCESS.name,
                    "physicalAttempts" to attempts,
                    "failureCount" to failures,
                    "timeoutCount" to timeoutCount,
                    "published" to true,
                    "acquisitionDurationNs" to durationNs,
                    "clockDomain" to "ANDROID_MONOTONIC",
                ),
            ) + "\n",
        )
    }

    private fun cleanStoreRoot() {
        File(context.filesDir, "sponge").deleteRecursively()
    }

    private fun extentId(caseId: String) = "m2e:$caseId:f1:audio:1"

    private data class Scenario(val caseId: String) {
        companion object {
            fun parse(value: String): Scenario = when (value) {
                "HIGH_RTT_JITTER" -> Scenario("n2-high-rtt-jitter")
                "BURST_PACKET_LOSS" -> Scenario("n3-burst-packet-loss")
                "BURST_LOSS" -> Scenario("n5-burst-loss")
                else -> error("unsupported M2-E network scenario: $value")
            }
        }
    }

    private companion object {
        const val BASE_URL_ARGUMENT = "spongetube.m2e.networkBaseUrl"
        const val SCENARIO_ARGUMENT = "spongetube.m2e.scenario"
        const val EVIDENCE_ROOT = "m2-e-network"
        const val RESOURCE_LENGTH = 81_811L
        const val RESOURCE_SHA256 =
            "08ac93538dcb3f5eece5996b0abab1e4e7677afbc7b21cc3292a63c776ef4943"
        const val CONNECT_TIMEOUT_MS = 600
        const val READ_TIMEOUT_MS = 1_500
        const val EXPECTED_MAX_ATTEMPTS = 4
        const val TEST_TIMEOUT_MS = 30_000L

        fun json(value: Any?): String = when (value) {
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
                json(key.toString()) + ":" + json(item)
            }
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { json(it) }
            else -> error("unsupported JSON value: $value")
        }
    }
}
