package dev.bridge.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import bridge.port.DiscoveredPeer
import bridge.port.DiscoveryPort
import java.net.Inet4Address

/**
 * `_bridge._tcp` over NsdManager: advertises this tablet and resolves the Macs it sees, so core can follow
 * a paired Mac to a new address (spec §5.1). NSD callbacks arrive on binder threads; all state is touched on main.
 */
class NsdDiscovery(context: Context) : DiscoveryPort {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    override fun advertise(port: Int, txt: Map<String, String>) {
        // shortcut: advertises CONTROL_PORT although the tablet doesn't listen yet (the Mac only uses the TXT record); listen when the Mac can scan the tablet's QR.
        val info = NsdServiceInfo().apply {
            serviceName = txt["name"]
            serviceType = TYPE
            this.port = port
            txt.forEach { (key, value) -> setAttribute(key, value) }
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "advertising as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "advertising failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {}

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        })
    }

    override fun browse(onPeers: (List<DiscoveredPeer>) -> Unit) {
        val peers = mutableMapOf<String, DiscoveredPeer>() // by service name
        // Before Android 14 NsdManager resolves one service at a time (FAILURE_ALREADY_ACTIVE otherwise).
        val toResolve = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun next() {
            if (resolving) return
            val info = toResolve.removeFirstOrNull() ?: return
            resolving = true
            @Suppress("DEPRECATION") // registerServiceInfoCallback is 34+; this works on every version we support.
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        toPeer(info)?.let { peers[info.serviceName] = it }
                        onPeers(peers.values.toList())
                        next()
                    }
                }

                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        Log.d(TAG, "resolve failed: $errorCode")
                        resolving = false
                        next()
                    }
                }
            })
        }

        nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    // Found again after a network switch: resolve again, the address may have changed.
                    toResolve.removeAll { it.serviceName == info.serviceName }
                    toResolve.addLast(info)
                    next()
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                main.post { if (peers.remove(info.serviceName) != null) onPeers(peers.values.toList()) }
            }

            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "browse failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        })
    }

    private fun toPeer(info: NsdServiceInfo): DiscoveredPeer? {
        val txt = info.attributes.mapValues { it.value?.decodeToString() }
        val id = txt["id"] ?: return null
        @Suppress("DEPRECATION") // NsdServiceInfo.host is deprecated in 34 for hostAddresses.
        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        // IPv4 only, like the QR code: a link-local IPv6 address needs a scope ID that URLs can't carry.
        val host = addresses.firstOrNull { it is Inet4Address }?.hostAddress
        return DiscoveredPeer(id, txt["name"] ?: info.serviceName, txt["platform"].orEmpty(), host, info.port)
    }

    private companion object {
        const val TAG = "BridgeNsd"
        const val TYPE = "_bridge._tcp"
    }
}
