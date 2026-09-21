package io.github.definitelystable.spongetube.benchmark

import android.util.JsonReader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.io.FileReader
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class M0AcceptanceCaseTest {

    @Test
    fun runAcceptanceCase() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val args = InstrumentationRegistry.getArguments()
        val runId = requireNotNull(args.getString(ARG_RUN_ID)) {
            "$ARG_RUN_ID is required"
        }
        val baseline = BaselineCase.valueOf(
            requireNotNull(args.getString(ARG_BASELINE)) {
                "$ARG_BASELINE is required"
            },
        )
        val expectOutage = args.getString(ARG_EXPECT_OUTAGE)
            ?.toBooleanStrictOrNull()
            ?: false
        require(RUN_ID.matches(runId)) {
            "unsupported run id: $runId"
        }

        val sessionId = "$runId-1"
        val device = UiDevice.getInstance(instrumentation)
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

        val baselineButton = device.wait(
            Until.findObject(By.text(baseline.buttonText)),
            10_000,
        )
        assertNotNull(
            "baseline button was not found: ${baseline.buttonText}",
            baselineButton,
        )
        if (baseline != BaselineCase.DIRECT) {
            baselineButton.click()
        }

        val loadButton = device.wait(
            Until.findObject(By.text("Load canonical F1")),
            10_000,
        )
        assertNotNull("acceptance load button was not found", loadButton)
        loadButton.click()

        val playing = device.wait(
            Until.hasObject(By.textContains("playing")),
            INITIAL_PLAY_TIMEOUT_MS,
        )
        assertTrue(
            "${baseline.name} never reached initial playing state",
            playing,
        )

        val httpEngine = device.wait(
            Until.hasObject(By.textContains("HTTP_ENGINE")),
            5_000,
        )
        assertTrue(
            "recommended transport did not resolve to HTTP_ENGINE",
            httpEngine,
        )

        if (expectOutage) {
            val rebuffer = device.wait(
                Until.hasObject(By.textContains("State: Buffering")),
                N4_REBUFFER_TIMEOUT_MS,
            )
            assertTrue(
                "${baseline.name} did not enter buffering during N4",
                rebuffer,
            )
            val resumed = device.wait(
                Until.hasObject(By.textContains("playing")),
                N4_RESUME_TIMEOUT_MS,
            )
            assertTrue(
                "${baseline.name} did not resume after canonical N4",
                resumed,
            )
        } else {
            Thread.sleep(NON_OUTAGE_OBSERVATION_MS)
        }

        device.pressHome()
        device.waitForIdle()

        val staged = BenchmarkEvidenceExporter.exportCompleted(
            context = context,
            sessionId = sessionId,
            namespace = "m0-acceptance",
        )

        BenchmarkEvidenceExporter.evidenceFiles.forEach { artifactName ->
            val file = staged.resolve(artifactName)
            assertTrue(
                "acceptance evidence missing or empty: $artifactName",
                file.isFile && file.length() > 0L,
            )
        }

        val cache = readCacheObservation(
            staged.resolve("baseline-observations.json"),
        )
        when (baseline) {
            BaselineCase.DIRECT -> {
                assertTrue(
                    "DIRECT must not use Standard Cache",
                    cache.atPreparation == 0L && cache.atEnd == 0L,
                )
            }

            BaselineCase.CACHE_COLD -> {
                assertTrue(
                    "CACHE_COLD must begin with zero cache bytes",
                    cache.atPreparation == 0L,
                )
                assertTrue(
                    "CACHE_COLD must populate cache bytes",
                    cache.atEnd > 0L,
                )
            }

            BaselineCase.CACHE_WARM -> {
                assertTrue(
                    "CACHE_WARM must inherit bytes from prior cold run",
                    cache.atPreparation > 0L,
                )
                assertTrue(
                    "CACHE_WARM end bytes cannot be negative",
                    cache.atEnd >= 0L,
                )
            }
        }
    }

    private fun readCacheObservation(file: File): CacheObservation {
        var atPreparation: Long? = null
        var atEnd: Long? = null
        var delta: Long? = null
        JsonReader(FileReader(file)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "cacheBytesAtPreparation" ->
                        atPreparation = reader.nextLong()
                    "cacheBytesAtEnd" ->
                        atEnd = reader.nextLong()
                    "cacheDeltaBytes" ->
                        delta = reader.nextLong()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }

        val preparation = checkNotNull(atPreparation)
        val end = checkNotNull(atEnd)
        val observedDelta = checkNotNull(delta)
        check(end - preparation == observedDelta) {
            "cache observation delta is inconsistent"
        }
        return CacheObservation(preparation, end)
    }

    private data class CacheObservation(
        val atPreparation: Long,
        val atEnd: Long,
    )

    private enum class BaselineCase(val buttonText: String) {
        DIRECT("DIRECT"),
        CACHE_COLD("CACHE COLD"),
        CACHE_WARM("CACHE WARM"),
    }

    private companion object {
        const val ARG_RUN_ID = "spongetube.runId"
        const val ARG_BASELINE = "spongetube.baseline"
        const val ARG_EXPECT_OUTAGE = "spongetube.expectOutage"
        val RUN_ID = Regex("[A-Za-z0-9._-]+")
        const val INITIAL_PLAY_TIMEOUT_MS = 60_000L
        const val NON_OUTAGE_OBSERVATION_MS = 8_000L
        const val N4_REBUFFER_TIMEOUT_MS = 60_000L
        const val N4_RESUME_TIMEOUT_MS = 150_000L
    }
}
