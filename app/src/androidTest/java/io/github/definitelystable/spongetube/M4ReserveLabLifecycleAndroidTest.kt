package io.github.definitelystable.spongetube

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.definitelystable.spongetube.core.storage.ExtentStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real N4R HTTP body gate; separate from the four-request N0 oracle. */
@RunWith(AndroidJUnit4::class)
class M4ReserveLabLifecycleAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device by lazy { UiDevice.getInstance(instrumentation) }

    @Test
    fun backgroundCancelsBlockedFillBeforeNewActivityOwnsStore() = runBlocking {
        val control = InstrumentationRegistry.getArguments()
            .getString("spongetube.m4ui.controlBaseUrl")
        assumeTrue("Requires isolated N4R Media Lab", !control.isNullOrBlank())
        val base = checkNotNull(control)
        check(File(context.filesDir, "sponge").deleteRecursively())
        var activity: M4ReserveLabActivity? = null
        try {
            withContext(Dispatchers.IO) { gate(base, "close", "m4-ui-lifecycle-close") }
            val first = open()
            activity = first
            click("Enable experimental F1 reserve lab")
            expect("Status: Ready")
            click("Fill reserve once")
            withTimeout(15_000) {
                while (true) {
                    val state = withContext(Dispatchers.IO) { gateState(base) }
                    if (state.getString("state") == "CLOSED" &&
                        state.getInt("activeBlockedRequests") > 0
                    ) break
                    delay(20)
                }
            }
            expect("Status: Filling (caller-owned pass)")
            assertTrue(device.pressHome())

            // Observe actual onStop cleanup while the first Activity is
            // still alive, not merely cleanup caused by destroying it.
            withTimeout(60_000) {
                while (true) {
                    var disabled = false
                    instrumentation.runOnMainSync {
                        disabled = first.sessionPhaseForTest == LabPhase.DISABLED
                        assertFalse("Activity destroyed before onStop proof", first.isDestroyed)
                    }
                    if (disabled) break
                    delay(50)
                }
            }
            val store = ExtentStore.open(context)
            try {
                assertTrue("Blocked data must not publish extents", store.committedExtents().isEmpty())
            } finally {
                store.close()
            }

            // Fresh Activity must remain opt-in and reopen the same
            // durable store while the physical gate is still closed.
            val second = open()
            activity = second
            expect("Status: Disabled")
            click("Enable experimental F1 reserve lab")
            expect("Status: Ready", 30_000)
            expect("Durable playable reserve: 0 µs (0.000 s)")
            click("Refresh coverage")
            expect("Status: Ready")
            click("Disable lab")
            expect("Status: Disabled", 30_000)
            withContext(Dispatchers.IO) { gate(base, "open", "m4-ui-lifecycle-restore") }
            Unit
        } finally {
            activity?.let { current ->
                instrumentation.runOnMainSync { current.finish() }
                instrumentation.waitForIdleSync()
            }
            withContext(Dispatchers.IO) {
                runCatching { gate(base, "open", "m4-ui-lifecycle-cleanup") }
            }
        }
    }

    private fun open(): M4ReserveLabActivity {
        val intent = Intent(context, M4ReserveLabActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return (instrumentation.startActivitySync(intent) as M4ReserveLabActivity).also {
            instrumentation.waitForIdleSync()
        }
    }

    private fun expect(label: String, timeout: Long = 20_000) {
        assertTrue("Expected UI text: $label", device.wait(Until.hasObject(By.text(label)), timeout))
    }

    private fun click(label: String) {
        var target = checkNotNull(device.wait(Until.findObject(By.text(label)), 15_000))
        while (!target.isClickable) target = checkNotNull(target.parent)
        assertTrue("Disabled button: $label", target.isEnabled)
        target.click()
    }

    private fun connection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            useCaches = false
        }

    private fun gate(base: String, action: String, id: String) {
        val conn = connection("$base/__lab/gate/media/$action")
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("X-Sponge-Gate-Command", id)
            assertEquals(200, conn.responseCode)
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun gateState(base: String): JSONObject {
        val conn = connection("$base/__lab/gate/media")
        try {
            assertEquals(200, conn.responseCode)
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }
}
