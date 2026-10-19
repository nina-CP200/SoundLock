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
            subtitle = when { !s.connected -> "尚未启用"; !s.armed -> "保护已暂停"; s.unlocked -> "允许外放 · 点击上锁"; else -> "已上锁 · 点击查看" }
            contentDescription = "声锁，$subtitle"
            updateTile()
        }
    }
    override fun onClick() {
        val s = GuardState.current
        if (s.connected && s.armed && s.unlocked) {
            GuardState.service?.lockNow()
            onStartListening()
        } else unlockAndRun {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        }
    }
}
