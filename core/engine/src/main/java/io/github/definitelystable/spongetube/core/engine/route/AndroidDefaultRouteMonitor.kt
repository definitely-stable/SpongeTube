package io.github.definitelystable.spongetube.core.engine.route

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope

/**
 * Android platform adapter for [DefaultRouteMonitor] (.work/milestones/M2.md 8.1).
 *
 * API 24+: `registerDefaultNetworkCallback()` (the app's default network,
 * physical or VPN), never a generic `registerNetworkCallback()`.
 * API 23: a context-registered `CONNECTIVITY_ACTION` receiver that only
 * triggers a conservative `activeNetwork` snapshot.
 *
 * `android.net.*` types never leave this file: the reducer only sees
 * [PlatformRouteRef] and [ObservedRouteCapabilities]. No location-sensitive
 * capabilities are requested (no `FLAG_INCLUDE_LOCATION_INFO`), and
 * LinkProperties content is never read.
 */
internal object AndroidDefaultRouteMonitor {
    fun open(
        context: Context,
        scope: CoroutineScope,
        listener: RouteEventListener? = null,
    ): DefaultRouteMonitor {
        val appContext = context.applicationContext ?: context
        val connectivity = checkNotNull(
            appContext.getSystemService(ConnectivityManager::class.java),
        ) { "ConnectivityManager unavailable" }
        val refs = PlatformRouteRefs()
        val platform = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            DefaultNetworkCallbackPlatform(connectivity, refs)
        } else {
            LegacyConnectivityActionPlatform(appContext, connectivity, refs)
        }
        return DefaultRouteMonitor.open(
            scope = scope,
            platform = platform,
            clock = SystemClock::elapsedRealtimeNanos,
            listener = listener,
        )
    }
}

/**
 * Maps one NetworkCapabilities observation. VPN comes only from
 * NOT_VPN (never from transport types: a VPN may also carry Wi-Fi/cellular).
 * `suspended` has a platform floor of API 28; below it the value is UNKNOWN.
 */
@SuppressLint("InlinedApi")
internal fun mapRouteCapabilities(
    sdkInt: Int,
    hasCapability: (Int) -> Boolean,
): ObservedRouteCapabilities =
    ObservedRouteCapabilities(
        internet = ObservedBoolean.of(hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)),
        validated = ObservedBoolean.of(hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)),
        vpn = ObservedBoolean.of(!hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)),
        metered = ObservedBoolean.of(!hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)),
        restricted = ObservedBoolean.of(
            !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED),
        ),
        suspended = if (sdkInt >= SUSPENDED_API_FLOOR) {
            ObservedBoolean.of(!hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED))
        } else {
            ObservedBoolean.UNKNOWN
        },
    )

internal const val SUSPENDED_API_FLOOR = 28

private fun NetworkCapabilities.toObserved(): ObservedRouteCapabilities =
    mapRouteCapabilities(Build.VERSION.SDK_INT, ::hasCapability)

/** Process-local, bounded Network -> opaque ref map. Never persisted. */
private class PlatformRouteRefs {
    private val lock = Any()
    private var lastOrdinal = 0L
    private val refs = object : LinkedHashMap<Network, PlatformRouteRef>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Network, PlatformRouteRef>,
        ): Boolean = size > MAX_TRACKED_NETWORKS
    }

    fun refFor(network: Network): PlatformRouteRef = synchronized(lock) {
        refs.getOrPut(network) {
            lastOrdinal += 1
            PlatformRouteRef.ofOrdinal(lastOrdinal)
        }
    }

    private companion object {
        const val MAX_TRACKED_NETWORKS = 32
    }
}

/**
 * API 24+. Callbacks only convert the delivered objects into signals: no
 * synchronous ConnectivityManager lookup happens inside a callback.
 */
@TargetApi(Build.VERSION_CODES.N)
private class DefaultNetworkCallbackPlatform(
    private val connectivity: ConnectivityManager,
    private val refs: PlatformRouteRefs,
) : RouteSignalPlatform {
    @Volatile
    private var sink: RouteSignalSink? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            sink?.offer(RouteSignal.Available(refs.refFor(network)))
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            sink?.offer(
                RouteSignal.CapabilitiesChanged(
                    refs.refFor(network),
                    networkCapabilities.toObserved(),
                ),
            )
        }

        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: LinkProperties,
        ) {
            sink?.offer(RouteSignal.LinkPropertiesChanged(refs.refFor(network)))
        }

        // Delivered by the platform from API 29 only; below it blocked stays UNKNOWN.
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            sink?.offer(RouteSignal.BlockedStatusChanged(refs.refFor(network), blocked))
        }

        override fun onLost(network: Network) {
            sink?.offer(RouteSignal.Lost(refs.refFor(network)))
        }
    }

    override fun register(sink: RouteSignalSink) {
        this.sink = sink
        try {
            connectivity.registerDefaultNetworkCallback(callback)
        } catch (error: Throwable) {
            this.sink = null
            throw error
        }
    }

    /**
     * Resolves INITIALIZING when no default network exists at registration.
     * Only `activeNetwork` is read; capabilities come from callbacks.
     */
    override fun bootstrap(sink: RouteSignalSink) {
        val active = connectivity.activeNetwork
        sink.offer(RouteSignal.BootstrapSnapshot(active?.let(refs::refFor)))
    }

    override fun unregister() {
        sink = null
        connectivity.unregisterNetworkCallback(callback)
    }
}

/**
 * API 23 only. The receiver takes no decision; it triggers a snapshot:
 *
 * ```text
 * A = activeNetwork; null -> UNAVAILABLE
 * caps = getNetworkCapabilities(A)
 * B = activeNetwork; A != B -> discard, one fresh snapshot, else await broadcast
 * ```
 */
private class LegacyConnectivityActionPlatform(
    private val context: Context,
    private val connectivity: ConnectivityManager,
    private val refs: PlatformRouteRefs,
) : RouteSignalPlatform {
    @Volatile
    private var sink: RouteSignalSink? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            sink?.let(::snapshot)
        }
    }

    // CONNECTIVITY_ACTION is used only on API 23, where receiver export flags
    // do not exist.
    @Suppress("DEPRECATION")
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun register(sink: RouteSignalSink) {
        this.sink = sink
        try {
            context.registerReceiver(
                receiver,
                IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION),
            )
        } catch (error: Throwable) {
            this.sink = null
            throw error
        }
    }

    override fun bootstrap(sink: RouteSignalSink) {
        snapshot(sink)
    }

    override fun unregister() {
        sink = null
        context.unregisterReceiver(receiver)
    }

    private fun snapshot(sink: RouteSignalSink) {
        repeat(MAX_SNAPSHOT_READS) {
            val first = connectivity.activeNetwork
            if (first == null) {
                // Null may also mean "default network blocked for this app".
                sink.offer(RouteSignal.LegacySnapshot(routeRef = null, capabilities = null))
                return
            }
            val capabilities = connectivity.getNetworkCapabilities(first)
            if (first == connectivity.activeNetwork) {
                sink.offer(
                    RouteSignal.LegacySnapshot(
                        routeRef = refs.refFor(first),
                        capabilities = capabilities?.toObserved(),
                    ),
                )
                return
            }
        }
        // Raced with a route change twice: trust nothing; the next
        // CONNECTIVITY_ACTION broadcast triggers another snapshot.
    }

    private companion object {
        const val MAX_SNAPSHOT_READS = 2
    }
}
