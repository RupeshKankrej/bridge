package dev.bridge.android

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import bridge.protocol.PROTOCOL_VERSION

/** Spec §7 settings: every per-feature switch lives here, not on the home screen (ADR 0012). */
@Composable
fun SettingsScreen(app: BridgeApplication, system: SystemState, onAllowBackground: () -> Unit, onOpen: (SubScreen) -> Unit) {
    val context = LocalContext.current
    val features = app.features
    val state by app.bridge.state.collectAsState()

    ScreenTitle("Settings")

    Section("Paired Mac") {
        if (state.paired.isEmpty()) SettingRow("Not paired", "Pair from the Home tab", R.drawable.ic_laptop)
        state.paired.forEach { peer ->
            SettingRow(
                peer.name,
                if (peer.online) "Connected" else "Offline",
                R.drawable.ic_laptop,
                trailing = { OutlinedButton(onClick = { app.bridge.unpair(peer.fingerprint) }) { Text("Forget") } },
            )
        }
        SettingRow(
            "Background use",
            if (system.batteryExempt) "Allowed: stays connected with the screen off" else "Not allowed: Bridge may disconnect with the screen off",
            R.drawable.ic_battery,
            onClick = if (system.batteryExempt) null else onAllowBackground,
        )
    }

    val notifyOn by app.notifications.enabled.collectAsState()
    Section("Notifications") {
        SettingRow(
            "Show this tablet's notifications on the Mac",
            "Encrypted, only to your paired Mac",
            R.drawable.ic_notifications,
            checked = notifyOn,
            onCheckedChange = app.notifications::setEnabled,
        )
        if (notifyOn && !system.notificationAccess) {
            SettingRow("Allow notification access", "Needed to read notifications", R.drawable.ic_error, onClick = { openAccessSettings(context) })
        }
        if (notifyOn) SettingRow("Choose apps", null, R.drawable.ic_settings, onClick = { onOpen(SubScreen.NOTIFICATION_APPS) })
    }

    Section("Media, battery and links") {
        FeatureRow("Control the Mac's media", "Play, pause, skip and volume from the Home tab", R.drawable.ic_music, features.media)
        FeatureRow("Show this tablet's battery on the Mac", "In the Mac's menu bar", R.drawable.ic_battery, features.battery)
        FeatureRow("Links and text", "Share → Bridge sends to the Mac; the Mac's Send with Bridge arrives here", R.drawable.ic_link, features.share)
    }

    val folder by features.receiveFolder.collectAsState()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree ?: return@rememberLauncherForActivityResult
        // Write access that survives restarts; without it the next launch can't save there.
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        features.setReceiveFolder(tree)
    }
    Section("Files") {
        SettingRow("Receive folder", folder?.let(::folderName) ?: "Download/Bridge", R.drawable.ic_folder, onClick = { pickFolder.launch(null) })
        if (folder != null) {
            SettingRow("Use Download/Bridge again", null, R.drawable.ic_download, onClick = {
                folder?.let { runCatching { context.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
                features.setReceiveFolder(null)
            })
        }
        SettingRow("Paired devices send without asking", "Others (LocalSend) get an Accept / Decline prompt", R.drawable.ic_check_circle)
    }

    AirDropSection(features)

    Section("About") {
        SettingRow("Diagnostics", "Connection, link quality, last error, crash log", R.drawable.ic_info, onClick = { onOpen(SubScreen.DIAGNOSTICS) })
        SettingRow("Bridge ${appVersion(context)}", "Protocol v$PROTOCOL_VERSION · LocalSend v2.1 compatible · no cloud, no telemetry")
    }
}

@Composable
private fun FeatureRow(title: String, summary: String, icon: Int, toggle: Features.Toggle) {
    val on by toggle.on.collectAsState()
    SettingRow(title, summary, icon, checked = on, onCheckedChange = toggle::set)
}

/** Spec §5.10 visibility, plumbing only: nothing advertises to Apple devices until Tier A or B ships (ADR 0012). */
@Composable
private fun AirDropSection(features: Features) {
    val visibility by features.airDrop.collectAsState()
    Section("AirDrop") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Features.AirDrop.entries.forEachIndexed { i, option ->
                    SegmentedButton(
                        selected = visibility == option,
                        onClick = { features.setAirDrop(option) },
                        shape = SegmentedButtonDefaults.itemShape(i, Features.AirDrop.entries.size),
                    ) { Text(airDropLabel(option)) }
                }
            }
            val until by features.everyoneUntil.collectAsState()
            Text(
                when (visibility) {
                    Features.AirDrop.OFF -> "Apple devices can't see this tablet. Sending still works."
                    Features.AirDrop.BRIDGE -> "Apple devices can't see you in this mode; paired Bridge and LocalSend devices can still send."
                    Features.AirDrop.EVERYONE -> if (until == 0L) "Everyone, always (until you change it)." else "Back to Bridge devices only at ${timeOf(until)}."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (visibility == Features.AirDrop.EVERYONE && until != 0L) {
                Button(onClick = { features.setAirDrop(Features.AirDrop.EVERYONE, always = true) }) { Text("Keep Everyone on") }
            }
            Row { Text("Not available on this tablet yet: Apple devices won't see it in any mode (spec §5.10).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun folderName(tree: Uri) = DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifEmpty { "Internal storage" }

private fun timeOf(epochMs: Long) = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(epochMs))

fun appVersion(context: android.content.Context) = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
