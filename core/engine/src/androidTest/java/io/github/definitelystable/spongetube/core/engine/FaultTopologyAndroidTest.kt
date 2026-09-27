package io.github.definitelystable.spongetube.core.engine

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M2-E0 feasibility proof only.
 *
 * The media origin must be reachable through the emulator's ordinary outbound
 * networking and a host-routed veth/network namespace. The harness control
 * endpoint is intentionally separate and may use adb reverse. Host-side
 * verification independently proves that the media port is not reversed and
 * that the namespace netem counters advance during this request.
 */
@RunWith(AndroidJUnit4::class)
class FaultTopologyAndroidTest {
    @Test(timeout = TEST_TIMEOUT_MS)
    fun directMediaPathKeepsControlPlaneSeparate() {
        val arguments = InstrumentationRegistry.getArguments()
        val mediaBaseUrl = arguments.getString(MEDIA_BASE_URL_ARGUMENT)?.trimEnd('/')
        val controlBaseUrl = arguments.getString(CONTROL_BASE_URL_ARGUMENT)?.trimEnd('/')

        assumeTrue(
            "M2-E0 topology proof requires $MEDIA_BASE_URL_ARGUMENT and $CONTROL_BASE_URL_ARGUMENT",
            !mediaBaseUrl.isNullOrBlank() && !controlBaseUrl.isNullOrBlank(),
        )

        val media = fetch("$mediaBaseUrl/probe")
        val control = fetch("$controlBaseUrl/probe")

        assertEquals(HttpURLConnection.HTTP_OK, media.statusCode)
        assertEquals("media", media.role)
        assertEquals(EXPECTED_MEDIA_BODY, media.body)
        assertEquals(HttpURLConnection.HTTP_OK, control.statusCode)
        assertEquals("control", control.role)
        assertEquals(EXPECTED_CONTROL_BODY, control.body)

        val evidence = linkedMapOf<String, Any>(
            "schemaVersion" to 1,
            "deviceApi" to Build.VERSION.SDK_INT,
            "mediaPathMode" to "GUEST_OUTBOUND_ROUTED_VETH_NAMESPACE",
            "mediaUsesAdbReverse" to false,
            "controlUsesAdbReverse" to true,
            "mediaOk" to true,
            "controlOk" to true,
            "mediaBodySha256" to sha256(media.body),
            "controlBodySha256" to sha256(control.body),
        )
        val output = PlatformTestStorageRegistry.getInstance()
        output.openOutputFile("$EVIDENCE_DIR/evidence.json")
            .bufferedWriter()
            .use { writer ->
                writer.write(json(evidence))
                writer.write("\n")
            }

        assertTrue("media/control probes must be distinct", media.body != control.body)
    }

    private fun fetch(url: String): ProbeResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
        }
        try {
            val status = connection.responseCode
            val body = if (status in 200..299) {
                connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            }
            return ProbeResponse(
                statusCode = status,
                role = connection.getHeaderField("X-Sponge-E0-Role"),
                body = body,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is String -> buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    else -> append(char)
                }
            }
            append('"')
        }
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
            json(key.toString()) + ":" + json(item)
        }
        else -> error("unsupported JSON value: $value")
    }

    private data class ProbeResponse(
        val statusCode: Int,
        val role: String?,
        val body: String,
    )

    private companion object {
        const val MEDIA_BASE_URL_ARGUMENT = "spongetube.m2e.mediaBaseUrl"
        const val CONTROL_BASE_URL_ARGUMENT = "spongetube.m2e.controlBaseUrl"
        const val EVIDENCE_DIR = "m2-e0-topology"
        const val EXPECTED_MEDIA_BODY = "SPONGETUBE_M2_E0_MEDIA_V1\n"
        const val EXPECTED_CONTROL_BODY = "SPONGETUBE_M2_E0_CONTROL_V1\n"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 5_000
        const val TEST_TIMEOUT_MS = 30_000L
    }
}
