package dev.bridge.android

import android.app.Activity
import android.net.wifi.WifiManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import bridge.protocol.BUTTON_LEFT
import bridge.protocol.BUTTON_RIGHT
import bridge.protocol.InputGesture
import bridge.protocol.MOD_COMMAND
import bridge.protocol.MOD_CONTROL
import bridge.protocol.MOD_OPTION
import bridge.protocol.MOD_SHIFT
import bridge.protocol.MacKey
import bridge.session.Bridge
import kotlin.math.abs
import kotlin.math.min

// shortcut: pointer feel tuned by guesswork, calibrate on the Pad 3 during the M2.2 gate.
/** Mac points per dp of finger travel when moving slowly; precise aiming. */
private const val BASE_GAIN = 1.0f

/** Extra gain per dp/ms of finger speed, capped at [MAX_GAIN]: a fast flick crosses the screen. */
private const val ACCEL = 1.5f
private const val MAX_GAIN = 4f

/** Scroll pixels per dp of two-finger travel. */
private const val SCROLL_GAIN = 1.5f

/** A finger down and up within this, without moving past the touch slop, is a tap (click). */
private const val TAP_MS = 250

/** Pinch span ratio for one zoom step (⌘= / ⌘−). */
private const val ZOOM_STEP = 1.25f

/** Full-screen trackpad with an optional keyboard bar (spec §5.5). Back or Done returns to the home screen. */
@Composable
fun TrackpadScreen(bridge: Bridge, onClose: () -> Unit) {
    val state by bridge.state.collectAsState()
    var keyboard by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    Immersive()
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val mac = state.paired.firstOrNull { it.online }
            Text(mac?.let { "Controlling ${it.name}" } ?: "Mac offline, reconnecting…", Modifier.weight(1f))
            TextButton(onClick = { keyboard = !keyboard }) { Text(if (keyboard) "Hide keyboard" else "Keyboard") }
            TextButton(onClick = onClose) { Text("Done") }
        }
        state.message?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        if (keyboard) KeyboardBar(bridge)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.large)
                // Keeps Android's edge back-swipe from stealing strokes that start near the sides (OS caps it at 200 dp per edge).
                .systemGestureExclusion()
                .pointerInput(bridge) { trackpad(bridge) },
        )
    }
}

/**
 * Hides the status and navigation bars and keeps the screen on while the trackpad is up. Also turns off Wi-Fi power
 * save: the Pad 3's radio dozes between packets (pings to the router spike past 200 ms), which made the pointer lag and
 * jitter. Android grants low-latency mode only to the foreground app with the screen on, which is exactly this screen.
 */
@Composable
private fun Immersive() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as Activity).window
        val bars = WindowCompat.getInsetsController(window, view)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        val wifi = view.context.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "bridge:trackpad").apply { acquire() }
        onDispose {
            wifi.release()
            bars.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }
}

/**
 * One gesture from first finger down to last finger up. One finger moves the pointer; a short touch is a click.
 * Two fingers either scroll or pinch, decided once they've moved past the touch slop; a short two-finger touch
 * is a right-click. Core coalesces the deltas, so every touch sample is passed straight on.
 */
private suspend fun PointerInputScope.trackpad(bridge: Bridge) = awaitEachGesture {
    val first = awaitFirstDown(requireUnconsumed = false)
    val slop = viewConfiguration.touchSlop
    var fingers = 1
    var travel = 0f
    var twoFinger: Boolean? = null // null = undecided, true = scroll, false = pinch
    var startSize = 0f
    var zoom = 1f
    var end = first.uptimeMillis
    while (true) {
        val event = awaitPointerEvent()
        val down = event.changes.filter { it.pressed }
        event.changes.forEach { it.consume() }
        if (down.isEmpty()) {
            end = event.changes.maxOf { it.uptimeMillis }
            break
        }
        if (down.size > fingers) {
            fingers = down.size
            // Measured on the next event: a finger that just landed isn't counted in the centroid yet, so this one reads 0.
            startSize = 0f
        }
        if (fingers == 1) {
            val c = down[0]
            val d = c.position - c.previousPosition
            val dist = d.getDistance()
            travel += dist
            val speed = dist / density / (c.uptimeMillis - c.previousUptimeMillis).coerceAtLeast(1)
            val gain = min(BASE_GAIN + ACCEL * speed, MAX_GAIN) / density
            bridge.pointer((d.x * gain).toDouble(), (d.y * gain).toDouble())
        } else if (down.size >= 2) {
            val pan = event.calculatePan()
            val size = event.calculateCentroidSize()
            if (startSize == 0f) startSize = size
            travel += pan.getDistance()
            val spread = abs(size - startSize)
            if (twoFinger == null && (travel > slop || spread > slop)) twoFinger = travel >= spread
            when (twoFinger) {
                true -> bridge.pointer(0.0, 0.0, (pan.x * SCROLL_GAIN / density).toDouble(), (pan.y * SCROLL_GAIN / density).toDouble())
                false -> {
                    zoom *= event.calculateZoom()
                    while (zoom > ZOOM_STEP) { bridge.zoom(InputGesture.ZOOM_IN); zoom /= ZOOM_STEP }
                    while (zoom < 1 / ZOOM_STEP) { bridge.zoom(InputGesture.ZOOM_OUT); zoom *= ZOOM_STEP }
                }
                null -> {}
            }
        }
        // After lifting one of two fingers the other one doesn't move the pointer: the gesture stays a two-finger one.
    }
    if (travel <= slop && twoFinger == null && end - first.uptimeMillis < TAP_MS) {
        bridge.buttons(if (fingers >= 2) BUTTON_RIGHT else BUTTON_LEFT)
        bridge.buttons(0)
    }
}

private const val SENTINEL = " "

/**
 * Keys the soft keyboard lacks, sticky modifiers, and a text field that forwards what the IME types. The field holds
 * one [SENTINEL] space so backspace on an "empty" field still reaches us; each change is diffed against the previous
 * text and sent as Delete presses plus new text, which also covers IME composition and autocorrect rewrites.
 */
@Composable
private fun KeyboardBar(bridge: Bridge) {
    var mods by remember { mutableIntStateOf(0) }
    var field by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(SENTINEL.length))) }

    fun key(code: Int) {
        bridge.key(code, mods)
        mods = 0
    }

    /** With a modifier held the next character is a shortcut (⌘C), which needs its key code, not text. */
    fun typed(text: String) {
        var rest = text
        val shortcut = rest.firstOrNull()?.takeIf { mods != 0 }?.let(MacKey::forChar)
        if (shortcut != null) {
            bridge.key(shortcut.first, mods or shortcut.second)
            rest = rest.substring(1)
        }
        if (text.isNotEmpty()) mods = 0
        // Return is a key press: a typed "\n" means nothing to most Mac apps.
        rest.split('\n').forEachIndexed { i, run ->
            if (i > 0) bridge.key(MacKey.RETURN)
            bridge.type(run)
        }
    }

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("⌃" to MOD_CONTROL, "⌥" to MOD_OPTION, "⌘" to MOD_COMMAND, "⇧" to MOD_SHIFT).forEach { (label, bit) ->
                FilterChip(selected = mods and bit != 0, onClick = { mods = mods xor bit }, label = { Text(label) })
            }
            listOf("esc" to MacKey.ESCAPE, "tab" to MacKey.TAB, "←" to MacKey.LEFT, "↑" to MacKey.UP, "↓" to MacKey.DOWN, "→" to MacKey.RIGHT, "⌦" to MacKey.FORWARD_DELETE)
                .forEach { (label, code) -> OutlinedButton(onClick = { key(code) }) { Text(label) } }
        }
        OutlinedTextField(
            value = field,
            onValueChange = { new ->
                val old = field.text
                val common = old.commonPrefixWith(new.text).length
                repeat(old.codePointCount(common, old.length)) { key(MacKey.DELETE) }
                typed(new.text.substring(common))
                // Reset only between words: replacing the text mid-composition would cancel the IME's word.
                field = if (new.composition == null) TextFieldValue(SENTINEL, TextRange(SENTINEL.length)) else new
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Type here to type on the Mac") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        )
    }
}
