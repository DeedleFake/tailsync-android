package dev.deedles.tailsync

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.util.Log
import mobile.Mobile
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface as JavaNetworkInterface

/**
 * Supplies host network interfaces to the Go mobile package so tsnet/netmon
 * does not call Go's [java.net]-incompatible netlink path on Android API 30+.
 *
 * Call [publish] before [mobile.Node.start] and again from connectivity
 * callbacks with [notify] true while a node is running.
 */
object AndroidNetworkBridge {

    private const val TAG = "AndroidNetworkBridge"

    // Go net.Flags bits (see mobile.SetNetworkInterfacesJSON docs).
    const val FLAG_UP: Int = 1
    const val FLAG_BROADCAST: Int = 2
    const val FLAG_LOOPBACK: Int = 4
    const val FLAG_POINT_TO_POINT: Int = 8
    const val FLAG_MULTICAST: Int = 16
    const val FLAG_RUNNING: Int = 32

    @Volatile
    private var callback: ConnectivityManager.NetworkCallback? = null

    data class Snapshot(
        val interfacesJson: String,
        val defaultInterface: String,
        val defaultGateway: String,
        val interfaceCount: Int,
    )

    /**
     * Enumerate interfaces via [JavaNetworkInterface] and default route via
     * [ConnectivityManager] / [LinkProperties]. Pure Android APIs — no netlink.
     */
    fun collect(context: Context): Snapshot {
        val ifaces = collectInterfacesJson()
        val (routeIf, gateway) = collectDefaultRoute(context)
        val count = try {
            JSONArray(ifaces).length()
        } catch (_: Exception) {
            0
        }
        return Snapshot(
            interfacesJson = ifaces,
            defaultInterface = routeIf,
            defaultGateway = gateway,
            interfaceCount = count,
        )
    }

    /**
     * Push [snapshot] into Go. When [notify] is true, also [Mobile.notifyNetworkChange]
     * so a running tsnet netmon re-evaluates (no-op if not yet running).
     */
    fun publish(snapshot: Snapshot, notify: Boolean = false) {
        try {
            Mobile.setNetworkInterfacesJSON(snapshot.interfacesJson)
            Mobile.setDefaultRouteInterface(snapshot.defaultInterface)
            Mobile.setDefaultGateway(snapshot.defaultGateway)
            if (notify) {
                Mobile.notifyNetworkChange()
            }
            Log.i(
                TAG,
                "Published ${snapshot.interfaceCount} interface(s) " +
                    "defaultIf=${snapshot.defaultInterface.ifBlank { "(none)" }} " +
                    "gw=${snapshot.defaultGateway.ifBlank { "(none)" }} notify=$notify",
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish network snapshot to Go", e)
            throw e
        }
    }

    /** Collect and publish. Returns the snapshot. */
    fun collectAndPublish(context: Context, notify: Boolean = false): Snapshot {
        val snap = collect(context.applicationContext)
        publish(snap, notify = notify)
        return snap
    }

    /**
     * Register a default-network callback that re-publishes interfaces and
     * notifies Go. Safe to call multiple times (replaces prior callback).
     */
    fun startMonitoring(context: Context) {
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        stopMonitoring(app)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = republish(app)
            override fun onLost(network: Network) = republish(app)
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                republish(app)

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) = republish(app)

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) =
                republish(app)
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            callback = cb
            // Initial push with notify so a node already mid-Up can catch up later.
            republish(app)
            Log.i(TAG, "Network monitoring registered")
        } catch (e: Exception) {
            Log.e(TAG, "registerDefaultNetworkCallback failed", e)
            callback = null
        }
    }

    fun stopMonitoring(context: Context) {
        val cb = callback ?: return
        callback = null
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        try {
            cm?.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterNetworkCallback: ${e.message}")
        }
    }

    private fun republish(context: Context) {
        try {
            collectAndPublish(context, notify = true)
        } catch (e: Exception) {
            Log.e(TAG, "republish failed", e)
        }
    }

    internal fun goFlags(
        isUp: Boolean,
        isLoopback: Boolean,
        isPointToPoint: Boolean,
        supportsMulticast: Boolean,
        hasBroadcast: Boolean,
    ): Int {
        var flags = 0
        if (isUp) {
            flags = flags or FLAG_UP or FLAG_RUNNING
        }
        if (isLoopback) flags = flags or FLAG_LOOPBACK
        if (isPointToPoint) flags = flags or FLAG_POINT_TO_POINT
        if (supportsMulticast) flags = flags or FLAG_MULTICAST
        if (hasBroadcast && !isLoopback) flags = flags or FLAG_BROADCAST
        return flags
    }

    internal fun formatCidr(address: java.net.InetAddress, prefixLength: Int): String? {
        val host = address.hostAddress ?: return null
        // Skip IPv6 scoped forms with %iface if hostAddress includes them — still valid for netip often.
        val cleaned = host.substringBefore('%')
        if (prefixLength < 0) return null
        val max = when (address) {
            is Inet4Address -> 32
            is Inet6Address -> 128
            else -> return null
        }
        val plen = prefixLength.coerceIn(0, max)
        return "$cleaned/$plen"
    }

    private fun collectInterfacesJson(): String {
        val arr = JSONArray()
        val enums = try {
            JavaNetworkInterface.getNetworkInterfaces()
        } catch (e: Exception) {
            Log.e(TAG, "getNetworkInterfaces failed", e)
            return "[]"
        } ?: return "[]"

        while (enums.hasMoreElements()) {
            val ni = enums.nextElement() ?: continue
            val name = ni.name ?: continue
            val addrs = JSONArray()
            var hasBroadcast = false
            try {
                for (ia in ni.interfaceAddresses) {
                    val addr = ia.address ?: continue
                    if (addr.isLinkLocalAddress && addr is Inet4Address) {
                        // Keep IPv4 link-local if any; usually none.
                    }
                    val cidr = formatCidr(addr, ia.networkPrefixLength.toInt()) ?: continue
                    addrs.put(cidr)
                    if (ia.broadcast != null) hasBroadcast = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "interfaceAddresses($name): ${e.message}")
            }
            // Loopback always has an implied "local" scope; treat as non-broadcast.
            if (ni.isLoopback) hasBroadcast = false

            val flags = goFlags(
                isUp = runCatching { ni.isUp }.getOrDefault(false),
                isLoopback = runCatching { ni.isLoopback }.getOrDefault(false),
                isPointToPoint = runCatching { ni.isPointToPoint }.getOrDefault(false),
                supportsMulticast = runCatching { ni.supportsMulticast() }.getOrDefault(false),
                hasBroadcast = hasBroadcast,
            )
            val mtu = runCatching { ni.mtu }.getOrDefault(0).coerceAtLeast(0)
            val index = runCatching { ni.index }.getOrDefault(0).coerceAtLeast(0)

            arr.put(
                JSONObject().apply {
                    put("name", name)
                    put("index", index)
                    put("flags", flags)
                    put("mtu", mtu)
                    put("addrs", addrs)
                },
            )
        }
        return arr.toString()
    }

    private fun collectDefaultRoute(context: Context): Pair<String, String> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return "" to ""
        val network = cm.activeNetwork ?: return "" to ""
        val lp = cm.getLinkProperties(network) ?: return "" to ""
        val ifName = lp.interfaceName?.trim().orEmpty()
        val gateway = defaultGatewayFrom(lp)
        return ifName to gateway
    }

    internal fun defaultGatewayFrom(lp: LinkProperties): String {
        for (route in lp.routes) {
            if (!isDefaultRoute(route)) continue
            val gw = route.gateway ?: continue
            val host = gw.hostAddress?.substringBefore('%')?.trim().orEmpty()
            if (host.isNotEmpty()) return host
        }
        return ""
    }

    private fun isDefaultRoute(route: RouteInfo): Boolean = route.isDefaultRoute
}
