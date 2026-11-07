package dev.bridge.android

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** M2.5: connection state, peer address, link quality, last error and the local crash log. Nothing leaves the tablet. */
@Composable
fun DiagnosticsScreen(app: BridgeApplication, system: SystemState, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by app.bridge.state.collectAsState()
    val files by app.files.state.collectAsState()
    val filesProblem by app.filesProblem.collectAsState()
    // Ticks the relative times ("2 min ago").
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            now = System.currentTimeMillis()
        }
    }
    fun ago(t: Long) = DateUtils.getRelativeTimeSpanString(t, now, DateUtils.SECOND_IN_MILLIS).toString()

    ScreenTitle("Diagnostics", onBack, action = { Button(onClick = app.bridge::reconnect) { Text("Reconnect now") } })

    state.paired.forEach { peer ->
        Section(peer.name) {
            SettingRow("Connection", if (peer.online) "Connected ${ago(peer.connectedAt)}" else "Offline, reconnecting", R.drawable.ic_laptop)
            SettingRow("Peer address", peer.address ?: peer.host?.let { "$it (last known)" } ?: "unknown", R.drawable.ic_link)
            SettingRow("Link quality", peer.rttMs?.takeIf { peer.online }?.let { "${qualityLabel(it)} round trip (heartbeat every 15 s)" } ?: "—", R.drawable.ic_airdrop)
            peer.battery?.let { SettingRow("Battery sent", "${it.level}%${if (it.charging) ", charging" else ""}", R.drawable.ic_battery) }
        }
    }
    if (state.paired.isEmpty()) Section { SettingRow("Connection", if (state.pairing != null) "Pairing" else "Not paired", R.drawable.ic_laptop) }

    Section("Last error") {
        SettingRow(state.lastError ?: "None since Bridge started", state.lastError?.let { ago(state.lastErrorAt) }, R.drawable.ic_error)
        filesProblem?.let { SettingRow(it, "File server", R.drawable.ic_error) }
    }

    Section("This tablet") {
        SettingRow("Device ID", state.deviceId.chunked(4).take(8).joinToString(" ") + " …", R.drawable.ic_tablet)
        SettingRow("Nearby (mDNS)", state.discovered.joinToString { it.name }.ifEmpty { "none" }, R.drawable.ic_refresh)
        SettingRow("Nearby (LocalSend)", files.nearby.joinToString { "${it.alias} ${it.host}" }.ifEmpty { "none" }, R.drawable.ic_send)
        SettingRow("Background use", if (system.batteryExempt) "Allowed" else "Not allowed", R.drawable.ic_battery)
        SettingRow("Notification access", if (system.notificationAccess) "Granted" else "Not granted", R.drawable.ic_notifications)
        SettingRow("Version", "Bridge ${appVersion(context)}", R.drawable.ic_info)
    }

    var log by remember { mutableStateOf(CrashLog.read()) }
    Section("Crash log") {
        if (log.isEmpty()) {
            SettingRow("No crashes recorded", "Stored on this tablet only", R.drawable.ic_check_circle)
        } else {
            SelectionContainer {
                Text(log.takeLast(4000), Modifier.padding(16.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.padding(16.dp, 0.dp, 16.dp, 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Bridge crash log", log))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
                OutlinedButton(onClick = { CrashLog.clear(); log = "" }) { Text("Clear") }
            }
        }
    }
}
