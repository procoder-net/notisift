package io.github.notisift.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.notisift.SiftApp
import kotlinx.coroutines.launch

/**
 * Wakes NotiSift at the next "Later at set times" hour so the waiting rows appear then. Inexact on purpose (a few
 * minutes either way needs no special permission and saves battery); every drawer refresh re-arms it, including
 * the one when NotiSift reconnects after a reboot.
 */
class LaterAlarm : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as SiftApp
        // Keep the process alive until the drawer is refreshed; a delayed refresh could be killed first.
        val pending = goAsync()
        app.scope.launch {
            try {
                app.digest.refresh()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val WINDOW_MS = 5 * 60 * 1000L

        /** Arms the alarm for [at], or cancels it when there is no schedule. */
        fun schedule(context: Context, at: Long?) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(context, LaterAlarm::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            if (at == null) alarms.cancel(pi) else alarms.setWindow(AlarmManager.RTC, at, WINDOW_MS, pi)
        }
    }
}
