package dev.bridge.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Without it the foreground-service notification is hidden; the service still runs.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        startForegroundService(Intent(this, BridgeService::class.java))
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { HomeScreen(app, onScan = ::scan) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as BridgeApplication).clipboard.setForeground(true)
    }

    override fun onPause() {
        (application as BridgeApplication).clipboard.setForeground(false)
        super.onPause()
    }

    /** Play services draws the camera UI, so Bridge needs no CAMERA permission. */
    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let(bridge::pair) }
            .addOnFailureListener { Toast.makeText(this, "Scanner unavailable: ${it.message}", Toast.LENGTH_LONG).show() }
    }
}

@Composable
private fun HomeScreen(app: BridgeApplication, onScan: () -> Unit) {
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

        state.paired.forEach { peer ->
            Text("Paired with ${peer.name}", style = MaterialTheme.typography.titleLarge)
            Text(if (peer.online) "Connected" else "Offline, reconnecting…")
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
            state.paired.isEmpty() -> Button(onClick = onScan) { Text("Scan QR code") }
        }

        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text("Files", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { pickFiles.launch(arrayOf("*/*")) }) { Text("Send files") }
            OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Send folder") }
        }
        Text("Received files go to Download/Bridge", style = MaterialTheme.typography.bodySmall)
        FilesPanel(app)
    }
}
