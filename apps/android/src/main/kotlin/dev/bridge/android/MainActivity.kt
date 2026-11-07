package dev.bridge.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** Tabs on the rail, like LocalSend's Receive / Send / Settings; sub-screens open over them. */
enum class Tab(val label: String, @DrawableRes val icon: Int) {
    HOME("Home", R.drawable.ic_home),
    SEND("Send", R.drawable.ic_send),
    SETTINGS("Settings", R.drawable.ic_settings),
}

enum class SubScreen { TRACKPAD, DIAGNOSTICS, NOTIFICATION_APPS }

class MainActivity : ComponentActivity() {
    private val app get() = application as BridgeApplication
    private val bridge get() = app.bridge
    private val system = mutableStateOf(SystemState())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Without it the foreground-service notification is hidden; the service still runs.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        startForegroundService(Intent(this, BridgeService::class.java))
        setContent {
            BridgeTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
                    var sub by rememberSaveable { mutableStateOf<SubScreen?>(null) }
                    BackHandler(enabled = sub != null) { sub = null }
                    OfferPrompt(app)
                    when (sub) {
                        SubScreen.TRACKPAD -> TrackpadScreen(bridge) { sub = null }
                        SubScreen.DIAGNOSTICS -> Page { DiagnosticsScreen(app, system.value) { sub = null } }
                        SubScreen.NOTIFICATION_APPS -> Page { NotificationAppsScreen(app.notifications) { sub = null } }
                        null -> Row(Modifier.fillMaxSize().safeDrawingPadding()) {
                            NavigationRail(Modifier.padding(top = 16.dp)) {
                                Spacer(Modifier.size(8.dp))
                                Tab.entries.forEach {
                                    NavigationRailItem(
                                        selected = tab == it,
                                        onClick = { tab = it },
                                        icon = { Icon(painterResource(it.icon), null) },
                                        label = { Text(it.label) },
                                    )
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                when (tab) {
                                    Tab.HOME -> Page {
                                        HomeScreen(
                                            app, system.value,
                                            onScan = ::scan,
                                            onAllowBackground = ::requestBatteryExemption,
                                            onTrackpad = { sub = SubScreen.TRACKPAD },
                                            onSend = { tab = Tab.SEND },
                                        )
                                    }
                                    Tab.SEND -> Page { SendScreen(app) }
                                    Tab.SETTINGS -> Page {
                                        SettingsScreen(
                                            app, system.value,
                                            onAllowBackground = ::requestBatteryExemption,
                                            onOpen = { sub = it },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        app.clipboard.setForeground(true)
        // Re-read on every resume: the user answers the system dialog or changes it in Settings.
        system.value = SystemState(
            batteryExempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
            notificationAccess = app.notifications.hasAccess,
        )
        // Now-playing only while Bridge is on screen: the Mac polls its players only while watched (ADR 0011).
        bridge.watchMedia(app.features.media.on.value)
    }

    override fun onPause() {
        bridge.watchMedia(false)
        app.clipboard.setForeground(false)
        super.onPause()
    }

    /** Without the exemption Doze cuts the network and OxygenOS may kill the service with the screen off. */
    private fun requestBatteryExemption() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    /** Play services draws the camera UI, so Bridge needs no CAMERA permission. [host] overrides the QR's address if not blank. */
    private fun scan(host: String) {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let { bridge.pair(it, host) } }
            .addOnFailureListener { Toast.makeText(this, "Scanner unavailable: ${it.message}", Toast.LENGTH_LONG).show() }
    }
}

/** What the system says about Bridge, read on every resume. */
data class SystemState(val batteryExempt: Boolean = true, val notificationAccess: Boolean = false)

/** A scrolling column of readable width, centred on the 13″ screen. */
@Composable
fun Page(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 840.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) { content() }
    }
}
