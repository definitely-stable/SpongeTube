package io.github.definitelystable.spongetube.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class M1ProcessDeathRecoveryTest {

    @Test
    fun publishedCoverageSurvivesActualTargetProcessDeath() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val client = M1RecoveryHarnessClient(instrumentation.context)
        val device = UiDevice.getInstance(instrumentation)
        val sessionId = "m1-f-process-death"

        device.executeShellCommand(
            "am force-stop ${BenchmarkToolchainContract.TARGET_PACKAGE}",
        )
        launchTarget(device)
        val prepared = client.prepareProcessDeath(sessionId)
        val pidBefore = prepared.getInt(
            M1RecoveryHarnessClient.KEY_TARGET_PID,
        )
        assertTrue("target PID before kill is invalid", pidBefore > 0)

        device.executeShellCommand(
            "am force-stop ${BenchmarkToolchainContract.TARGET_PACKAGE}",
        )
        awaitCondition(PROCESS_TIMEOUT_MS, "target process termination") {
            pidOf(device).isEmpty()
        }

        launchTarget(device)
        val pidAfter = awaitPid(device)
        assertNotEquals(
            "process death must result in a new PID",
            pidBefore,
            pidAfter,
        )

        val recovered = client.recoverProcessDeath(
            sessionId = sessionId,
            pidBefore = pidBefore,
            pidAfter = pidAfter,
        )
        assertTrue(
            "recovery provider must run in PID-after",
            recovered.getInt(M1RecoveryHarnessClient.KEY_TARGET_PID) ==
                pidAfter,
        )
        client.exportEvidence(sessionId)
    }

    private fun launchTarget(device: UiDevice) {
        val launch = device.executeShellCommand(
            "am start -W -n " +
                "${BenchmarkToolchainContract.TARGET_PACKAGE}/.MainActivity",
        )
        assertTrue(
            "target launch failed: $launch",
            launch.contains("Status: ok"),
        )
    }

    private fun awaitPid(device: UiDevice): Int {
        var result = 0
        awaitCondition(PROCESS_TIMEOUT_MS, "new target PID") {
            result = pidOf(device).firstOrNull()?.toIntOrNull() ?: 0
            result > 0
        }
        return result
    }

    private fun pidOf(device: UiDevice): List<String> =
        device.executeShellCommand(
            "pidof ${BenchmarkToolchainContract.TARGET_PACKAGE}",
        ).trim().split(Regex("\\s+")).filter(String::isNotBlank)

    private fun awaitCondition(
        timeoutMs: Long,
        description: String,
        condition: () -> Boolean,
    ) {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) {
                "timed out waiting for $description"
            }
            Thread.sleep(100)
        }
    }

    private companion object {
        const val PROCESS_TIMEOUT_MS = 10_000L
    }
}
