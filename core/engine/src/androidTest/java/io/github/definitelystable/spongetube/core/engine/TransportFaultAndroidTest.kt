package io.github.definitelystable.spongetube.core.engine

import android.content.Context
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
 * M2-E TRANSPORT proof through a real external Toxiproxy process.
 *
 * The harness owns the injected cause. Runtime evidence records only the typed
 * socket/body observation and never infers that a Toxiproxy toxic existed.
 */
@RunWith(AndroidJUnit4::class)
class TransportFaultAndroidTest {
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
    fun transportFaultUsesOneBoundedRecoveryChain() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val baseUrl = args.getString(BASE_URL_ARGUMENT)?.trimEnd('/')
        val scenarioName = args.getString(SCENARIO_ARGUMENT)
        assumeTrue(
            "M2-E transport test requires $BASE_URL_ARGUMENT and $SCENARIO_ARGUMENT",
            !baseUrl.isNullOrBlank() && !scenarioName.isNullOrBlank(),
        )
        val scenario = Scenario.parse(checkNotNull(scenarioName))
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
            val attempts = fetchEvents.count { it.event == FetchEventKind.ATTEMPT_STARTED }
            val failures = recorder.failures()

            if (scenario.expectsSuccess) {
                assertEquals(RecoveryTerminalReason.SUCCESS, outcome.terminalReason)
                assertEquals(1, attempts)
                assertTrue(failures.isEmpty())
                assertTrue(
                    store.committedExtents().any {
                        it.extentId.value == extentId(scenario.caseId) &&
                            it.length == RESOURCE_LENGTH
                    },
                )
            } else {
                assertEquals(RecoveryTerminalReason.BUDGET_EXHAUSTED, outcome.terminalReason)
                assertEquals(EXPECTED_ATTEMPTS, attempts)
                assertEquals(attempts, failures.size)
                assertTrue(
                    failures.all {
                        it.observation.plane == ObservationPlane.TRANSPORT &&
                            it.classification == FailureClassification.TRANSIENT_TRANSPORT
                    },
                )
                val kinds = failures.map {
                    (it.observation as FailureObservation.TransportIo).kind
                }.toSet()
                assertTrue(
                    "unexpected observations for " + scenario.caseId + ": " + kinds,
                    kinds.all { it in scenario.acceptedKinds },
                )
                assertFalse(
                    store.committedExtents().any {
                        it.extentId.value == extentId(scenario.caseId)
                    },
                )
            }

            writeEvidence(
                scenario,
                recorder,
                fetchEvents,
                attempts,
                outcome.terminalReason.name,
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
        terminal: String,
    ) {
        val policyId = RecoveryPolicy.DEFAULT.policyId
        val root = EVIDENCE_ROOT + "/" + scenario.caseId
        val output = PlatformTestStorageRegistry.getInstance()
        fun write(name: String, text: String) {
            output.openOutputFile(root + "/" + name).bufferedWriter().use { it.write(text) }
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
                    "expectedTerminals" to mapOf(terminal to 1),
                ),
            ) + "\n",
        )
        write(
            "transport-result.json",
            json(
                linkedMapOf(
                    "schemaVersion" to 1,
                    "caseId" to scenario.caseId,
                    "terminalReason" to terminal,
                    "physicalAttempts" to attempts,
                    "published" to (terminal == RecoveryTerminalReason.SUCCESS.name),
                    "clockDomain" to "ANDROID_MONOTONIC",
                ),
            ) + "\n",
        )
    }

    private fun cleanStoreRoot() {
        File(context.filesDir, "sponge").deleteRecursively()
    }

    private fun extentId(caseId: String) = "m2e:" + caseId + ":f1:audio:1"

    private data class Scenario(
        val caseId: String,
        val expectsSuccess: Boolean,
        val acceptedKinds: Set<TransportIoKind>,
    ) {
        companion object {
            fun parse(value: String): Scenario = when (value) {
                "TRANSPORT_READ_TIMEOUT" -> Scenario(
                    "transport-read-timeout",
                    false,
                    setOf(TransportIoKind.READ_TIMEOUT),
                )
                "TRANSPORT_RESET" -> Scenario(
                    "transport-reset",
                    false,
                    setOf(
                        TransportIoKind.CONNECTION_RESET,
                        TransportIoKind.IO,
                        TransportIoKind.PREMATURE_EOF,
                    ),
                )
                "TRUNCATED_STREAM" -> Scenario(
                    "truncated-stream",
                    false,
                    setOf(
                        TransportIoKind.PREMATURE_EOF,
                        TransportIoKind.CONNECTION_RESET,
                        TransportIoKind.IO,
                    ),
                )
                "SLOW_CLOSE" -> Scenario(
                    "slow-close",
                    true,
                    emptySet(),
                )
                else -> error("unsupported M2-E transport scenario: " + value)
            }
        }
    }

    private companion object {
        const val BASE_URL_ARGUMENT = "spongetube.m2e.transportBaseUrl"
        const val SCENARIO_ARGUMENT = "spongetube.m2e.scenario"
        const val EVIDENCE_ROOT = "m2-e-transport"
        const val RESOURCE_LENGTH = 81_811L
        const val RESOURCE_SHA256 =
            "08ac93538dcb3f5eece5996b0abab1e4e7677afbc7b21cc3292a63c776ef4943"
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 750
        const val EXPECTED_ATTEMPTS = 4
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
            else -> error("unsupported JSON value: " + value)
        }
    }
}
