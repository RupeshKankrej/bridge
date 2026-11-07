package dev.bridge.android

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import bridge.files.FileTransfer
import bridge.files.LocalSendPeer
import bridge.port.InputPort
import bridge.port.MediaPort
import bridge.port.OutgoingFile
import bridge.protocol.Platform
import bridge.session.Bridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns the single core [Bridge] and [FileTransfer]; [BridgeService] keeps the process alive, activities observe them. */
class BridgeApplication : Application() {
    /** AirDrop (spec §5.10) needs Wi-Fi Aware; some firmware turns it off (the Pad 3's does). */
    val airDropSupported by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // Without Wi-Fi Aware the AirDrop tile would do nothing, so it isn't offered in the tile picker at all.
        // Checked every launch: an OS update can add the feature.
        val state = if (airDropSupported) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        packageManager.setComponentEnabledSetting(ComponentName(this, AirDropTile::class.java), state, PackageManager.DONT_KILL_APP)
    }

    val clipboard by lazy { AndroidClipboard(this) }
    val notifications by lazy { AndroidNotifications(this) }
    val features by lazy { Features(this) }
    private val keyStore by lazy { AndroidKeyStorePort(this) }
    private val deviceName get() = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL

    val bridge: Bridge by lazy {
        Bridge(
            name = deviceName,
            platform = Platform.ANDROID,
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "0",
            keyStore = keyStore,
            transport = OkHttpTransport(keyStore),
            discovery = NsdDiscovery(this),
            clipboard = clipboard,
            notifications = notifications,
            // The tablet sends input; it never injects any.
            input = object : InputPort {},
            // The tablet shows the Mac's media; it has no player of its own to report.
            media = object : MediaPort {},
            share = AndroidShare(this, features.share.on),
        )
    }

    /** Created after [bridge], which creates the identity on first launch. */
    val files: FileTransfer by lazy {
        bridge
        val streams = AndroidStreams(keyStore) { _filesProblem.value = it }
        FileTransfer(deviceName, Platform.ANDROID, Build.MODEL, keyStore, streams, AndroidMulticast(this), MediaStoreFileStore(contentResolver) { features.receiveFolder.value })
    }

    private val _filesProblem = MutableStateFlow<String?>(null)

    /** e.g. LocalSend on this tablet holds port 53317. */
    val filesProblem: StateFlow<String?> = _filesProblem.asStateFlow()

    private val _pendingSend = MutableStateFlow<List<OutgoingFile>>(emptyList())

    /** Files picked or shared, waiting for the user to choose a device. */
    val pendingSend: StateFlow<List<OutgoingFile>> = _pendingSend.asStateFlow()

    fun isPaired(peer: LocalSendPeer) = keyStore.trustedPeers().any { it.fingerprint == peer.fingerprint }

    /** Sends straight to the paired device if exactly one is nearby; otherwise asks (same rule as the Mac). */
    fun send(outgoing: List<OutgoingFile>) {
        if (outgoing.isEmpty()) return
        val paired = files.state.value.nearby.filter(::isPaired)
        if (paired.size == 1) files.send(paired[0], outgoing) else _pendingSend.value = outgoing
    }

    fun sendPending(peer: LocalSendPeer) {
        files.send(peer, _pendingSend.value)
        _pendingSend.value = emptyList()
    }

    fun cancelPending() {
        _pendingSend.value = emptyList()
    }

    /** Send screen: add to the selection without sending; the user then picks a device. */
    fun select(more: List<OutgoingFile>) {
        _pendingSend.value += more
    }

    fun unselect(file: OutgoingFile) {
        _pendingSend.value -= file
    }
}
