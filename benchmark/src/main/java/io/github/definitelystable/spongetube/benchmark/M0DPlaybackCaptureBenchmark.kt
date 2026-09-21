package io.github.definitelystable.spongetube.benchmark

import android.net.Uri
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
            InstrumentationRegistry.getInstrumentation().context
        val stagingDir = File(
            "/sdcard/Android/media/${benchmarkContext.packageName}" +
                "/additional_test_output/m0-d/$sessionId",
        )
        check(stagingDir.deleteRecursively())
        check(stagingDir.mkdirs())

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

        var evidenceReady = false
        var lastEvidenceFailure = "provider not attempted"
        for (attempt in 1..EVIDENCE_FINALIZE_ATTEMPTS) {
            val attemptResult = runCatching {
                EVIDENCE_FILES.forEach { artifactName ->
                    val uri = Uri.Builder()
                        .scheme("content")
                        .authority(EVIDENCE_AUTHORITY)
                        .appendPath(sessionId)
                        .appendPath(artifactName)
                        .build()
                    val target = File(stagingDir, artifactName)
                    benchmarkContext.contentResolver
                        .openInputStream(uri)
                        ?.use { input ->
                            target.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        ?: error("provider returned null stream for $artifactName")
                    check(target.length() > 0L) {
                        "empty evidence artifact: $artifactName"
                    }
                }
            }

            if (attemptResult.isSuccess) {
                evidenceReady = true
                break
            }

            lastEvidenceFailure =
                attemptResult.exceptionOrNull()?.toString() ?: "unknown failure"
            EVIDENCE_FILES.forEach { File(stagingDir, it).delete() }
            if (attempt < EVIDENCE_FINALIZE_ATTEMPTS) {
                Thread.sleep(EVIDENCE_FINALIZE_POLL_MS)
            }
        }

        assertTrue(
            "Target evidence was not exportable before benchmark teardown: " +
                lastEvidenceFailure,
            evidenceReady,
        )

        EVIDENCE_FILES.forEach { artifactName ->
            val staged = File(stagingDir, artifactName)
            assertTrue(
                "Staged evidence missing or empty: $artifactName",
                staged.isFile && staged.length() > 0L,
            )
        }
    }

    private companion object {
        const val PREPARE_TRACE = "SpongeTube:M0:prepare"
        const val EVIDENCE_AUTHORITY =
            "io.github.definitelystable.spongetube.m0.evidence"
        val EVIDENCE_FILES = listOf(
            "playback-events.jsonl",
            "playback-summary.json",
            "playback-stats-cross-check.json",
        )
        const val EVIDENCE_FINALIZE_ATTEMPTS = 80
        const val EVIDENCE_FINALIZE_POLL_MS = 100L
    }
}
