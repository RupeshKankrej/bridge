package dev.bridge.android

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick-settings tile for AirDrop visibility (spec §5.10): each tap goes Off → Bridge devices only → Everyone for 10 minutes. */
class AirDropTile : TileService() {
    private val features get() = (application as BridgeApplication).features

    override fun onStartListening() = show()

    override fun onClick() {
        val next = Features.AirDrop.entries[(features.airDrop.value.ordinal + 1) % Features.AirDrop.entries.size]
        features.setAirDrop(next)
        show()
    }

    private fun show() {
        val tile = qsTile ?: return
        val visibility = features.airDrop.value
        tile.state = if (visibility == Features.AirDrop.OFF) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.subtitle = airDropLabel(visibility)
        tile.updateTile()
    }
}

fun airDropLabel(v: Features.AirDrop) = when (v) {
    Features.AirDrop.OFF -> "Receiving off"
    Features.AirDrop.BRIDGE -> "Bridge devices only"
    Features.AirDrop.EVERYONE -> "Everyone for 10 minutes"
}
