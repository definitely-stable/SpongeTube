package io.github.definitelystable.spongetube.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
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

        device.clickFresh(
            selector = By.text("Load canonical F1"),
            timeoutMs = 10_000,
        )

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
            "smoke did not reach a quiescent measurement state before finalization",
            BenchmarkEvidenceExporter.finishWhenQuiescent(
                context = benchmarkContext,
                sessionId = sessionId,
                timeoutMs = FINALIZE_TIMEOUT_MS,
            ),
        )

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

    private fun UiDevice.clickFresh(
        selector: BySelector,
        timeoutMs: Long,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastStale: StaleObjectException? = null

        while (SystemClock.uptimeMillis() < deadline) {
            val remainingMs = deadline - SystemClock.uptimeMillis()
            val candidate = wait(
                Until.findObject(selector),
                minOf(1_000L, remainingMs),
            ) ?: continue

            try {
                candidate.click()
                return
            } catch (error: StaleObjectException) {
                lastStale = error
                SystemClock.sleep(50)
            }
        }

        throw AssertionError(
            "smoke load button was not stably clickable within ${timeoutMs}ms",
            lastStale,
        )
    }

    private companion object {
        const val RUN_ID_ARGUMENT = "spongetube.runId"
        const val EXPECTED_TRANSPORT_ARGUMENT =
            "spongetube.expectedTransport"
        const val DEFAULT_RUN_ID = "android-smoke"
        val RUN_ID = Regex("[A-Za-z0-9._-]+")
        val EXPECTED_TRANSPORT = Regex("[A-Z0-9_]+")
        const val FINALIZE_TIMEOUT_MS = 30_000L
    }
}
