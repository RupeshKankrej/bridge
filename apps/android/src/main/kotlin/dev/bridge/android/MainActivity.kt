package dev.bridge.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import bridge.session.Bridge
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class MainActivity : ComponentActivity() {
    private val bridge get() = (application as BridgeApplication).bridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Without it the foreground-service notification is hidden; the service still runs.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        startForegroundService(Intent(this, BridgeService::class.java))
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { HomeScreen(bridge, onScan = ::scan) }
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
private fun HomeScreen(bridge: Bridge, onScan: () -> Unit) {
    val state by bridge.state.collectAsState()
    Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
    }
}
