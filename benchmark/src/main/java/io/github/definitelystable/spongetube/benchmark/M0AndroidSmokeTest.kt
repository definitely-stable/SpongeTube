package io.github.definitelystable.spongetube.benchmark

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class M0AndroidSmokeTest {

    @Test
    fun directPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val benchmarkContext = instrumentation.context
        val device = UiDevice.getInstance(instrumentation)
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments
            .getString(RUN_ID_ARGUMENT)
            ?.takeIf { RUN_ID.matches(it) }
            ?: DEFAULT_RUN_ID
        val expectedTransport = arguments
            .getString(EXPECTED_TRANSPORT_ARGUMENT)
            ?.takeIf { EXPECTED_TRANSPORT.matches(it) }
            ?: "HTTP_ENGINE"
        val sessionId = "$runId-1"

        device.pressHome()
        device.executeShellCommand(
            "am force-stop ${BenchmarkToolchainContract.TARGET_PACKAGE}",
        )
        val launch = device.executeShellCommand(
            "am start -W -n " +
                "${BenchmarkToolchainContract.TARGET_PACKAGE}/.MainActivity " +
                "--es spongetube.runId $runId " +
                "--ez spongetube.autoPlay true",
        )
        assertTrue(
            "target activity did not report a successful launch: $launch",
            launch.contains("Status: ok"),
        )

        val loadButton = device.wait(
            Until.findObject(By.text("Load canonical F1")),
            10_000,
        )
        assertNotNull("smoke load button was not found", loadButton)
        loadButton.click()

        val playing = device.wait(
            Until.hasObject(By.textContains("playing")),
            50_000,
        )
        assertTrue("DIRECT baseline never reached playing state", playing)

        val expectedTransportVisible = device.wait(
            Until.hasObject(By.textContains(expectedTransport)),
            5_000,
        )
        assertTrue(
            "recommended transport did not resolve to $expectedTransport",
            expectedTransportVisible,
        )

        assertTrue(
            "smoke did not reach stable READY/playing before finalization",
            waitForStableReadyPlaying(device),
        )

        device.pressHome()
        device.waitForIdle()

        val staged = BenchmarkEvidenceExporter.exportCompleted(
            context = benchmarkContext,
            sessionId = sessionId,
            namespace = "android-smoke",
        )
        BenchmarkEvidenceExporter.evidenceFiles.forEach { artifactName ->
            val file = staged.resolve(artifactName)
            assertTrue(
                "smoke evidence missing or empty: $artifactName",
                file.isFile && file.length() > 0L,
            )
        }
    }

    private fun waitForStableReadyPlaying(device: UiDevice): Boolean {
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        var stableSinceMs: Long? = null

        while (SystemClock.elapsedRealtime() < deadline) {
            val now = SystemClock.elapsedRealtime()
            val readyAndPlaying = device.hasObject(
                By.textContains(READY_PLAYING_TEXT),
            )

            if (readyAndPlaying) {
                val started = stableSinceMs ?: now.also {
                    stableSinceMs = it
                }
                if (now - started >= STABLE_READY_WINDOW_MS) {
                    return true
                }
            } else {
                stableSinceMs = null
            }

            Thread.sleep(READY_POLL_MS)
        }

        return false
    }

    private companion object {
        const val RUN_ID_ARGUMENT = "spongetube.runId"
        const val EXPECTED_TRANSPORT_ARGUMENT =
            "spongetube.expectedTransport"
        const val DEFAULT_RUN_ID = "android-smoke"
        val RUN_ID = Regex("[A-Za-z0-9._-]+")
        val EXPECTED_TRANSPORT = Regex("[A-Z0-9_]+")
        const val READY_TIMEOUT_MS = 30_000L
        const val STABLE_READY_WINDOW_MS = 2_000L
        const val READY_POLL_MS = 100L
        const val READY_PLAYING_TEXT = "State: Ready · playing"
    }
}
