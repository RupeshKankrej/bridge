package dev.bridge.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
 * Share-sheet target ("Bridge" in any app's Share menu) for one or more files. Shows the device picker
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
        if (savedInstanceState == null) {
            val uris = sharedUris()
            lifecycleScope.launch {
                val files = withContext(Dispatchers.IO) { uris.mapNotNull { UriFile.of(contentResolver, it, hold = true) } }
                problem = when {
                    uris.isEmpty() -> "Nothing to send: Bridge shares files, not text or links (yet)."
                    files.isEmpty() -> "Bridge can't read the shared file${if (uris.size == 1) "" else "s"}."
                    files.size < uris.size -> "${uris.size - files.size} of ${uris.size} files can't be read and were left out."
                    else -> null
                }
                app.send(files)
            }
        }
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Bridge", style = MaterialTheme.typography.headlineMedium)
                        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        FilesPanel(app)
                        Button(onClick = ::finish) { Text("Done") }
                    }
                }
            }
        }
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
