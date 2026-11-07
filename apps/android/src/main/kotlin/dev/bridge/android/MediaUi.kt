package dev.bridge.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import bridge.protocol.MediaAction
import bridge.session.Bridge
import bridge.session.PeerStatus
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The per-feature switches for M2.3 (spec §7). */
@Composable
fun FeaturesPanel(features: Features) {
    Text("Media, battery and links", style = MaterialTheme.typography.titleLarge)
    FeatureSwitch("Control the Mac's music from here", features.media)
    FeatureSwitch("Show this tablet's battery on the Mac", features.battery)
    FeatureSwitch("Send links and text to the Mac, and receive them", features.share)
}

@Composable
private fun FeatureSwitch(label: String, toggle: Features.Toggle) {
    val on by toggle.on.collectAsState()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Switch(checked = on, onCheckedChange = toggle::set)
    }
}

/**
 * Media keys for whatever plays on the Mac, and its volume (spec §5.6). The Mac sends media keys, so it can't say what's
 * playing (ADR 0011); title, artist and progress show only if a peer ever reports them.
 */
@Composable
fun MediaCard(bridge: Bridge, peer: PeerStatus) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val m = peer.media ?: return@Column Text("Connecting to ${peer.name}'s media keys…")
            val title = m.title
            if (title == null) {
                Text("Controls whatever is playing on ${peer.name}", style = MaterialTheme.typography.titleMedium)
            } else {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull(m.artist, m.app).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }

            val position = m.position
            val duration = m.duration
            if (position != null && duration != null && duration > 0) {
                // The Mac doesn't send every second of playback; advance the position here while playing.
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(m, peer.mediaAt) {
                    while (m.playing) {
                        now = System.currentTimeMillis()
                        delay(1000)
                    }
                }
                val at = position + if (m.playing) (now - peer.mediaAt).coerceAtLeast(0) / 1000.0 else 0.0
                LinearProgressIndicator(progress = { (at / duration).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { bridge.media(MediaAction.PREV) }, Modifier.semantics { contentDescription = "Previous" }) { Text("⏮") }
                // Without a title the play state is unknown: one toggle, like the keyboard's ⏯ key.
                val label = when {
                    title == null -> "Play or pause" to "⏯"
                    m.playing -> "Pause" to "⏸"
                    else -> "Play" to "▶"
                }
                FilledIconButton(
                    onClick = { bridge.media(if (m.playing) MediaAction.PAUSE else MediaAction.PLAY) },
                    Modifier.semantics { contentDescription = label.first },
                ) { Text(label.second) }
                IconButton(onClick = { bridge.media(MediaAction.NEXT) }, Modifier.semantics { contentDescription = "Next" }) { Text("⏭") }
            }

            m.volume?.let { volume ->
                // Local while dragging; sent once on release so the Mac isn't asked 60 times a second.
                var dragged by remember(volume) { mutableFloatStateOf(volume.toFloat()) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Volume")
                    Slider(
                        value = dragged,
                        onValueChange = { dragged = it },
                        onValueChangeFinished = { bridge.media(MediaAction.VOLUME, dragged.roundToInt()) },
                        valueRange = 0f..100f,
                    )
                }
            }
        }
    }
}
