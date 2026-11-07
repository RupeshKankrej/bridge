package dev.bridge.android

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bridge.files.Offer
import bridge.files.Transfer
import bridge.files.TransferFile
import bridge.files.TransferStatus

private val Accept = Color(0xFF2E7D32)
private val Decline = Color(0xFFC62828)
private val ACTIVE = setOf(TransferStatus.WAITING, TransferStatus.RUNNING, TransferStatus.PAUSED)

/** Share sheet: offer prompt, device picker for the shared files, and the transfer list. */
@Composable
fun FilesPanel(app: BridgeApplication) {
    OfferPrompt(app)
    val pending by app.pendingSend.collectAsState()
    if (pending.isNotEmpty()) {
        Section("Send ${pending.size} file${if (pending.size == 1) "" else "s"} to") {
            DeviceList(app)
            TextButton(onClick = app::cancelPending, Modifier.padding(horizontal = 8.dp)) { Text("Cancel") }
        }
    }
    TransferList(app)
}

/** Accept / Decline for an incoming offer; shown over whichever screen is open. */
@Composable
fun OfferPrompt(app: BridgeApplication) {
    val state by app.files.state.collectAsState()
    state.offer?.let { OfferDialog(it) { accept -> app.files.respond(it.id, accept) } }
}

/** Nearby LocalSend and Bridge devices; a tap sends the selection there (LocalSend's Send tab). */
@Composable
fun DeviceList(app: BridgeApplication, enabled: Boolean = true) {
    val state by app.files.state.collectAsState()
    if (state.nearby.isEmpty()) {
        SettingRow("No devices found", "Bridge on your Mac, or LocalSend on any device on this Wi-Fi", R.drawable.ic_refresh, onClick = app.files::refresh)
    }
    state.nearby.forEach { peer ->
        val paired = app.isPaired(peer)
        val icon = when (peer.deviceType) {
            "desktop", "laptop", "web", "headless", "server" -> R.drawable.ic_laptop
            else -> R.drawable.ic_tablet
        }
        SettingRow(
            peer.alias,
            if (paired) "Paired · ${peer.host}" else "LocalSend · ${peer.host}",
            icon,
            onClick = { if (enabled) app.sendPending(peer) },
            trailing = { Icon(painterResource(R.drawable.ic_send), "Send", tint = MaterialTheme.colorScheme.primary) },
        )
    }
}

/** Newest first; the list only grows within a run. */
@Composable
fun TransferList(app: BridgeApplication, limit: Int = 10) {
    val state by app.files.state.collectAsState()
    state.transfers.takeLast(limit).reversed().forEach { TransferCard(it) { app.files.cancel(it.id) } }
}

@Composable
private fun OfferDialog(offer: Offer, onAnswer: (Boolean) -> Unit) {
    val context = LocalContext.current
    val total = Formatter.formatShortFileSize(context, offer.files.sumOf { it.size })
    AlertDialog(
        onDismissRequest = {}, // answer explicitly; the offer times out on its own
        title = { Text("${offer.senderName} wants to send ${offer.files.size} file${if (offer.files.size == 1) "" else "s"} ($total)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                offer.files.take(8).forEach { FileLine(it.name, Formatter.formatShortFileSize(context, it.size)) }
                if (offer.files.size > 8) Text("and ${offer.files.size - 8} more", style = MaterialTheme.typography.bodySmall)
            }
        },
        dismissButton = {
            Button(onClick = { onAnswer(false) }, colors = ButtonDefaults.buttonColors(containerColor = Decline)) { Text("Decline") }
        },
        confirmButton = {
            Button(onClick = { onAnswer(true) }, colors = ButtonDefaults.buttonColors(containerColor = Accept)) { Text("Accept") }
        },
    )
}

@Composable
private fun TransferCard(transfer: Transfer, onCancel: () -> Unit) {
    val context = LocalContext.current
    fun bytes(n: Long) = Formatter.formatShortFileSize(context, n)
    val total = transfer.files.sumOf { it.size }
    val done = transfer.files.sumOf { it.done }
    val active = transfer.status in ACTIVE
    var expanded by remember { mutableStateOf(false) }
    val summary = when (transfer.status) {
        TransferStatus.RUNNING -> "${bytes(done)} of ${bytes(total)} · ${bytes(transfer.bytesPerSecond)}/s"
        TransferStatus.PAUSED -> "Reconnecting…"
        TransferStatus.WAITING -> if (transfer.incoming) "Waiting" else "Waiting for ${transfer.peerName}"
        TransferStatus.DONE -> "Done · ${bytes(total)}"
        TransferStatus.DECLINED -> "Declined"
        TransferStatus.CANCELLED -> "Cancelled"
        else -> "Failed"
    }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    painterResource(if (transfer.incoming) R.drawable.ic_download else R.drawable.ic_upload),
                    if (transfer.incoming) "Received from" else "Sent to",
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(transfer.peerName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(summary, style = MaterialTheme.typography.bodySmall)
                if (active) TextButton(onClick = onCancel) { Text("Cancel") }
            }
            if (active) LinearProgressIndicator(progress = { if (total == 0L) 0f else done.toFloat() / total }, Modifier.fillMaxWidth())
            transfer.error?.takeIf { !active }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (transfer.files.size == 1) {
                FileRow(transfer.files[0], ::bytes)
            } else {
                Text(
                    (if (expanded) "▾ " else "▸ ") + "${transfer.files.size} files",
                    Modifier.clickable { expanded = !expanded },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (expanded) transfer.files.forEach { FileRow(it, ::bytes) }
            }
        }
    }
}

@Composable
private fun FileRow(file: TransferFile, bytes: (Long) -> String) {
    val status = when (file.status) {
        TransferStatus.DONE -> bytes(file.size)
        TransferStatus.RUNNING, TransferStatus.PAUSED -> "${bytes(file.done)} / ${bytes(file.size)}"
        else -> file.status.name.lowercase()
    }
    FileLine(file.name, status)
}

@Composable
private fun FileLine(name: String, detail: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = MaterialTheme.typography.bodySmall)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
