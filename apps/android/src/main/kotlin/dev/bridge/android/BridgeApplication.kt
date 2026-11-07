package dev.bridge.android

import android.app.Application
import android.os.Build
import android.provider.Settings
import bridge.files.FileTransfer
import bridge.files.LocalSendPeer
import bridge.port.OutgoingFile
import bridge.protocol.Platform
import bridge.session.Bridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns the single core [Bridge] and [FileTransfer]; [BridgeService] keeps the process alive, activities observe them. */
class BridgeApplication : Application() {
    val clipboard by lazy { AndroidClipboard(this) }
    val notifications by lazy { AndroidNotifications(this) }
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
        )
    }

    /** Created after [bridge], which creates the identity on first launch. */
    val files: FileTransfer by lazy {
        bridge
        val streams = AndroidStreams(keyStore) { _filesProblem.value = it }
        FileTransfer(deviceName, Platform.ANDROID, Build.MODEL, keyStore, streams, AndroidMulticast(this), MediaStoreFileStore(contentResolver))
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
}
