package dev.bridge.android

import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import bridge.session.LinkQuality
import bridge.session.PeerStatus
import bridge.session.linkQuality

/** Spec §7 tablet home: connection card, big buttons, media, recent transfers. Feature switches live in Settings. */
@Composable
fun HomeScreen(
    app: BridgeApplication,
    system: SystemState,
    onScan: (host: String) -> Unit,
    onAllowBackground: () -> Unit,
    onTrackpad: () -> Unit,
    onSend: () -> Unit,
) {
    val bridge = app.bridge
    val state by bridge.state.collectAsState()
    val mediaOn by app.features.media.on.collectAsState()
    // The switch flipped while on screen; onResume/onPause cover the rest.
    LaunchedEffect(mediaOn) { bridge.watchMedia(mediaOn) }
    val context = LocalContext.current

    ScreenTitle("Bridge")

    if (!system.batteryExempt) {
        Notice("Bridge needs to run in the background to stay connected with the screen off.", "Allow background use", onAllowBackground)
    }

    state.paired.forEach { peer -> ConnectionCard(app, peer) }

    val pairing = state.pairing
    when {
        pairing != null -> Section("Pairing with ${pairing.peerName}") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Check that ${pairing.peerName} shows the same code")
                Text(pairing.code, style = MaterialTheme.typography.displayLarge, fontFamily = FontFamily.Monospace)
                if (pairing.awaitingLocalConfirm) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = bridge::confirmPairing) { Text("Confirm") }
                        OutlinedButton(onClick = bridge::rejectPairing) { Text("Cancel") }
                    }
                } else {
                    Text("Waiting for ${pairing.peerName}…")
                }
            }
        }

        state.paired.isEmpty() -> Section("Pair with your Mac") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("On the Mac: menu-bar icon → Pair a device…, then scan its QR code.")
                var host by remember { mutableStateOf("") }
                Button(onClick = { onScan(host) }) {
                    Icon(painterResource(R.drawable.ic_qr), null, Modifier.size(18.dp))
                    Text("  Scan QR code")
                }
                OutlinedTextField(
                    host,
                    { host = it },
                    singleLine = true,
                    label = { Text("Mac address (optional)") },
                    supportingText = { Text("Only if the QR's address doesn't work here, e.g. Wi-Fi that isolates devices") },
                )
            }
        }
    }

    state.message?.let { Notice(it) }

    val online = state.paired.firstOrNull { it.online }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionTile("Send clipboard", R.drawable.ic_paste, Modifier.weight(1f), enabled = online != null) {
            // ClipboardActivity reads the clipboard and sends it, as the tile and notification do.
            context.startActivity(Intent(context, ClipboardActivity::class.java))
        }
        ActionTile("Send files", R.drawable.ic_send, Modifier.weight(1f), onClick = onSend)
        ActionTile("Trackpad", R.drawable.ic_mouse, Modifier.weight(1f), enabled = online != null, onClick = onTrackpad)
    }

    if (mediaOn && online != null) MediaCard(bridge, online)

    val transfers by app.files.state.collectAsState()
    if (transfers.transfers.isNotEmpty()) {
        Text("Recent transfers", style = MaterialTheme.typography.titleMedium)
        TransferList(app, limit = 5)
    }
}

@Composable
private fun ConnectionCard(app: BridgeApplication, peer: PeerStatus) {
    val bridge = app.bridge
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(painterResource(R.drawable.ic_laptop), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(peer.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (peer.online) "Connected" else "Offline, reconnecting…",
                        color = if (peer.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                peer.rttMs?.takeIf { peer.online }?.let { AssistChip(onClick = {}, label = { Text(qualityLabel(it)) }) }
            }
            val host = peer.host
            // For networks that block mDNS: the Mac got a new address and can't announce it.
            if (!peer.online && host != null) {
                var typed by remember(host) { mutableStateOf(host) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(typed, { typed = it }, label = { Text("Mac address") }, singleLine = true)
                    OutlinedButton(onClick = { bridge.setAddress(peer.fingerprint, typed) }, enabled = typed.isNotBlank()) { Text("Connect") }
                }
            }
        }
    }
}

/** e.g. "Excellent · 12 ms". */
fun qualityLabel(rttMs: Long) = when (linkQuality(rttMs)) {
    LinkQuality.EXCELLENT -> "Excellent"
    LinkQuality.GOOD -> "Good"
    LinkQuality.FAIR -> "Fair"
    LinkQuality.POOR -> "Poor"
} + " · $rttMs ms"

@Composable
private fun ActionTile(label: String, @DrawableRes icon: Int, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(120.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(painterResource(icon), null, Modifier.size(32.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** A problem worth acting on, with an optional fix button. */
@Composable
fun Notice(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_error), null)
            Text(text, Modifier.weight(1f))
            action?.let { Button(onClick = onAction) { Text(it) } }
        }
    }
}
