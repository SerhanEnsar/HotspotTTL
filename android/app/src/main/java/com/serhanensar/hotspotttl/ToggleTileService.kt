package com.serhanensar.hotspotttl

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Hızlı ayarlar kutucuğu: tek dokunuşla aç/kapat. */
class ToggleTileService : TileService() {

    override fun onStartListening() = update()

    override fun onClick() {
        val started = try {
            when {
                TtlVpnService.isRunning -> { TtlVpnService.stop(this); true }
                VpnService.prepare(this) == null -> { TtlVpnService.start(this); true }
                else -> false // VPN izni henüz verilmemiş
            }
        } catch (_: IllegalStateException) {
            false // arka plandan servis başlatma engellendi
        }
        if (!started) {
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_START, true)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }
        qsTile?.let {
            it.state = if (TtlVpnService.isRunning) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            it.updateTile()
        }
    }

    private fun update() {
        val tile = qsTile ?: return
        tile.state = if (TtlVpnService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
