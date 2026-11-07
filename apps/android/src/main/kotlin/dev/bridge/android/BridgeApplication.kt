package dev.bridge.android

import android.app.Application
import android.os.Build
import android.provider.Settings
import bridge.protocol.Platform
import bridge.session.Bridge

/** Owns the single core [Bridge]; [BridgeService] keeps the process alive, [MainActivity] observes it. */
class BridgeApplication : Application() {
    val bridge: Bridge by lazy {
        val keyStore = AndroidKeyStorePort(this)
        Bridge(
            name = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL,
            platform = Platform.ANDROID,
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "0",
            keyStore = keyStore,
            transport = OkHttpTransport(keyStore),
            discovery = NsdDiscovery(this),
        )
    }
}
