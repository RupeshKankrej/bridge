package dev.bridge.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Mirroring on/off, the notification-access prompt, and the per-app allow/block list (spec §5.4, §7). */
@Composable
fun NotificationsPanel(port: AndroidNotifications, hasAccess: Boolean) {
    val context = LocalContext.current
    val enabled by port.enabled.collectAsState()
    val blocked by port.blocked.collectAsState()
    var showApps by remember { mutableStateOf(false) }

    Text("Notifications", style = MaterialTheme.typography.titleLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Show this tablet's notifications on the Mac")
        Switch(checked = enabled, onCheckedChange = port::setEnabled)
    }
    if (enabled && !hasAccess) {
        Text("Bridge needs notification access to read them; they go only to your paired Mac, encrypted, over your Wi-Fi.")
        Button(onClick = { openAccessSettings(context) }) { Text("Allow notification access") }
    }
    if (enabled) {
        TextButton(onClick = { showApps = !showApps }) { Text(if (showApps) "Hide apps" else "Choose apps…") }
        if (showApps) AppList(blocked, port::setBlocked)
    }
}

private class App(val pkg: String, val label: String)

@Composable
private fun AppList(blocked: Set<String>, setBlocked: (String, Boolean) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<App>?>(null) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { launchableApps(context) } }
    val list = apps ?: return Text("Loading apps…")
    Column {
        list.forEach { app ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(app.label)
                Switch(checked = app.pkg !in blocked, onCheckedChange = { allow -> setBlocked(app.pkg, !allow) })
            }
        }
    }
}

private fun launchableApps(context: Context): List<App> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .map { it.activityInfo.packageName }
        .distinct()
        .filter { it != context.packageName }
        .map { App(it, pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString()) }
        .sortedBy { it.label.lowercase() }
}

/** Android 11+ opens Bridge's own switch; Android 10 only has the list of all listeners. */
private fun openAccessSettings(context: Context) {
    val intent = if (android.os.Build.VERSION.SDK_INT >= 30) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, MirrorService::class.java).flattenToString())
    } else {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
    context.startActivity(intent)
}
