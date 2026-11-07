package dev.bridge.android

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bridge.port.FileSource
import bridge.port.OutgoingFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * LocalSend's Send tab: pick what to send by type, then tap a nearby device. Picks add to the selection
 * ([BridgeApplication.pendingSend]) until it's sent or cleared.
 */
@Composable
fun SendScreen(app: BridgeApplication) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    val selection by app.pendingSend.collectAsState()
    val filesProblem by app.filesProblem.collectAsState()
    var writing by remember { mutableStateOf(false) }

    // Persistable grants keep picked documents readable later, e.g. when an upload resumes after a drop.
    fun keep(uri: Uri) = runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    fun add(load: () -> List<OutgoingFile>) = scope.launch { app.select(withContext(Dispatchers.IO) { load() }) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach(::keep)
        add { uris.mapNotNull { UriFile.of(resolver, it, hold = false) } }
    }
    // The system photo picker: no storage permission. Its grant can't be persisted everywhere, so the file is held open.
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        add { uris.mapNotNull { UriFile.of(resolver, it, hold = true) } }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree ?: return@rememberLauncherForActivityResult
        keep(tree)
        add { UriFile.ofTree(resolver, tree) }
    }

    ScreenTitle("Send")
    filesProblem?.let { Notice(it) }

    Text("Selection", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TypeTile("File", R.drawable.ic_file, Modifier.weight(1f)) { pickFiles.launch(arrayOf("*/*")) }
        TypeTile("Media", R.drawable.ic_image, Modifier.weight(1f)) {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
        TypeTile("Text", R.drawable.ic_text, Modifier.weight(1f)) { writing = true }
        TypeTile("Folder", R.drawable.ic_folder, Modifier.weight(1f)) { pickFolder.launch(null) }
        TypeTile("Paste", R.drawable.ic_paste, Modifier.weight(1f)) {
            val text = app.clipboard.readText()
            if (text == null) Toast.makeText(context, "The clipboard has no text", Toast.LENGTH_SHORT).show() else app.select(listOf(TextFile(text)))
        }
    }

    if (selection.isNotEmpty()) {
        val total = Formatter.formatShortFileSize(context, selection.sumOf { it.size })
        Section("${selection.size} item${if (selection.size == 1) "" else "s"} · $total") {
            selection.take(MAX_SHOWN).forEach { file ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(iconFor(file)), null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        file.preview ?: file.path,
                        Modifier.weight(1f).padding(horizontal = 16.dp),
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    Text(Formatter.formatShortFileSize(context, file.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { app.unselect(file) }) { Icon(painterResource(R.drawable.ic_close), "Remove") }
                }
            }
            if (selection.size > MAX_SHOWN) Text("and ${selection.size - MAX_SHOWN} more", Modifier.padding(16.dp, 4.dp))
            TextButton(onClick = app::cancelPending, Modifier.padding(horizontal = 8.dp)) { Text("Clear") }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Nearby devices", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = app.files::refresh) { Icon(painterResource(R.drawable.ic_refresh), "Refresh") }
    }
    Section {
        DeviceList(app, enabled = selection.isNotEmpty())
    }
    if (selection.isEmpty()) Text("Pick something above, then tap a device to send it.", color = MaterialTheme.colorScheme.onSurfaceVariant)

    if (writing) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { writing = false },
            title = { Text("Send text") },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 4) },
            confirmButton = {
                Button(onClick = {
                    app.select(listOf(TextFile(text)))
                    writing = false
                }, enabled = text.isNotBlank()) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { writing = false }) { Text("Cancel") } },
        )
    }
}

private const val MAX_SHOWN = 20

@DrawableRes
private fun iconFor(file: OutgoingFile) = when {
    file.preview != null -> R.drawable.ic_text
    file.mime.startsWith("image/") || file.mime.startsWith("video/") -> R.drawable.ic_image
    file.path.contains('/') -> R.drawable.ic_folder
    else -> R.drawable.ic_file
}

@Composable
private fun TypeTile(label: String, @DrawableRes icon: Int, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier.height(96.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(icon), null, Modifier.size(28.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * A LocalSend text message: a .txt file whose `preview` carries the text, so LocalSend shows it as a message instead of
 * saving a file (and Bridge saves it as the .txt). Named like LocalSend's own.
 */
class TextFile(text: String) : OutgoingFile {
    private val bytes = text.encodeToByteArray()
    override val path = "${UUID.randomUUID()}.txt"
    override val size = bytes.size.toLong()
    override val mime = "text/plain"
    override val preview: String = text

    override fun open(offset: Long): FileSource {
        var sent = offset.toInt()
        return object : FileSource {
            override fun read(max: Int): ByteArray = bytes.copyOfRange(sent, minOf(bytes.size, sent + max)).also { sent += it.size }

            override fun close() = Unit
        }
    }
}
