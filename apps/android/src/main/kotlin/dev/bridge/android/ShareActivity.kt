package dev.bridge.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Share-sheet target ("Bridge" in any app's Share menu). A link or text (e.g. Chrome's Share → Bridge) goes straight
 * to the Mac over the control channel and opens there (F12). For one or more files it shows the device picker
 * and the transfer list. The sharing app's read permission lasts only as long as this activity, so each
 * file's descriptor is opened here and held for the upload (see [UriFile]).
 */
class ShareActivity : ComponentActivity() {
    /** Shown when the share had files but none of them could be read. */
    private var problem by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, BridgeService::class.java))
        val app = application as BridgeApplication
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (sharedUris().isEmpty() && !text.isNullOrBlank()) return shareText(app, text)
        if (savedInstanceState == null) {
            val uris = sharedUris()
            lifecycleScope.launch {
                val files = withContext(Dispatchers.IO) { uris.mapNotNull { UriFile.of(contentResolver, it, hold = true) } }
                problem = when {
                    uris.isEmpty() -> "Nothing to send."
                    files.isEmpty() -> "Bridge can't read the shared file${if (uris.size == 1) "" else "s"}."
                    files.size < uris.size -> "${uris.size - files.size} of ${uris.size} files can't be read and were left out."
                    else -> null
                }
                app.send(files)
            }
        }
        setContent {
            BridgeTheme {
                Surface(Modifier.fillMaxSize()) {
                    Page {
                        ScreenTitle("Bridge")
                        problem?.let { Notice(it) }
                        FilesPanel(app)
                        Button(onClick = ::finish) { Text("Done") }
                    }
                }
            }
        }
    }

    /** No UI: a toast says what happened (spec §7), then back to the sharing app. */
    private fun shareText(app: BridgeApplication, text: String) {
        val mac = app.bridge.state.value.paired.firstOrNull { it.online }
        val message = when {
            !app.features.share.on.value -> "Turn on \"Send links and text to the Mac\" in Bridge first"
            mac == null -> "Not connected to the Mac"
            else -> "Sent to ${mac.name}".also { app.bridge.share(text) }
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    @Suppress("DEPRECATION") // the typed getParcelable*Extra overloads need API 33
    private fun sharedUris(): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM),
        )
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM))
                .orEmpty()
        else -> emptyList()
    }
}
