package dev.soundlock

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class LockTileService : TileService() {
    override fun onStartListening() {
        val s = GuardState.current
        qsTile?.apply {
            state = if (!s.connected || !s.armed) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            subtitle = when {
                !s.connected -> getString(R.string.tile_off)
                !s.armed -> getString(R.string.status_paused_title)
                s.unlocked || s.allowed -> getString(R.string.tile_unlocked)
                else -> getString(R.string.tile_locked)
            }
            contentDescription = getString(R.string.tile_description, subtitle)
            updateTile()
        }
    }
    override fun onClick() {
        val s = GuardState.current
        if (s.connected && s.armed && (s.unlocked || s.allowed)) {
            GuardState.service?.lockNow()
            onStartListening()
        } else unlockAndRun {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        }
    }
}
