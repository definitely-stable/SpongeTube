package io.github.definitelystable.spongetube.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class M0DPlaybackCaptureBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun directN0CorrectnessCapture() = benchmarkRule.measureRepeated(
        packageName = BenchmarkToolchainContract.TARGET_PACKAGE,
        metrics = listOf(
            StartupTimingMetric(),
            TraceSectionMetric(
                sectionName = PREPARE_TRACE,
                mode = TraceSectionMetric.Mode.Sum,
                label = "spongetubePrepare",
                targetPackageOnly = true,
            ),
            TraceSectionMetric(
                sectionName = PREPARE_TRACE,
                mode = TraceSectionMetric.Mode.Count,
                label = "spongetubePrepareCount",
                targetPackageOnly = true,
            ),
        ),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = 1,
        setupBlock = {
            pressHome()
        },
    ) {
        val runId = "m0d-macro-${iteration ?: 0}"
        val sessionId = "$runId-1"
        val benchmarkContext =
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
                .context

        startActivityAndWait { intent ->
            intent.putExtra("spongetube.runId", runId)
            intent.putExtra("spongetube.autoPlay", true)
        }

        val loadButton = device.wait(
            Until.findObject(By.text("Load canonical F1")),
            10_000,
        )
        assertNotNull("M0-D load button was not found", loadButton)
        loadButton.click()

        val playing = device.wait(
            Until.hasObject(By.textContains("playing")),
            20_000,
        )
        assertTrue("DIRECT baseline never reached playing state", playing)

        val httpEngine = device.wait(
            Until.hasObject(By.textContains("HTTP_ENGINE")),
            5_000,
        )
        assertTrue(
            "RECOMMENDED_PLATFORM did not resolve to HTTP_ENGINE",
            httpEngine,
        )

        pressHome()
        device.waitForIdle()

        val stagingDir = BenchmarkEvidenceExporter.exportCompleted(
            context = benchmarkContext,
            sessionId = sessionId,
            namespace = "m0-d",
        )
        BenchmarkEvidenceExporter.evidenceFiles.forEach { artifactName ->
            val staged = stagingDir.resolve(artifactName)
            assertTrue(
                "Staged evidence missing or empty: $artifactName",
                staged.isFile && staged.length() > 0L,
            )
        }
    }

    private companion object {
        const val PREPARE_TRACE = "SpongeTube:M0:prepare"
    }
}
