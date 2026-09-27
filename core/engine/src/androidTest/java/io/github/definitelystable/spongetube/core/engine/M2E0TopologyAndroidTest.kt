package io.github.definitelystable.spongetube.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M2-E0 feasibility proof only.
 *
 * The media endpoint is reached directly through the emulator's normal outbound
 * networking. Only the test-control endpoint may use adb reverse. No production
 * recovery or transport policy is changed by this test.
 */
@RunWith(AndroidJUnit4::class)
class M2E0TopologyAndroidTest {
    @Test(timeout = TEST_TIMEOUT_MS)
    fun directNamespaceMediaPathAndControlIsolationAreReachable() {
        val arguments = InstrumentationRegistry.getArguments()
        val mediaBase = arguments.getString(MEDIA_BASE_ARGUMENT)?.trimEnd('/')
        val controlBase = arguments.getString(CONTROL_BASE_ARGUMENT)?.trimEnd('/')
        assumeTrue(
            "M2-E0 topology proof requires $MEDIA_BASE_ARGUMENT and $CONTROL_BASE_ARGUMENT",
            !mediaBase.isNullOrBlank() && !controlBase.isNullOrBlank(),
        )

        val media = getJson("${checkNotNull(mediaBase)}/probe")
        assertEquals(1, media.getInt("schemaVersion"))
        assertEquals(MEDIA_ENDPOINT_ID, media.getString("endpointId"))
        assertEquals(MEDIA_PAYLOAD, media.getString("payload"))

        val control = getJson("${checkNotNull(controlBase)}/__lab/config")
        assertEquals("N0", control.getString("profileId"))

        val evidence = JSONObject()
            .put("schemaVersion", 1)
            .put("runId", "m2-e0-api36-topology")
            .put("sessionId", "m2-e0-topology")
            .put("clockDomain", "ANDROID_MONOTONIC")
            .put("mediaPath", "DIRECT_NAMESPACE")
            .put("controlPath", "ADB_REVERSE_CONTROL_ONLY")
            .put("mediaEndpointIdentity", MEDIA_ENDPOINT_ID)
            .put("mediaReachable", true)
            .put("controlReachable", true)

        val encoded = evidence.toString(2) + "\n"
        assertTrue(!IP_OR_URL.containsMatchIn(encoded))
        PlatformTestStorageRegistry.getInstance()
            .openOutputFile("$EVIDENCE_DIR/topology.json")
            .bufferedWriter()
            .use { it.write(encoded) }
    }

    private fun getJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = IO_TIMEOUT_MS
            readTimeout = IO_TIMEOUT_MS
            useCaches = false
        }
        return try {
            assertEquals(HttpURLConnection.HTTP_OK, connection.responseCode)
            val body = connection.inputStream.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MEDIA_BASE_ARGUMENT = "spongetube.m2e0.mediaBaseUrl"
        const val CONTROL_BASE_ARGUMENT = "spongetube.m2e0.controlBaseUrl"
        const val EVIDENCE_DIR = "m2-e0-topology"
        const val MEDIA_ENDPOINT_ID = "M2_E0_NAMESPACE_MEDIA"
        const val MEDIA_PAYLOAD = "spongetube-m2e0-direct"
        const val IO_TIMEOUT_MS = 5_000
        const val TEST_TIMEOUT_MS = 30_000L

        val IP_OR_URL = Regex(
            """(?:[A-Za-z][A-Za-z0-9+.-]*://)|(?<![0-9.])(?:(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])\.){3}(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])(?![0-9.])""",
        )
    }
}
