package dev.bridge.android

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import bridge.port.ClipboardPort

/**
 * ClipboardPort on ClipboardManager. Android 10+ only lets the app with window focus (or the
 * keyboard) read the clipboard, so reads happen while [MainActivity] is in front or from
 * [ClipboardActivity] (quick-settings tile, notification action). Writing works in the background.
 * Never logs clipboard contents.
 */
class AndroidClipboard(private val context: Context) : ClipboardPort {
    private val manager = context.getSystemService(ClipboardManager::class.java)
    private var onLocalChange: (String) -> Unit = {}
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { sendCurrent() }

    override fun watch(onLocalChange: (String) -> Unit) {
        this.onLocalChange = onLocalChange
    }

    /** The clip label is the Bridge origin tag. */
    override fun write(text: String) = manager.setPrimaryClip(ClipData.newPlainText(ORIGIN, text))

    /** Hands the current clipboard text to core. Returns false if there was nothing to send. Needs window focus. */
    fun sendCurrent(): Boolean {
        val clip = manager.primaryClip ?: return false
        val description = clip.description
        if (description.label?.toString() == ORIGIN || !description.hasMimeType("text/*")) return false
        // Password managers and keyboards set this (API 33+); older Android has no flag, so nothing is skipped there.
        if (description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) return false
        val text = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: return false
        onLocalChange(text)
        return true
    }

    /** Send screen's Paste: the clipboard's text, whoever copied it. Needs window focus; null if not text. */
    fun readText(): String? {
        val clip = manager.primaryClip?.takeIf { it.description.hasMimeType("text/*") && it.itemCount > 0 } ?: return null
        return clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotEmpty() }
    }

    /** Listen for copies while an activity is in front; the listener can't read the clipboard otherwise. */
    fun setForeground(foreground: Boolean) {
        if (foreground) manager.addPrimaryClipChangedListener(listener) else manager.removePrimaryClipChangedListener(listener)
    }

    private companion object {
        const val ORIGIN = "dev.bridge.origin"
    }
}
