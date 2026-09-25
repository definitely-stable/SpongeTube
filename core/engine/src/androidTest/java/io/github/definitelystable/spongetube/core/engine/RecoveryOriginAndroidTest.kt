package io.github.definitelystable.spongetube.core.engine

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryAttemptGate
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryAttemptPermit
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumer
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumerId
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumerKind
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryCoordinator
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryEvidenceRecorder
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryPermitReason
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryPolicy
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryTerminalReason
import io.github.definitelystable.spongetube.core.engine.recovery.toArtifactMap
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentSpec
import io.github.definitelystable.spongetube.core.storage.ExtentStore
import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import io.github.definitelystable.spongetube.core.storage.Sha256Digest
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M2-C Android Smoke proof (API 36, Media Lab N4R, no VPN/network switch).
 *
 * Controlled transient failure sequence through the production
 * RecoveryCoordinator (`sponge-recovery-v1`), FetchBroker, real
 * HttpRangeFetchExecutor and ExtentStore:
 *
 * ```text
 * body gate closed -> attempt 1 READ_TIMEOUT -> backoff
 *                  -> attempt 2 READ_TIMEOUT -> backoff
 * gate opened by the attempt gate before permit 3 -> attempt 3 SUCCESS
 * ```
 *
 * The body gate is opened from inside the RecoveryAttemptGate, so the
 * sequence does not depend on timing. Exported evidence is verified on the
 * host by `scripts/ci/verify-m2-c-recovery-evidence.sh device` against the
 * Media Lab request trace: exactly three physical origin requests, each with
 * exactly one REMOTE_ATTEMPT charge.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryOriginAndroidTest {
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
    fun transientTimeoutsRecoverWithinOneChainAndExactPhysicalRequests() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val originBaseUrl = arguments.getString(ORIGIN_BASE_URL_ARGUMENT)?.trimEnd('/')
        val controlBaseUrl = arguments.getString(CONTROL_BASE_URL_ARGUMENT)?.trimEnd('/')
        assumeTrue(
            "M2-C origin recovery requires $ORIGIN_BASE_URL_ARGUMENT and " +
                CONTROL_BASE_URL_ARGUMENT,
            !originBaseUrl.isNullOrBlank() && !controlBaseUrl.isNullOrBlank(),
        )
        val control = checkNotNull(controlBaseUrl)
        gateCommand(control, "open", "m2c-reset")
        gateCommand(control, "close", "m2c-close")

        val fetchEvents = CopyOnWriteArrayList<FetchEvent>()
        val recorder = RecoveryEvidenceRecorder(runId = RUN_ID, sessionId = SESSION_ID)
        val permits = AtomicInteger()
        val store = ExtentStore.open(context)
        val broker = FetchBroker(
            extentStore = store,
            executor = HttpRangeFetchExecutor(
                targetFor = {
                    HttpRangeTarget(
                        url = URL("$originBaseUrl/fixtures/F1/segment-1-00001.m4s"),
                        resourceLength = RESOURCE_LENGTH,
                    )
                },
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
            ),
            sessionId = SESSION_ID,
            eventListener = FetchEventListener { fetchEvents += it },
        )
        val coordinator = RecoveryCoordinator(
            broker = broker,
            sessionId = SESSION_ID,
            policy = RecoveryPolicy.DEFAULT,
            attemptGate = RecoveryAttemptGate {
                if (permits.incrementAndGet() == OPEN_BEFORE_PERMIT) {
                    withContext(Dispatchers.IO) { gateCommand(control, "open", "m2c-open") }
                }
                RecoveryAttemptPermit(
                    routeEpoch = null,
                    reason = RecoveryPermitReason("HARNESS_ORIGIN_GATE"),
                )
            },
            evidence = recorder,
        )

        try {
            val outcome = withTimeout(TEST_TIMEOUT_MS) {
                coordinator.acquire(
                    request(),
                    RecoveryConsumer(RecoveryConsumerId("playback-m2c"), RecoveryConsumerKind.PLAYBACK),
                ).await()
            }

            assertEquals(RecoveryTerminalReason.SUCCESS, outcome.terminalReason)
            assertEquals(EXPECTED_ATTEMPTS, permits.get())
            assertEquals(
                EXPECTED_ATTEMPTS,
                fetchEvents.count { it.event == FetchEventKind.ATTEMPT_STARTED },
            )
            assertEquals(
                listOf("READ_TIMEOUT", "READ_TIMEOUT"),
                recorder.failures().map { it.observation.toArtifactMap()["kind"] },
            )
            assertTrue(
                store.committedExtents().any { it.extentId.value == EXTENT_ID && it.length == RESOURCE_LENGTH },
            )
        } finally {
            coordinator.shutdown()
            broker.shutdown()
            store.close()
            runCatching { gateCommand(control, "open", "m2c-cleanup") }
        }

        writeEvidence(recorder, fetchEvents)
    }

    private fun request(): FetchRequest =
        FetchRequest(
            fetchKey = FetchKey("fixture:F1/audio-main/f1-audio-1/segment-1-00001"),
            extentSpec = ExtentSpec(
                mediaAssetId = MediaAssetId("fixture:F1"),
                extentId = ExtentId(EXTENT_ID),
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

    private fun gateCommand(
        controlBaseUrl: String,
        action: String,
        commandId: String,
    ) {
        val connection = URL("$controlBaseUrl/__lab/gate/media/$action")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONTROL_TIMEOUT_MS
            connection.readTimeout = CONTROL_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("X-Sponge-Gate-Command", commandId)
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "gate $action returned ${connection.responseCode}"
            }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun writeEvidence(
        recorder: RecoveryEvidenceRecorder,
        fetchEvents: List<FetchEvent>,
    ) {
        val policyId = RecoveryPolicy.DEFAULT.policyId
        val output = PlatformTestStorageRegistry.getInstance()
        fun write(name: String, text: String) {
            output.openOutputFile("$EVIDENCE_DIR/$name").bufferedWriter().use { it.write(text) }
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
                    "caseId" to "api36-transient-origin-recovery",
                    "policyId" to policyId,
                    "expectedPhysicalAttempts" to EXPECTED_ATTEMPTS,
                    "expectedTerminals" to mapOf("SUCCESS" to 1),
                ),
            ) + "\n",
        )
    }

    private fun cleanStoreRoot() {
        File(context.filesDir, "sponge").deleteRecursively()
    }

    private companion object {
        const val ORIGIN_BASE_URL_ARGUMENT = "spongetube.m2c.originBaseUrl"
        const val CONTROL_BASE_URL_ARGUMENT = "spongetube.m2c.controlBaseUrl"
        const val RUN_ID = "m2-c-api36-origin-recovery"
        const val SESSION_ID = "m2-c-origin-recovery"
        const val EVIDENCE_DIR = "m2-c-recovery-evidence"
        const val EXTENT_ID = "m2c:f1:audio:1:1"
        const val RESOURCE_LENGTH = 81_811L
        const val RESOURCE_SHA256 =
            "08ac93538dcb3f5eece5996b0abab1e4e7677afbc7b21cc3292a63c776ef4943"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 1_500
        const val CONTROL_TIMEOUT_MS = 5_000
        const val OPEN_BEFORE_PERMIT = 3
        const val EXPECTED_ATTEMPTS = 3
        const val TEST_TIMEOUT_MS = 60_000L

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
