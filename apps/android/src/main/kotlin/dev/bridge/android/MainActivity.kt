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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val app get() = application as BridgeApplication
    private val bridge get() = app.bridge
    private val batteryExempt = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Without it the foreground-service notification is hidden; the service still runs.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        startForegroundService(Intent(this, BridgeService::class.java))
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    HomeScreen(app, batteryExempt.value, onScan = ::scan, onAllowBackground = ::requestBatteryExemption)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as BridgeApplication).clipboard.setForeground(true)
        // Re-read on every resume: the user answers the system dialog or changes it in Settings.
        batteryExempt.value = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }

    override fun onPause() {
        (application as BridgeApplication).clipboard.setForeground(false)
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

@Composable
private fun HomeScreen(app: BridgeApplication, batteryExempt: Boolean, onScan: (host: String) -> Unit, onAllowBackground: () -> Unit) {
    val bridge = app.bridge
    val state by bridge.state.collectAsState()
    val resolver = app.contentResolver
    val scope = rememberCoroutineScope()
    // Persistable grants keep picked files readable later, e.g. when an upload resumes after a drop.
    fun keep(uri: Uri) = runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    fun sendFrom(load: () -> List<UriFile>) = scope.launch { app.send(withContext(Dispatchers.IO) { load() }) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach(::keep)
        sendFrom { uris.mapNotNull { UriFile.of(resolver, it, hold = false) } }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree ?: return@rememberLauncherForActivityResult
        keep(tree)
        sendFrom { UriFile.ofTree(resolver, tree) }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Bridge", style = MaterialTheme.typography.headlineMedium)

        if (!batteryExempt) {
            Text("Bridge needs to run in the background to stay connected with the screen off.")
            Button(onClick = onAllowBackground) { Text("Allow background use") }
        }

        state.paired.forEach { peer ->
            Text("Paired with ${peer.name}", style = MaterialTheme.typography.titleLarge)
            Text(if (peer.online) "Connected" else "Offline, reconnecting…")
            val host = peer.host
            // For networks that block mDNS: the Mac got a new address and can't announce it.
            if (!peer.online && host != null) {
                var typed by remember(host) { mutableStateOf(host) }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(typed, { typed = it }, label = { Text("Mac address") }, singleLine = true)
                    OutlinedButton(onClick = { bridge.setAddress(peer.fingerprint, typed) }, enabled = typed.isNotBlank()) { Text("Connect") }
                }
            }
            OutlinedButton(onClick = { bridge.unpair(peer.fingerprint) }) { Text("Forget") }
        }

        val pairing = state.pairing
        when {
            pairing != null -> {
                Text("Check that ${pairing.peerName} shows the same code")
                Text(pairing.code, style = MaterialTheme.typography.displayLarge, fontFamily = FontFamily.Monospace)
                if (pairing.awaitingLocalConfirm) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(onClick = bridge::confirmPairing) { Text("Confirm") }
                        OutlinedButton(onClick = bridge::rejectPairing) { Text("Cancel") }
                    }
                } else {
                    Text("Waiting for ${pairing.peerName}…")
                }
            }
            state.paired.isEmpty() -> {
                var host by remember { mutableStateOf("") }
                Button(onClick = { onScan(host) }) { Text("Scan QR code") }
                OutlinedTextField(
                    host, { host = it }, singleLine = true,
                    label = { Text("Mac address (optional)") },
                    supportingText = { Text("Only if the QR's address doesn't work here, e.g. Wi-Fi that isolates devices") },
                )
            }
        }

        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text("Files", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { pickFiles.launch(arrayOf("*/*")) }) { Text("Send files") }
            OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Send folder") }
        }
        Text("Received files go to Download/Bridge", style = MaterialTheme.typography.bodySmall)
        val filesProblem by app.filesProblem.collectAsState()
        filesProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        FilesPanel(app)
    }
}
