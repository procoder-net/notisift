package io.github.notisift.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.notisift.MainActivity
import io.github.notisift.R
import io.github.notisift.SiftApp
import kotlinx.coroutines.launch

/** Quick Settings tile: one tap turns sorting on or pauses it, without opening the app. */
class PauseTile : TileService() {
    private val app get() = application as SiftApp

    override fun onStartListening() {
        super.onStartListening()
        app.scope.launch { show(active = app.awaitSettings().let { !it.observe && !it.stopped }) }
    }

    override fun onClick() {
        super.onClick()
        // Without notification access there is nothing to turn on: open the app's one-step setup instead.
        if (!SiftListener.hasAccess(this)) return openApp()
        app.scope.launch {
            val active = app.awaitSettings().let { it.observe || it.stopped }
            app.settingsStore.update { it.copy(observe = !active, stopped = false) }
            if (active) SiftListener.instance?.cleanNow()
            show(active)
        }
    }

    private fun show(active: Boolean) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_sift)
        tile.label = getString(R.string.app_name)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (active) "Sorting" else "Paused"
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
