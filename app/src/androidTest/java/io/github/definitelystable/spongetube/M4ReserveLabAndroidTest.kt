package io.github.definitelystable.spongetube

import android.app.Activity
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.definitelystable.spongetube.core.engine.CoverageEvidenceSnapshot
import io.github.definitelystable.spongetube.core.engine.CoverageIndex
import io.github.definitelystable.spongetube.core.engine.PlaybackRequirementSet
import io.github.definitelystable.spongetube.core.storage.ExtentStore
import io.github.definitelystable.spongetube.testsupport.fixture.f1.F1FixtureCatalog
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M4-A1c: black-box Compose UI evidence on the real debug app and Media Lab N0.
 * A second host oracle verifies the exported Room SQLite and immutable bytes.
 * It never trusts UI text or a locally derived resume time as its own oracle.
 */
@RunWith(AndroidJUnit4::class)
class M4ReserveLabAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device by lazy { UiDevice.getInstance(instrumentation) }
    private var activity: Activity? = null

    @Before
    fun prepareCleanStore() {
        check(File(context.filesDir, "sponge").deleteRecursively())
    }

    @After
    fun finishActivity() {
        activity?.let {
            instrumentation.runOnMainSync { it.finish() }
            instrumentation.waitForIdleSync()
        }
        activity = null
    }

    @Test
    fun optInUiShowsOnlyMeasuredRequiredTrackReserveAndRetainsIt() = runBlocking {
        open(M4ReserveLabActivity::class.java)
        expect("SpongeTube · M4 Reserve Lab")
        expect("Durable playable reserve: not measured")
        expect("Status: Disabled")
        assertFalse(
            device.hasObject(By.text("Fill reserve once")),
        )

        click("Enable experimental F1 reserve lab")
        expect("Status: Ready")
        expect("Durable playable reserve: 0 µs (0.000 s)")
        expect("Target: 8.000 s")
        click("Fill reserve once")
        expect("Status: Target reached", 90_000)
        expect("Durable playable reserve: 9941333 µs (9.941 s)")
        expect("Fetch keys admitted in last pass: 4")
        assertFalse(device.hasObject(By.textContains("Error:")))

        // A second explicit demand must not create network requests or
        // claim any new fetch; the independent host trace enforces that too.
        click("Fill reserve once")
        expect("Status: Target reached", 60_000)
        expect("Fetch keys admitted in last pass: 0")

        click("Disable lab")
        expect("Status: Disabled", 20_000)
        expect("Durable playable reserve: not measured")

        // A fresh runtime/store must re-observe the persisted A/V reserve
        // without automatically downloading anything.
        click("Enable experimental F1 reserve lab")
        expect("Status: Ready")
        expect("Durable playable reserve: 9941333 µs (9.941 s)")
        click("Disable lab")
        expect("Status: Disabled", 20_000)

        // Only after runtime shutdown do we independently reopen the
        // physical store and retain the actual database/extent bytes.
        val store = ExtentStore.open(context)
        val snapshot = try {
            val index = CoverageIndex(store)
            index.refresh()
            assertEquals(4, store.committedExtents().size)
            index.snapshot(
                PlaybackRequirementSet(
                    F1FixtureCatalog.ASSET,
                    F1FixtureCatalog.REQUIRED_REPRESENTATIONS,
                ),
                0,
            )
        } finally {
            store.close()
        }
        assertEquals(9_941_333L, snapshot.durableReserveUs)
        writeEvidence(
            CoverageEvidenceSnapshot(
                eventSequence = 0,
                eventElapsedRealtimeNs = 0,
                sessionId = SESSION,
                coverage = snapshot,
                playerBufferedAheadUs = null,
            ).toArtifactMap(),
        )
    }

    @Test
    fun normalM0LauncherRemainsAvailableAndUnmodified() {
        open(MainActivity::class.java)
        expect("SpongeTube · M0-C")
        assertFalse(device.hasObject(By.text("Fill reserve once")))
        assertFalse(device.hasObject(By.text("Enable experimental F1 reserve lab")))
    }

    private fun open(type: Class<out Activity>) {
        val intent = Intent(context, type)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
    }

    private fun click(label: String) {
        val button = device.wait(Until.findObject(By.text(label)), 15_000)
        checkNotNull(button) { "M4 UI button not visible: $label" }
        button.click()
    }

    private fun expect(text: String, timeout: Long = 20_000) {
        assertTrue(
            "Expected visible Android UI text: $text",
            device.wait(Until.hasObject(By.text(text)), timeout),
        )
    }

    private fun writeEvidence(runtime: Map<String, Any?>) {
        val sink = PlatformTestStorageRegistry.getInstance()
        fun save(path: String, body: String) {
            sink.openOutputFile("m4-a1c-evidence/$path")
                .bufferedWriter().use { it.write(body + "\n") }
        }

        val physicalStore = File(context.filesDir, "sponge")
        check(physicalStore.isDirectory)
        physicalStore.walkTopDown().filter(File::isFile).forEach { source ->
            val path = source.relativeTo(physicalStore).invariantSeparatorsPath
            sink.openOutputFile("m4-a1c-evidence/storage/$path")
                .use { output -> source.inputStream().use { it.copyTo(output) } }
        }
        save("runtime-coverage.json", JSONObject(runtime).toString(2))
        save("ui-summary.json", JSONObject()
            .put("schemaVersion", 1)
            .put("sessionId", SESSION)
            .put("source", "CoverageIndex")
            .put("targetReserveUs", 8_000_000L)
            .put("durableReserveUs", 9_941_333L)
            .put("firstPassFetchKeys", 4)
            .put("secondPassFetchKeys", 0)
            .put("afterDisableUnmeasured", true)
            .put("reopenWithoutNetwork", true)
            .put("baselineSeparate", true)
            .toString(2))
    }

    private companion object {
        const val SESSION = "m4-a1c-f1-debug"
    }
}
