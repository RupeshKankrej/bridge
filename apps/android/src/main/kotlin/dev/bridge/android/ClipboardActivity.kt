package dev.bridge.android

import android.app.Activity
import android.widget.Toast

/**
 * Invisible activity behind the "Send clipboard" tile and notification action. Android 10+ only
 * lets the focused app read the clipboard, so this exists just long enough to gain window focus,
 * read, hand the text to core, and finish.
 */
class ClipboardActivity : Activity() {
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || isFinishing) return
        val app = application as BridgeApplication
        val message = when {
            app.bridge.state.value.paired.none { it.online } -> "Not connected"
            app.clipboard.sendCurrent() -> "Clipboard sent"
            else -> "Nothing new to send"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}
