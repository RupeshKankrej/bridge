package dev.bridge.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import bridge.port.DiscoveryPort

/** Advertises `_bridge._tcp` with NsdManager. Browsing isn't needed yet: the tablet dials the Mac from the QR. */
class NsdDiscovery(context: Context) : DiscoveryPort {
    private val nsd = context.getSystemService(NsdManager::class.java)

    override fun advertise(port: Int, txt: Map<String, String>) {
        // shortcut: advertises CONTROL_PORT although the tablet doesn't listen yet (the Mac only uses the TXT record); listen when the Mac can scan the tablet's QR.
        val info = NsdServiceInfo().apply {
            serviceName = txt["name"]
            serviceType = "_bridge._tcp"
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

    private companion object {
        const val TAG = "BridgeNsd"
    }
}
