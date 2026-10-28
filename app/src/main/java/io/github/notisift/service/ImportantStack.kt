package io.github.notisift.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import io.github.notisift.R
import io.github.notisift.SiftApp
import io.github.notisift.data.Entry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Android silently drops an app's notifications past ~5 posts a second, so every post and cancel goes through
 * here, and summary rows are only re-posted when what they show actually changed.
 */
class Pacer(private val gapMs: Long = 350) {
    private val mutex = Mutex()
    private var last = 0L
    private val shown = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Posts [n] unless the same title, text and lines are already showing under this tag and id. */
    suspend fun notifyIfChanged(manager: NotificationManager, tag: String, id: Int, n: Notification) {
        val x = n.extras
        val signature = listOf(
            x.getCharSequence(Notification.EXTRA_TITLE), x.getCharSequence(Notification.EXTRA_TEXT),
            x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n"), x.getCharSequence(Notification.EXTRA_SUMMARY_TEXT),
            // `when` sets the row's place in drawers that order by time.
            n.`when`,
        ).joinToString("\u0000").hashCode()
        if (shown["$tag#$id"] == signature) return
        run { manager.notify(tag, id, n) }
        shown["$tag#$id"] = signature
    }

    /** Called whenever one of NotiSift's notifications goes away, so the next refresh posts it again. */
    fun forget(tag: String?, id: Int) {
        shown.remove("$tag#$id")
    }

    suspend fun <T> run(block: () -> T): T = mutex.withLock {
        val wait = last + gapMs - SystemClock.elapsedRealtime()
        if (wait > 0) delay(wait)
        try {
            block()
        } finally {
            last = SystemClock.elapsedRealtime()
        }
    }
}

/**
 * The "⭐ Important" stack at the top of the drawer. NotiSift removes every original, so important ones are
 * re-shown here as real rows: each can be expanded, swiped away or replied to like the original, because it is
 * rebuilt from the original (tap target and buttons included). Only the newest [MAX_ROWS] are shown to stay well
 * under Android's 50-notifications-per-app cap; older ones stay in NotiSift's Important tab.
 */
class ImportantStack(
    private val context: Context,
    private val manager: NotificationManager,
    private val pacer: Pacer,
    private val openNotiSift: () -> PendingIntent,
) {
    private val app get() = context.applicationContext as SiftApp

    /** Shows a copy of [sbn]. Call after logging it, so the header's count includes it. */
    suspend fun show(sbn: StatusBarNotification, appLabel: String, templateKey: String): Boolean = io {
        val posted = try {
            val repeats = mergeRepeats(sbn.key, templateKey)
            val n = fromOriginal(sbn, if (repeats > 1) "$appLabel · ${repeats}× today" else appLabel)
            pacer.run { manager.notify(tag(sbn.key), 0, n) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        // The copy is up; a failure tidying the header must not make the caller think it isn't.
        if (posted) runCatching { afterChange() }.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        posted
    }

    /**
     * The same kind of alert again (a camera's "Motion detected" every few minutes) replaces the earlier rows
     * instead of stacking up; the newest row says how many there were today. Returns that count.
     */
    private suspend fun mergeRepeats(key: String, templateKey: String): Int {
        app.dao.shownLike(templateKey, key, since()).forEach { older ->
            app.dao.dismiss(older.id)
            pacer.run { manager.cancel(tag(older.key), 0) }
        }
        return app.dao.countLike(templateKey, since())
    }

    /**
     * Android accepts posts to a blocked app or channel without an error and simply doesn't show them. If the user
     * turned NotiSift's Important notifications off, originals must stay where they are.
     */
    fun canShow(): Boolean = manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    /** Shows a logged notification again ("Show in drawer"). */
    suspend fun restore(entry: Entry) = io {
        pacer.run { manager.notify(tag(entry.key), 0, fromLog(entry)) }
        afterChange()
    }

    suspend fun remove(key: String) = io {
        pacer.run { manager.cancel(tag(key), 0) }
        ensureSummary()
    }

    /** Takes every Important row out of the drawer (history deleted). */
    suspend fun removeAll() = io {
        activeTags().filter { it.startsWith(PREFIX) }.forEach { t -> pacer.run { manager.cancel(t, 0) } }
        afterChange()
    }

    /** Android drops an app's notifications when it is updated; the originals are gone, so rebuild from the log. */
    suspend fun rebuild() = io {
        val active = activeTags()
        // A previous build showed one row per app ("m:<package>"); those become individual rows again.
        active.filter { it.startsWith(PREFIX) && '|' !in it }.forEach { t -> pacer.run { manager.cancel(t, 0) } }
        app.dao.shownImportantSince(since()).distinctBy { it.key }.take(MAX_ROWS)
            .filter { tag(it.key) !in active }
            .forEach { entry ->
                // A live copy (with the original's buttons and link) may have arrived meanwhile; never replace it.
                if (tag(entry.key) !in activeTags()) pacer.run { manager.notify(tag(entry.key), 0, fromLog(entry)) }
            }
        afterChange()
    }

    /**
     * The original app's own tap action (e.g. the exact WhatsApp chat), read back from the row showing it. Android
     * keeps rows across NotiSift restarts, so this works after the in-memory copy is gone. Rows rebuilt from the
     * log carry NotiSift's own fallback, which doesn't count.
     */
    suspend fun originalIntent(key: String): PendingIntent? = io {
        runCatching {
        manager.activeNotifications.firstOrNull { it.tag == tag(key) }?.notification?.contentIntent
                ?.takeIf { it.creatorPackage != context.packageName }
        }.getOrNull()
    }

    /** The user swiped or tapped a row; refresh the header's count. */
    suspend fun onRowRemoved() = io { ensureSummary() }

    /** Important notifications not yet tapped or swiped (the oldest may be folded into the app when over budget). */
    suspend fun count(): Int = app.dao.shownImportantSince(since()).distinctBy { it.key }.size

    private suspend fun afterChange() {
        enforceBudget()
        ensureSummary()
    }

    /** Folds the oldest rows away once there are more than [MAX_ROWS]; they stay listed in NotiSift. */
    private suspend fun enforceBudget() {
        val rows = runCatching { manager.activeNotifications.filter { keyOf(it.tag) != null } }.getOrDefault(emptyList())
        rows.sortedByDescending { it.notification.`when` }.drop(MAX_ROWS).forEach { old -> pacer.run { manager.cancel(old.tag, 0) } }
    }

    private fun fromOriginal(sbn: StatusBarNotification, appLabel: String): Notification {
        val original = sbn.notification
        val builder = runCatching { Notification.Builder.recoverBuilder(context, original) }.getOrNull()
            ?: Notification.Builder(context, CHANNEL)
                .setContentTitle(original.extras.getCharSequence(Notification.EXTRA_TITLE))
                .setContentText(original.extras.getCharSequence(Notification.EXTRA_TEXT))
                .setContentIntent(original.contentIntent)
                .also { b -> original.actions?.forEach(b::addAction) }
        // recoverBuilder works in the other app's context, so icons must be built against NotiSift's own context.
        builder.setChannelId(CHANNEL)
            .setSmallIcon(Icon.createWithResource(context, R.drawable.ic_sift))
            .setSubText(appLabel)
            .setGroup(GROUP)
            .setGroupSummary(false)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            // Rows drop out of the header's count after a day; the row itself goes then too.
            .setTimeoutAfter(SiftApp.DAY_MS)
            // These point at the other app's shortcuts and bubbles, which NotiSift can't own.
            .setShortcutId(null)
            .setSortKey(newestFirst(original.`when`.takeIf { it > 0 } ?: sbn.postTime))
        if (Build.VERSION.SDK_INT >= 29) builder.setLocusId(null).setBubbleMetadata(null)
        // A resource icon would be looked up in NotiSift's package and fail to draw; bitmaps travel fine.
        // Before Android 9 an icon's type can't be read, so always use the app icon there.
        val large = original.getLargeIcon()
        if (large == null || Build.VERSION.SDK_INT < 28 || large.type == Icon.TYPE_RESOURCE) builder.setLargeIcon(appIcon(sbn.packageName))
        return builder.build()
    }

    private fun fromLog(entry: Entry): Notification {
        // Original tap targets live in memory only; after a restart a tap opens the app instead.
        val intent = SiftListener.contentIntent(entry.key)
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(Icon.createWithResource(context, R.drawable.ic_sift))
            .setContentTitle(entry.title.ifBlank { entry.appLabel })
            .setContentText(entry.text)
            .setSubText(entry.appLabel)
            .setStyle(Notification.BigTextStyle().bigText(entry.text))
            .setWhen(entry.postedAt)
            .setSortKey(newestFirst(entry.postedAt))
            .setShowWhen(true)
            .setTimeoutAfter((entry.postedAt + SiftApp.DAY_MS - System.currentTimeMillis()).coerceAtLeast(60_000))
            .setGroup(GROUP)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(intent ?: launchIntent(entry.packageName) ?: openNotiSift())
            .setLargeIcon(appIcon(entry.packageName))
            .build()
    }

    /**
     * The stack's header. Without its own text, Samsung's drawer shows the newest row instead and the stack
     * doesn't read as "Important", so it always carries a title and the list of apps. Re-posted on every change.
     */
    private suspend fun ensureSummary() {
        val shown = app.dao.shownImportantSince(since()).distinctBy { it.key }
        if (shown.isEmpty()) {
            pacer.run { manager.cancel(SUMMARY_TAG, SUMMARY_ID) }
            return
        }
        val byApp = shown.groupBy { it.packageName }.values.sortedByDescending { it.first().postedAt }
        val style = Notification.InboxStyle().setBigContentTitle("⭐ Important · ${shown.size}")
        byApp.take(MAX_LINES).forEach { list ->
            val e = list.first()
            style.addLine("${e.appLabel}${if (list.size > 1) " · ${list.size}" else ""}: ${e.title.takeIf { it.isNotBlank() && it != e.appLabel } ?: e.text}".take(90))
        }
        val folded = shown.size - MAX_ROWS
        style.setSummaryText(if (folded > 0) "+$folded older in NotiSift" else null)
        val summary = Notification.Builder(context, CHANNEL)
            .setSmallIcon(Icon.createWithResource(context, R.drawable.ic_sift))
            .setContentTitle("⭐ Important · ${shown.size}")
            .setContentText(byApp.take(4).joinToString(", ") { it.first().appLabel } + if (byApp.size > 4) " +${byApp.size - 4}" else "")
            .setStyle(style)
            .setNumber(shown.size)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setSortKey("0")
            .setContentIntent(openNotiSift())
            .build()
        pacer.notifyIfChanged(manager, SUMMARY_TAG, SUMMARY_ID, summary)
    }

    private fun activeTags(): Set<String> = runCatching { manager.activeNotifications.mapNotNull { it.tag }.toSet() }.getOrDefault(emptySet())

    private fun appIcon(pkg: String): Icon? = runCatching {
        val d = context.packageManager.getApplicationIcon(pkg)
        val bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, ICON_PX, ICON_PX)
        d.draw(Canvas(bmp))
        Icon.createWithBitmap(bmp)
    }.getOrNull()

    private fun launchIntent(pkg: String): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(pkg)?.let {
            PendingIntent.getActivity(context, pkg.hashCode(), it, PendingIntent.FLAG_IMMUTABLE)
        }

    private fun since() = System.currentTimeMillis() - SiftApp.DAY_MS

    /**
     * Callers include the UI (Open, Done, Show in drawer). Talking to the notification service and drawing app icons
     * can take a while with ~40 rows, so it never happens on the main thread.
     */
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val CHANNEL = "important"
        /** Row tags are PREFIX + the original key, so a removed row maps back to its log entry. */
        const val PREFIX = "m:"
        /** 20 rows + up to 18 group rows + running + 2 headers stays under Android's 50-per-app cap. */
        const val MAX_ROWS = 20
        private const val GROUP = "notisift.important"
        private const val SUMMARY_TAG = "digest"
        private const val SUMMARY_ID = 999
        private const val ICON_PX = 128
        private const val MAX_LINES = 6

        fun tag(key: String) = PREFIX + key

        /** Rows in a stack are ordered by sort key, so a key that shrinks over time puts the newest message first. */
        fun newestFirst(time: Long): String = "%019d".format(Long.MAX_VALUE - time)

        /** The original notification key a row stands for ("m:<key>"; keys always contain '|'). */
        fun keyOf(tag: String?): String? = tag?.takeIf { it.startsWith(PREFIX) && '|' in it }?.removePrefix(PREFIX)
    }
}
