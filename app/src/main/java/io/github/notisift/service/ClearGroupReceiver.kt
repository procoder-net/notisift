package io.github.notisift.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.notisift.SiftApp
import io.github.notisift.engine.Action
import io.github.notisift.engine.Category
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Buttons on NotiSift's drawer rows: "Clear" marks a group's (or a day's) waiting items done; "Show" brings running ones back. */
class ClearGroupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_SHOW_RUNNING, false)) {
            SiftListener.instance?.showRunning()
            return
        }
        val ids = intent.getLongArrayExtra(EXTRA_IDS)?.toList()
        val category = Category.entries.firstOrNull { it.id == intent.getStringExtra(EXTRA_CATEGORY) }
        if (ids == null && category == null) return
        val app = context.applicationContext as SiftApp
        val done = goAsync()
        app.scope.launch {
            try {
                // A day row clears exactly the items it listed; a group row clears its whole group.
                if (ids != null) app.dao.setDismissed(ids, true) else if (category != null) app.dao.dismissCategory(category.id, listOf(Action.BUNDLE.id))
                app.digest.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("NotiSift", "Clear failed", e)
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_SHOW_RUNNING = "show_running"
        const val EXTRA_IDS = "ids"
    }
}
