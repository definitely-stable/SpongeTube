package io.github.definitelystable.spongetube.core.engine.route

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M2-F0 feasibility only.
 *
 * Proves two separate facts on API 36:
 * 1. the app's actual default route can be lost and restored with a new epoch;
 * 2. an actual VPN default route can carry a real HTTP Range request through
 *    the TUN datapath, after which a test-only relay binds the upstream leg to
 *    the pre-VPN physical Network and reaches the real namespace Media Lab.
 *
 * It deliberately does not wire RecoveryCoordinator or claim M2-F acceptance.
 */
@RunWith(AndroidJUnit4::class)
class RouteVpnFeasibilityAndroidTest {
    @Test(timeout = TEST_TIMEOUT_MS)
    fun provesActualRouteLossRestoreAndUsableVpnMediaPath() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT == 36)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val connectivity = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
        val originBaseUrl = InstrumentationRegistry.getArguments()
            .getString(ORIGIN_ARGUMENT)
            ?.trimEnd('/')
        assumeTrue("M2-F0 requires $ORIGIN_ARGUMENT", !originBaseUrl.isNullOrBlank())
        val origin = URL(originBaseUrl)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recorder = RouteEvidenceRecorder(
            runId = "m2-f0-api36",
            sessionId = "m2-f0-api36-session",
            androidApi = Build.VERSION.SDK_INT,
        )
        val monitor = AndroidDefaultRouteMonitor.open(context, scope, recorder)
        val device = UiDevice.getInstance(instrumentation)

        var relay: OriginRelay? = null
        var monitorStopped = false
        try {
            val initial = awaitRoute(monitor, vpn = ObservedBoolean.FALSE)
            recorder.evaluate(SessionRouteGuard(), monitor.observations.value)

            setConnectivityEnabled(instrumentation, enabled = false)
            val unavailable = withTimeout(ROUTE_TIMEOUT_MS) {
                monitor.observations.first { it.state == DefaultRouteState.Unavailable }
            }
            assertTrue(unavailable.sequence > 0)

            setConnectivityEnabled(instrumentation, enabled = true)
            val restored = awaitRoute(
                monitor,
                vpn = ObservedBoolean.FALSE,
                minimumEpochExclusive = initial.routeEpoch,
            )
            assertTrue(restored.routeEpoch > initial.routeEpoch)

            val underlying = checkNotNull(connectivity.activeNetwork)
            val underlyingCaps = checkNotNull(connectivity.getNetworkCapabilities(underlying))
            assertTrue(
                "restored underlay must be a non-VPN network",
                underlyingCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
            )

            prepareVpn(context, device)
            val preferences = context.getSharedPreferences(
                RouteVpnFeasibilityVpnService.PREFERENCES,
                Context.MODE_PRIVATE,
            )
            preferences.edit().clear().commit()

            context.startForegroundService(
                Intent(context, RouteVpnFeasibilityVpnService::class.java)
                    .setAction(RouteVpnFeasibilityVpnService.ACTION_START),
            )
            val vpnRoute = awaitRoute(
                monitor,
                vpn = ObservedBoolean.TRUE,
                minimumEpochExclusive = restored.routeEpoch,
            )
            assertTrue(vpnRoute.routeEpoch > restored.routeEpoch)

            relay = OriginRelay(
                underlying = underlying,
                originHost = checkNotNull(origin.host),
                originPort = if (origin.port >= 0) origin.port else origin.defaultPort,
            ).also { it.start() }

            val syntheticUrl =
                "http://" + RouteVpnFeasibilityVpnService.SYNTHETIC_MEDIA_HOST +
                    ":" + relay.localPort + MEDIA_PATH
            val connection = URL(syntheticUrl).openConnection() as HttpURLConnection
            val body = try {
                connection.requestMethod = "GET"
                connection.connectTimeout = HTTP_TIMEOUT_MS
                connection.readTimeout = HTTP_TIMEOUT_MS
                connection.setRequestProperty("Range", RANGE_HEADER)
                connection.setRequestProperty("Connection", "close")

                assertEquals(HttpURLConnection.HTTP_PARTIAL, connection.responseCode)
                assertEquals(EXPECTED_CONTENT_RANGE, connection.getHeaderField("Content-Range"))
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }

            assertEquals(RANGE_BYTES, body.size)
            assertFalse(body.all { it == 0.toByte() })
            relay.awaitSuccess()

            context.startService(
                Intent(context, RouteVpnFeasibilityVpnService::class.java)
                    .setAction(RouteVpnFeasibilityVpnService.ACTION_STOP),
            )
            val directAfterVpn = awaitRoute(
                monitor,
                vpn = ObservedBoolean.FALSE,
                minimumEpochExclusive = vpnRoute.routeEpoch,
            )
            assertTrue(directAfterVpn.routeEpoch > vpnRoute.routeEpoch)

            withTimeout(VPN_STOP_TIMEOUT_MS) {
                while (!preferences.contains(RouteVpnFeasibilityVpnService.KEY_REFLECTED_PACKETS)) {
                    delay(50)
                }
            }
            val reflectedPackets = preferences.getLong(
                RouteVpnFeasibilityVpnService.KEY_REFLECTED_PACKETS,
                0L,
            )
            val reflectedBytes = preferences.getLong(
                RouteVpnFeasibilityVpnService.KEY_REFLECTED_BYTES,
                0L,
            )
            assertTrue("VPN TUN must reflect TCP packets", reflectedPackets > 0)
            assertTrue("VPN TUN must reflect bytes", reflectedBytes > 0)

            monitor.shutdown()
            monitorStopped = true
            assertTrue(monitor.isClosed)

            writeEvidence(
                recorder = recorder,
                summary = linkedMapOf(
                    "schemaVersion" to 1,
                    "phase" to "M2-F0",
                    "deviceApi" to Build.VERSION.SDK_INT,
                    "initialDirectEpoch" to initial.routeEpoch,
                    "actualRouteLossObserved" to true,
                    "restoredDirectEpoch" to restored.routeEpoch,
                    "vpnEpoch" to vpnRoute.routeEpoch,
                    "vpnObserved" to true,
                    "vpnMediaStatus" to HttpURLConnection.HTTP_PARTIAL,
                    "vpnMediaBytes" to body.size,
                    "vpnMediaBodySha256" to sha256(body),
                    "vpnTunReflectedPackets" to reflectedPackets,
                    "vpnTunReflectedBytes" to reflectedBytes,
                    "postVpnDirectEpoch" to directAfterVpn.routeEpoch,
                    "mediaPathUsesAdbReverse" to false,
                    "monitorStopped" to true,
                    "status" to "PASS",
                ),
            )
        } finally {
            relay?.close()
            runCatching {
                context.startService(
                    Intent(context, RouteVpnFeasibilityVpnService::class.java)
                        .setAction(RouteVpnFeasibilityVpnService.ACTION_STOP),
                )
            }
            runCatching { setConnectivityEnabled(instrumentation, enabled = true) }
            if (!monitorStopped) {
                runCatching { monitor.shutdown() }
            }
            scope.cancel()
        }
    }

    private suspend fun awaitRoute(
        monitor: DefaultRouteMonitor,
        vpn: ObservedBoolean,
        minimumEpochExclusive: Long = 0L,
    ): DefaultRouteState.Available =
        withTimeout(ROUTE_TIMEOUT_MS) {
            monitor.observations.first { observation ->
                val state = observation.state as? DefaultRouteState.Available
                state != null &&
                    state.capabilitiesReceived &&
                    state.routeEpoch > minimumEpochExclusive &&
                    state.capabilities.vpn == vpn &&
                    state.capabilities.internet != ObservedBoolean.FALSE
            }.state as DefaultRouteState.Available
        }

    private suspend fun setConnectivityEnabled(
        instrumentation: Instrumentation,
        enabled: Boolean,
    ) {
        val commands = if (enabled) {
            listOf(
                "cmd connectivity airplane-mode disable",
                "svc data enable",
                "svc wifi enable",
            )
        } else {
            listOf(
                "svc wifi disable",
                "svc data disable",
                "cmd connectivity airplane-mode enable",
            )
        }
        for (command in commands) {
            shell(instrumentation, command)
        }
        delay(SHELL_SETTLE_MS)
    }

    private fun shell(
        instrumentation: Instrumentation,
        command: String,
    ): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }

    private suspend fun prepareVpn(context: Context, device: UiDevice) {
        val prepare = VpnService.prepare(context)
        if (prepare != null) {
            val dialogPackage = checkNotNull(prepare.component?.packageName) {
                "VpnService.prepare returned an intent without a component package"
            }
            val resourceIdRegex = "android:id/button1$|button_start_vpn"

            RouteVpnFeasibilityConsentActivity.resetResult()
            context.startActivity(
                Intent(context, RouteVpnFeasibilityConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            device.waitForIdle()

            val allow = device.findObject(
                UiSelector()
                    .className("android.widget.Button")
                    .packageName(dialogPackage)
                    .resourceIdMatches(resourceIdRegex),
            )
            val allowFound = allow.waitForExists(VPN_CONSENT_TIMEOUT_MS)
            if (!allowFound) {
                writeVpnConsentDiagnostics(
                    context = context,
                    device = device,
                    dialogPackage = dialogPackage,
                    prepareComponent = prepare.component?.flattenToShortString(),
                    reason = "ALLOW_BUTTON_NOT_FOUND",
                )
            }
            assertTrue(
                "VPN confirmation allow button not found; package=$dialogPackage " +
                    "resourceIdRegex=$resourceIdRegex",
                allowFound,
            )
            assertTrue("VPN confirmation click failed", allow.click())
            device.waitForIdle()

            val resultCode = RouteVpnFeasibilityConsentActivity.awaitResult(VPN_CONSENT_TIMEOUT_MS)
            if (resultCode != Activity.RESULT_OK) {
                writeVpnConsentDiagnostics(
                    context = context,
                    device = device,
                    dialogPackage = dialogPackage,
                    prepareComponent = prepare.component?.flattenToShortString(),
                    reason = "CONSENT_RESULT_$resultCode",
                )
            }
            assertEquals(
                "VPN confirmation dialog did not return RESULT_OK",
                Activity.RESULT_OK,
                resultCode,
            )

            withTimeout(VPN_CONSENT_TIMEOUT_MS) {
                while (VpnService.prepare(context) != null) {
                    delay(100)
                }
            }
        }
        assertTrue("VPN package is not prepared", VpnService.prepare(context) == null)
    }

    private fun writeVpnConsentDiagnostics(
        context: Context,
        device: UiDevice,
        dialogPackage: String,
        prepareComponent: String?,
        reason: String,
    ) {
        val output = PlatformTestStorageRegistry.getInstance()
        output.openOutputFile("$EVIDENCE_DIR/vpn-consent-debug.json")
            .bufferedWriter()
            .use { writer ->
                writer.write(
                    JSONObject(
                        linkedMapOf(
                            "schemaVersion" to 1,
                            "phase" to "M2-F0",
                            "reason" to reason,
                            "dialogPackage" to dialogPackage,
                            "prepareComponent" to prepareComponent,
                            "currentPackageName" to device.currentPackageName,
                        ),
                    ).toString(2),
                )
                writer.newLine()
            }

        val hierarchy = File(context.cacheDir, "m2-f0-vpn-consent-window.xml")
        runCatching {
            device.dumpWindowHierarchy(hierarchy)
            output.openOutputFile("$EVIDENCE_DIR/vpn-consent-window.xml").use { destination ->
                hierarchy.inputStream().use { source -> source.copyTo(destination) }
            }
        }
        hierarchy.delete()

        val screenshot = File(context.cacheDir, "m2-f0-vpn-consent.png")
        runCatching {
            if (device.takeScreenshot(screenshot)) {
                output.openOutputFile("$EVIDENCE_DIR/vpn-consent.png").use { destination ->
                    screenshot.inputStream().use { source -> source.copyTo(destination) }
                }
            }
        }
        screenshot.delete()
    }

    private fun writeEvidence(
        recorder: RouteEvidenceRecorder,
        summary: Map<String, Any?>,
    ) {
        val output = PlatformTestStorageRegistry.getInstance()
        output.openOutputFile("$EVIDENCE_DIR/feasibility.json")
            .bufferedWriter()
            .use { writer ->
                writer.write(JSONObject(summary).toString(2))
                writer.newLine()
            }
        output.openOutputFile("$EVIDENCE_DIR/route-events.json")
            .bufferedWriter()
            .use { writer ->
                writer.write(JSONObject(recorder.toArtifactMap()).toString(2))
                writer.newLine()
            }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private class OriginRelay(
        private val underlying: Network,
        private val originHost: String,
        private val originPort: Int,
    ) : AutoCloseable {
        private val failure = AtomicReference<Throwable?>()
        private val server = ServerSocket(0, 1, InetAddress.getByName("0.0.0.0"))
        private lateinit var worker: Thread

        val localPort: Int
            get() = server.localPort

        fun start() {
            worker = thread(name = "M2-F0-OriginRelay", start = true) {
                try {
                    server.accept().use { client ->
                        client.soTimeout = HTTP_TIMEOUT_MS
                        Socket().use { upstream ->
                            underlying.bindSocket(upstream)
                            upstream.connect(
                                InetSocketAddress(originHost, originPort),
                                HTTP_TIMEOUT_MS,
                            )
                            upstream.soTimeout = HTTP_TIMEOUT_MS

                            val clientInput = BufferedInputStream(client.getInputStream())
                            val clientOutput = BufferedOutputStream(client.getOutputStream())
                            val upstreamInput = BufferedInputStream(upstream.getInputStream())
                            val upstreamOutput = BufferedOutputStream(upstream.getOutputStream())

                            val requestHeaders = readHeaders(clientInput)
                            upstreamOutput.write(requestHeaders)
                            upstreamOutput.flush()

                            val responseHeaders = readHeaders(upstreamInput)
                            val contentLength = parseContentLength(responseHeaders)
                            clientOutput.write(responseHeaders)
                            copyExactly(upstreamInput, clientOutput, contentLength)
                            clientOutput.flush()
                        }
                    }
                } catch (error: Throwable) {
                    if (!server.isClosed) {
                        failure.compareAndSet(null, error)
                    }
                }
            }
        }

        fun awaitSuccess() {
            worker.join(RELAY_TIMEOUT_MS)
            assertFalse("origin relay did not terminate", worker.isAlive)
            failure.get()?.let { throw AssertionError("origin relay failed", it) }
        }

        override fun close() {
            runCatching { server.close() }
            if (::worker.isInitialized) {
                worker.interrupt()
                worker.join(2_000)
            }
        }

        private fun readHeaders(input: BufferedInputStream): ByteArray {
            val output = ByteArrayOutputStream()
            var matched = 0
            val delimiter = byteArrayOf(
                '\r'.code.toByte(),
                '\n'.code.toByte(),
                '\r'.code.toByte(),
                '\n'.code.toByte(),
            )
            while (output.size() < MAX_HEADER_BYTES) {
                val value = input.read()
                check(value >= 0) { "HTTP stream ended before headers completed" }
                output.write(value)
                if (value.toByte() == delimiter[matched]) {
                    matched += 1
                    if (matched == delimiter.size) {
                        return output.toByteArray()
                    }
                } else {
                    matched = if (value.toByte() == delimiter[0]) 1 else 0
                }
            }
            error("HTTP headers exceed $MAX_HEADER_BYTES bytes")
        }

        private fun parseContentLength(headers: ByteArray): Int {
            val text = String(headers, StandardCharsets.ISO_8859_1)
            val value = text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.lowercase(Locale.US).startsWith("content-length:") }
                ?.substringAfter(':')
                ?.trim()
                ?: error("origin response is missing Content-Length")
            return value.toInt().also { require(it >= 0) }
        }

        private fun copyExactly(
            input: BufferedInputStream,
            output: BufferedOutputStream,
            bytes: Int,
        ) {
            var remaining = bytes
            val buffer = ByteArray(8192)
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                check(count > 0) { "origin response ended with $remaining bytes remaining" }
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
    }

    private companion object {
        const val ORIGIN_ARGUMENT = "spongetube.m2f0.originBaseUrl"
        const val EVIDENCE_DIR = "m2-f0-route-feasibility"
        const val MEDIA_PATH = "/fixtures/F0/progressive.mp4"
        const val RANGE_HEADER = "bytes=0-4095"
        const val EXPECTED_CONTENT_RANGE = "bytes 0-4095/1054544"
        const val RANGE_BYTES = 4096

        const val ROUTE_TIMEOUT_MS = 30_000L
        const val VPN_CONSENT_TIMEOUT_MS = 10_000L
        const val VPN_STOP_TIMEOUT_MS = 10_000L
        const val HTTP_TIMEOUT_MS = 10_000
        const val RELAY_TIMEOUT_MS = 15_000L
        const val SHELL_SETTLE_MS = 750L
        const val MAX_HEADER_BYTES = 64 * 1024
        const val TEST_TIMEOUT_MS = 120_000L
    }
}
