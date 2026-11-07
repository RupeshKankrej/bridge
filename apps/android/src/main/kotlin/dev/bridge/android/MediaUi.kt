package dev.bridge.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import bridge.protocol.MediaAction
import bridge.session.Bridge
import bridge.session.PeerStatus
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Media keys for whatever plays on the Mac, and its volume (spec §5.6). The Mac sends media keys, so it can't say what's
 * playing (ADR 0011); title, artist and progress show only if a peer ever reports them.
 */
@Composable
fun MediaCard(bridge: Bridge, peer: PeerStatus) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                IconButton(onClick = { bridge.media(MediaAction.PREV) }) { Icon(painterResource(R.drawable.ic_skip_previous), "Previous") }
                // Without a title the play state is unknown: one toggle, like the keyboard's ⏯ key.
                val label = when {
                    title == null -> "Play or pause"
                    m.playing -> "Pause"
                    else -> "Play"
                }
                FilledIconButton(
                    onClick = { bridge.media(if (m.playing) MediaAction.PAUSE else MediaAction.PLAY) },
                    Modifier.size(56.dp),
                ) { Icon(painterResource(R.drawable.ic_play_pause), label) }
                IconButton(onClick = { bridge.media(MediaAction.NEXT) }) { Icon(painterResource(R.drawable.ic_skip_next), "Next") }
            }

            m.volume?.let { volume ->
                // Local while dragging; sent once on release so the Mac isn't asked 60 times a second.
                var dragged by remember(volume) { mutableFloatStateOf(volume.toFloat()) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(R.drawable.ic_volume), "Volume")
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
