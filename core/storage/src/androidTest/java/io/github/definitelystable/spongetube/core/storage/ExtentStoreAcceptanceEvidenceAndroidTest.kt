package io.github.definitelystable.spongetube.core.storage

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * API 36 canonical raw-evidence producer for M1-ACC-01/02/03.
 *
 * The host-side verifier remains independent: this harness only performs the
 * real Room/filesystem stimulus and exports a quiescent storage snapshot plus
 * lifecycle/recovery observations.
 */
@RunWith(AndroidJUnit4::class)
class ExtentStoreAcceptanceEvidenceAndroidTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var databaseFile: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.filesDir, "sponge")
        databaseFile = File(root, "metadata/extents.db")
        cleanRoot()
    }

    @After
    fun tearDown() {
        cleanRoot()
    }

    @Test
    fun acc01PublishedExtentRetainsLifecycleAndStorageEvidence() = runBlocking {
        val sessionId = "m1-b-acc-01"
        val spec = spec("acc01")
        val bytes = PAYLOAD
        val events = mutableListOf<Map<String, Any?>>()
        val sequence = AtomicLong()

        val store = openStore(
            lifecycleListener = listener(sessionId, spec, events, sequence),
        )
        store.writeExtent(spec) {
            write(bytes)
        }
        assertEquals(1, store.committedExtents().size)
        store.close()

        val reopened = openStore()
        val recovery = reopened.initialRecoveryReport
        assertEquals(1, recovery.verifiedPublishedExtents)
        assertEquals(1, reopened.committedExtents().size)
        reopened.close()

        assertEquals(
            listOf("RECEIVING", "SEALED", "VERIFIED", "DURABLE", "PUBLISHED"),
            events.map { it.getValue("state") },
        )

        exportCase(
            caseId = "acc-01",
            sessionId = sessionId,
            gateId = "M1-ACC-01",
            spec = spec,
            events = events,
            recovery = recovery,
            expectedPlayableEndUs = TEN_SECONDS_US,
            expectedQuarantinedExtents = 0,
        )
    }

    @Test
    fun acc02EveryCrashBoundaryRetainsPostRecoveryEvidence() = runBlocking {
        for (point in ExtentFaultPoint.entries) {
            cleanRoot()

            val caseId = "acc-02-" + point.name.lowercase()
            val sessionId = "m1-b-" + caseId
            val spec = spec(caseId)
            val events = mutableListOf<Map<String, Any?>>()
            val sequence = AtomicLong()

            val store = openStore(
                lifecycleListener = listener(sessionId, spec, events, sequence),
                faultInjector = ExtentFaultInjector { observed ->
                    if (observed == point) {
                        throw SimulatedProcessCrash(point)
                    }
                },
            )

            val crash = expectThrows<SimulatedProcessCrash> {
                store.writeExtent(spec) {
                    write(PAYLOAD)
                }
            }
            assertEquals(point, crash.point)
            store.close()

            val reopened = openStore()
            val recovery = reopened.initialRecoveryReport
            val committed = reopened.committedExtents()
            val shouldBePublished =
                point == ExtentFaultPoint.AFTER_METADATA_COMMIT_BEFORE_PUBLISHED_EVENT ||
                    point == ExtentFaultPoint.AFTER_PUBLISH

            assertEquals(
                point.name,
                if (shouldBePublished) 1 else 0,
                committed.size,
            )
            reopened.close()

            exportCase(
                caseId = caseId,
                sessionId = sessionId,
                gateId = "M1-ACC-02",
                spec = spec,
                events = events,
                recovery = recovery,
                expectedPlayableEndUs =
                    if (shouldBePublished) TEN_SECONDS_US else null,
                expectedQuarantinedExtents = 0,
                faultPoint = point.name,
            )
        }
    }

    @Test
    fun acc03MissingAndCorruptPublishedFilesRetainQuarantineEvidence() =
        runBlocking {
            for (mutation in listOf("MISSING", "CORRUPT")) {
                cleanRoot()

                val caseId = "acc-03-" + mutation.lowercase()
                val sessionId = "m1-b-" + caseId
                val spec = spec(caseId)
                val events = mutableListOf<Map<String, Any?>>()
                val sequence = AtomicLong()

                val store = openStore(
                    lifecycleListener = listener(sessionId, spec, events, sequence),
                )
                val committed = store.writeExtent(spec) {
                    write(PAYLOAD)
                }
                store.close()

                val file = File(
                    root,
                    ExtentPathLayout(root).finalRelativePath(committed.extentId),
                )
                assertTrue(file.isFile)
                when (mutation) {
                    "MISSING" -> assertTrue(file.delete())
                    "CORRUPT" -> file.writeBytes("corrupt".encodeToByteArray())
                }

                val reopened = openStore()
                val recovery = reopened.initialRecoveryReport
                assertEquals(mutation, 1, recovery.quarantinedExtents)
                assertTrue(reopened.committedExtents().isEmpty())
                reopened.close()

                exportCase(
                    caseId = caseId,
                    sessionId = sessionId,
                    gateId = "M1-ACC-03",
                    spec = spec,
                    events = events,
                    recovery = recovery,
                    expectedPlayableEndUs = null,
                    expectedQuarantinedExtents = 1,
                    mutation = mutation,
                )
            }
        }

    private suspend fun openStore(
        lifecycleListener: ExtentLifecycleListener? = null,
        faultInjector: ExtentFaultInjector = ExtentFaultInjector.NONE,
    ): ExtentStore =
        ExtentStore.openAndroidForTest(
            context = context,
            rootDirectory = root,
            databaseFile = databaseFile,
            lifecycleListener = lifecycleListener,
            faultInjector = faultInjector,
        )

    private fun listener(
        sessionId: String,
        spec: ExtentSpec,
        events: MutableList<Map<String, Any?>>,
        sequence: AtomicLong,
    ): ExtentLifecycleListener =
        ExtentLifecycleListener { event ->
            events += linkedMapOf(
                "schemaVersion" to 1,
                "eventSequence" to sequence.getAndIncrement(),
                "eventElapsedRealtimeNs" to SystemClock.elapsedRealtimeNanos(),
                "sessionId" to sessionId,
                "extentId" to event.extentId.value,
                "fetchId" to null,
                "state" to event.state.name,
                "integrityState" to when (event.state) {
                    ExtentLifecycleState.VERIFIED,
                    ExtentLifecycleState.DURABLE,
                    ExtentLifecycleState.PUBLISHED,
                    -> "VALID"
                    ExtentLifecycleState.RECEIVING,
                    ExtentLifecycleState.SEALED,
                    -> "UNKNOWN"
                },
                "trackId" to spec.trackId,
                "representationId" to spec.representationId,
                "mediaStartUs" to spec.mediaStartUs,
                "mediaEndUs" to spec.mediaEndUs,
                "dependencyExtentIds" to JSONArray(
                    spec.dependencyExtentIds.map(ExtentId::value),
                ),
                "byteStart" to spec.byteStart,
                "byteEndExclusive" to spec.byteEndExclusive,
                "expectedLength" to spec.expectedLength,
                "actualLength" to event.actualLength,
                "sha256" to event.sha256?.hex,
                "storagePath" to
                    if (
                        event.state == ExtentLifecycleState.DURABLE ||
                        event.state == ExtentLifecycleState.PUBLISHED
                    ) {
                        ExtentPathLayout(root).finalRelativePath(spec.extentId)
                    } else {
                        null
                    },
            )
        }

    private fun exportCase(
        caseId: String,
        sessionId: String,
        gateId: String,
        spec: ExtentSpec,
        events: List<Map<String, Any?>>,
        recovery: RecoveryReport,
        expectedPlayableEndUs: Long?,
        expectedQuarantinedExtents: Int,
        faultPoint: String? = null,
        mutation: String? = null,
    ) {
        if (Build.VERSION.SDK_INT < 36) {
            return
        }

        val output = PlatformTestStorageRegistry.getInstance()
        val prefix = "m1-b-evidence/$caseId"

        output.openOutputFile("$prefix/case.json").bufferedWriter().use { writer ->
            writer.write(
                JSONObject(
                    linkedMapOf(
                        "schemaVersion" to 1,
                        "gateId" to gateId,
                        "caseId" to caseId,
                        "sessionId" to sessionId,
                        "mediaAssetId" to spec.mediaAssetId.value,
                        "trackId" to spec.trackId,
                        "representationId" to spec.representationId,
                        "playheadUs" to 0,
                        "expectedPlayableEndUs" to expectedPlayableEndUs,
                        "expectedQuarantinedExtents" to expectedQuarantinedExtents,
                        "faultPoint" to faultPoint,
                        "mutation" to mutation,
                    ),
                ).toString(2),
            )
            writer.newLine()
        }

        output.openOutputFile("$prefix/recovery-report.json")
            .bufferedWriter().use { writer ->
                writer.write(
                    JSONObject(
                        linkedMapOf(
                            "deletedPartFiles" to recovery.deletedPartFiles,
                            "deletedOrphanFiles" to recovery.deletedOrphanFiles,
                            "deletedQuarantinedFiles" to recovery.deletedQuarantinedFiles,
                            "quarantinedExtents" to recovery.quarantinedExtents,
                            "verifiedPublishedExtents" to
                                recovery.verifiedPublishedExtents,
                        ),
                    ).toString(2),
                )
                writer.newLine()
            }

        output.openOutputFile("$prefix/extent-events-v1.jsonl")
            .bufferedWriter().use { writer ->
                for (event in events) {
                    writer.write(JSONObject(event).toString())
                    writer.newLine()
                }
            }

        check(root.isDirectory) { "storage root missing for $caseId" }
        root.walkTopDown()
            .filter(File::isFile)
            .forEach { source ->
                val relative = source.relativeTo(root).invariantSeparatorsPath
                output.openOutputFile("$prefix/storage/$relative").use { destination ->
                    source.inputStream().use { input ->
                        input.copyTo(destination)
                    }
                }
            }
    }

    private fun spec(id: String): ExtentSpec =
        ExtentSpec(
            mediaAssetId = MediaAssetId(ASSET_ID),
            extentId = ExtentId(id),
            trackId = TRACK_ID,
            representationId = REPRESENTATION_ID,
            mediaStartUs = 0,
            mediaEndUs = TEN_SECONDS_US,
            byteStart = 0,
            byteEndExclusive = PAYLOAD.size.toLong(),
            expectedLength = PAYLOAD.size.toLong(),
            expectedSha256 = Sha256.digest(PAYLOAD),
        )

    private fun cleanRoot() {
        root.deleteRecursively()
    }

    private companion object {
        const val ASSET_ID = "fixture:M1-B"
        const val TRACK_ID = "video-main"
        const val REPRESENTATION_ID = "m1-b-video"
        const val TEN_SECONDS_US = 10_000_000L
        val PAYLOAD = "m1-b-canonical-evidence".encodeToByteArray()
    }
}

private suspend inline fun <reified T : Throwable> expectThrows(
    crossinline block: suspend () -> Unit,
): T {
    try {
        block()
    } catch (error: Throwable) {
        if (error is T) {
            return error
        }
        throw AssertionError(
            "expected " + T::class.java.name +
                ", got " + error::class.java.name,
            error,
        )
    }
    throw AssertionError("expected " + T::class.java.name)
}
