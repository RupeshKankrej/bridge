package dev.bridge.android

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick-settings tile "Send clipboard": opens [ClipboardActivity], which can read the clipboard. */
class SendClipboardTile : TileService() {
    override fun onStartListening() {
        // A one-shot button, not a toggle.
        qsTile?.run {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    // The Intent overload is only reached below API 34, where it is the only option.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = Intent(this, ClipboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
