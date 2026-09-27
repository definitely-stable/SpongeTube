package io.github.definitelystable.spongetube.core.engine.route

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor

/**
 * Test-only M2-F0 VPN. It is packaged only in androidTest and is not a product
 * VPN implementation.
 *
 * The synthetic route is carried by a TUN. TCP packets are reflected back to
 * the device, where the test relay opens the real media connection explicitly
 * on the pre-VPN physical Network. A successful media fetch therefore proves
 * both a usable VPN datapath and an actual origin request.
 */
class RouteVpnFeasibilityVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null
    private var reflector: RouteVpnPacketReflector? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProbeVpn()
            ACTION_STOP -> {
                stopProbeVpn()
                stopSelf()
            }
        }
        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        stopProbeVpn()
        super.onDestroy()
    }

    private fun startProbeVpn() {
        if (tunnel != null) {
            return
        }

        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("SpongeTube M2-F0")
                .setContentText("Route/VPN feasibility probe")
                .setOngoing(true)
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        val connectivity = checkNotNull(getSystemService(ConnectivityManager::class.java))
        val underlying = checkNotNull(connectivity.activeNetwork) {
            "M2-F0 requires a usable pre-VPN default network"
        }

        val established = Builder()
            .setSession("SpongeTube M2-F0")
            .setMtu(MTU)
            .addAddress(VPN_CLIENT_ADDRESS, VPN_PREFIX_LENGTH)
            .addRoute(SYNTHETIC_MEDIA_NETWORK, SYNTHETIC_MEDIA_PREFIX_LENGTH)
            .setUnderlyingNetworks(arrayOf(underlying))
            .establish()
            ?: error("VpnService.Builder.establish returned null")

        val packetReflector = RouteVpnPacketReflector(established.fileDescriptor, MTU)
        tunnel = established
        reflector = packetReflector
        packetReflector.start()
    }

    private fun stopProbeVpn() {
        val packetReflector = reflector
        val descriptor = tunnel
        reflector = null
        tunnel = null

        descriptor?.close()
        packetReflector?.interrupt()
        packetReflector?.join(REFLECTOR_JOIN_MS)

        if (packetReflector != null) {
            getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .edit()
                .putLong(KEY_REFLECTED_PACKETS, packetReflector.reflectedPackets())
                .putLong(KEY_REFLECTED_BYTES, packetReflector.reflectedBytes())
                .commit()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        val manager = checkNotNull(getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                "M2-F0 route feasibility",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val ACTION_START =
            "io.github.definitelystable.spongetube.core.engine.route.M2_F0_VPN_START"
        const val ACTION_STOP =
            "io.github.definitelystable.spongetube.core.engine.route.M2_F0_VPN_STOP"
        const val PREFERENCES = "m2_f0_vpn_probe"
        const val KEY_REFLECTED_PACKETS = "reflectedPackets"
        const val KEY_REFLECTED_BYTES = "reflectedBytes"
        const val SYNTHETIC_MEDIA_HOST = "203.0.113.2"

        private const val SYNTHETIC_MEDIA_NETWORK = "203.0.113.0"
        private const val SYNTHETIC_MEDIA_PREFIX_LENGTH = 24
        private const val VPN_CLIENT_ADDRESS = "198.51.100.2"
        private const val VPN_PREFIX_LENGTH = 24
        private const val MTU = 1500
        private const val REFLECTOR_JOIN_MS = 2_000L
        private const val NOTIFICATION_ID = 0x4d324630
        private const val NOTIFICATION_CHANNEL = "m2-f0-route-feasibility"
    }
}
