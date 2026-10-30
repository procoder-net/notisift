package io.github.notisift.service

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import io.github.notisift.BuildConfig
import io.github.notisift.MainActivity
import io.github.notisift.R
import io.github.notisift.SiftApp
import io.github.notisift.data.Entry
import io.github.notisift.engine.Category
import io.github.notisift.engine.Heuristics
import io.github.notisift.engine.LaterSchedule
import io.github.notisift.llm.GroupSummaries
import io.github.notisift.engine.NotificationInput
import io.github.notisift.engine.Tier
import io.github.notisift.ui.DaySection
import io.github.notisift.ui.GroupRow
import io.github.notisift.ui.topGroups
import io.github.notisift.ui.drawerDays
import io.github.notisift.ui.emoji
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * NotiSift's drawer, which replaces everything else in it: a "⭐ Important" stack (copies of the important
 * originals, with their buttons) on top and silent per-group "waiting" stacks below.
 */
class DigestNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val app get() = context.applicationContext as SiftApp
    private var pending: Job? = null
    private val pacer = Pacer()
    val important = ImportantStack(context, manager, pacer) { open(Tier.LATER, null) }
    private val summaries = GroupSummaries({ request -> app.summaryModel()?.completeJson(request) })

    init {
        // Older builds had per-tier digest and junk channels; one quiet line replaces them.
        listOf("digest", "junk").forEach { manager.deleteNotificationChannel(it) }
        // Older builds posted per-tier digests under these ids; cancel once here, not on every refresh (rate limits).
        Tier.entries.forEach { manager.cancel(TAG, 2000 + it.ordinal) }
        Category.entries.forEach { manager.cancel(TAG, it.ordinal) }
        manager.createNotificationChannel(
            NotificationChannel(DIGEST_CHANNEL, "Waiting groups", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Silent groups of notifications waiting in NotiSift"
                setShowBadge(false)
            },
        )
        manager.deleteNotificationChannel("restored")
        // The original already rang when it arrived, so NotiSift's copy never makes a second sound.
        manager.createNotificationChannel(
            NotificationChannel(ImportantStack.CHANNEL, "Important", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Your important notifications, gathered at the top of the drawer"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    /** Coalesces bursts so a dozen promos produce one update, not twelve. */
    @Synchronized
    fun requestRefresh() {
        pending?.cancel()
        pending = app.scope.launch {
            delay(1500)
            try {
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A transient binder or database error must not take the listener down with it.
                Log.w("NotiSift", "Drawer refresh failed", e)
            }
        }
    }

    /**
     * The drawer view of NotiSift: one collapsed stack with a row per group ("🛍️ Promotions · 23") listing its
     * newest items. Tap a row to open that group; Clear empties it. Junk is never announced.
     */
    suspend fun refresh() = refreshLock.withLock { refreshNow() }

    /** Refreshes come from new notifications, buttons and rebuilds; one at a time, so an old snapshot never wins. */
    private val refreshLock = Mutex()

    private suspend fun refreshNow() {
        val now = System.currentTimeMillis()
        val byDay = app.settings.value?.groupByDay == true
        // By day looks back a week so older items keep a row; by group shows the last day.
        val since = now - if (byDay) DAY_LOOKBACK * SiftApp.DAY_MS else SiftApp.DAY_MS
        val settings = app.settings.value
        // "Later at set times": what arrived after the latest delivery time waits for the next one.
        val cutoff = LaterSchedule.lastSlot(now, LaterSchedule.parse(settings?.laterTimes.orEmpty()))
        // Settings not loaded yet (process start): leave any armed alarm alone rather than cancel it.
        if (settings != null) LaterAlarm.schedule(context, LaterSchedule.nextSlot(now, LaterSchedule.parse(settings.laterTimes)))
        val later = regroup(app.dao.removedEntriesSince(since).filter { Tier.of(it.action) == Tier.LATER && (cutoff == null || it.postedAt <= cutoff) })
        summarizeLater(later, byDay)
        val groups = if (byDay) emptyList() else later.groupBy { Category.fromId(it.category) }.entries.sortedByDescending { it.value.size }
        val days = if (byDay) drawerDays(later, Entry::postedAt, now, MAX_DAY_STACKS) else emptyList()
        val stacks = days.map { day -> day to topGroups(day.items, MAX_DAY_GROUPS) { Category.fromId(it.category) } }
        if (BuildConfig.DEBUG) Log.d("NotiSift", "refresh: ${later.size} waiting in ${groups.size} groups, ${days.size} days")
        val shown = groups.map { groupId(it.key) }.toSet() + stacks.flatMapIndexed { slot, (_, rows) ->
            listOf(DAY_BASE + slot) + rows.indices.map { dayRowId(slot, it) }
        }
        val active = runCatching { manager.activeNotifications.filter { it.tag == TAG }.map { it.id }.toSet() }.getOrDefault(emptySet())
        // Only cancel what is actually showing: every call counts against Android's rate limit.
        allIds()
            .filter { it !in shown && it in active }
            .forEach { pacer.run { manager.cancel(TAG, it) } }
        dayIds = stacks.flatMapIndexed { slot, (day, rows) ->
            listOf(DAY_BASE + slot to day.items.map { it.id }) + rows.mapIndexed { i, row -> dayRowId(slot, i) to row.items.map { it.id } }
        }.toMap()
        postRunning(RUNNING_ID in active)
        if (later.isEmpty()) {
            if (SUMMARY_ID in active) pacer.run { manager.cancel(TAG, SUMMARY_ID) }
            return
        }
        groups.forEachIndexed { rank, (category, entries) -> postGroup(category, entries, rank) }
        if (byDay) {
            // Each day is its own stack, so the drawer goes Day → group → newest notifications. No overall summary.
            stacks.forEachIndexed { slot, (day, rows) -> postDayStack(day, rows, slot) }
            if (SUMMARY_ID in active) pacer.run { manager.cancel(TAG, SUMMARY_ID) }
            return
        }
        val lines = groups.map { "${it.key.emoji()} ${it.key.label} · ${it.value.size}" }
        val summary = Notification.Builder(context, DIGEST_CHANNEL)
            .setSmallIcon(R.drawable.ic_sift)
            .setContentTitle("${later.size} waiting in NotiSift")
            .setContentText(groups.joinToString(" · ") { "${it.key.emoji()} ${it.value.size}" })
            .setStyle(Notification.InboxStyle().also { st -> lines.take(6).forEach(st::addLine) })
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setWhen(bottomWhen())
            .setContentIntent(open(Tier.LATER, null))
            .build()
        pacer.notifyIfChanged(manager, TAG, SUMMARY_ID, summary)
    }

    private suspend fun postGroup(category: Category, entries: List<Entry>, rank: Int) {
        val apps = entries.map { it.appLabel }.distinct()
        val ai = aiLine(category, entries)
        val style = Notification.InboxStyle().setBigContentTitle("${category.emoji()} ${category.label} · ${entries.size}")
        ai?.let { style.addLine("✨ $it") }
        entries.take(5).forEach { e -> style.addLine("${e.appLabel}: ${e.title.takeIf { it.isNotBlank() && it != e.appLabel } ?: e.text}".take(90)) }
        if (entries.size > 5) style.setSummaryText("+${entries.size - 5} more")
        val n = Notification.Builder(context, DIGEST_CHANNEL)
            .setSmallIcon(R.drawable.ic_sift)
            .setContentTitle("${category.emoji()} ${category.label} · ${entries.size}")
            .setContentText(ai?.let { "✨ $it" } ?: (apps.take(3).joinToString(", ") + if (apps.size > 3) " +${apps.size - 3}" else ""))
            .setStyle(style)
            .setGroup(GROUP)
            .setSortKey("%02d".format(rank))
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setWhen(bottomWhen(slot = rank + 1))
            .setContentIntent(open(Tier.LATER, category))
            .addAction(Notification.Action.Builder(null, "Clear", clearIntent(category)).build())
            .build()
        pacer.notifyIfChanged(manager, TAG, groupId(category), n)
    }

    /**
     * One stack per day ("🕓 Today · 239"), its own drawer group, with a row per group inside ("💬 People & messages
     * · 107"). Expanding a row shows that group's newest notifications, so the drawer goes three levels deep.
     */
    private suspend fun postDayStack(day: DaySection<Entry>, rows: List<GroupRow<Category, Entry>>, slot: Int) {
        val stack = "$GROUP.day$slot"
        val title = "${dayEmoji(day)} ${day.title} · ${day.items.size}"
        // Today's stack sits above Yesterday's: one UI orders stacks and their rows by `when`.
        val base = slot * (MAX_DAY_GROUPS + 2)
        rows.forEachIndexed { i, row ->
            val category = row.key
            val rowTitle = if (category != null) "${category.emoji()} ${category.label} · ${row.items.size}"
            else "➕ More groups · ${row.items.size}"
            val apps = row.items.map { it.appLabel }.distinct()
            val ai = category?.let { aiLine(it, row.items, day.title) }
            val text = ai?.let { "✨ $it" } ?: if (category != null) apps.take(3).joinToString(", ") + if (apps.size > 3) " +${apps.size - 3}" else ""
            else row.groups.joinToString(" · ") { (c, list) -> "${c.emoji()} ${list.size}" }
            val style = Notification.InboxStyle().setBigContentTitle("$rowTitle · ${day.title}")
            ai?.let { style.addLine("✨ $it") }
            row.items.take(ROW_LINES).forEach { e -> style.addLine(lineFor(e, showGroup = category == null)) }
            if (row.items.size > ROW_LINES) style.addLine("+${row.items.size - ROW_LINES} more · tap to see all")
            val n = Notification.Builder(context, DIGEST_CHANNEL)
                .setSmallIcon(R.drawable.ic_sift)
                .setContentTitle(rowTitle)
                .setContentText(text)
                .setStyle(style)
                // An open stack's header only says "NotiSift", so every row names its day beside its title.
                .setSubText(day.title)
                .setGroup(stack)
                .setSortKey("%02d".format(i))
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setWhen(bottomWhen(slot = base + i + 1))
                .setContentIntent(open(Tier.LATER, category))
                .addAction(Notification.Action.Builder(null, "Clear", clearIds(dayRowId(slot, i), row.items)).build())
                .build()
            pacer.notifyIfChanged(manager, TAG, dayRowId(slot, i), n)
        }
        val summary = Notification.Builder(context, DIGEST_CHANNEL)
            .setSmallIcon(R.drawable.ic_sift)
            .setContentTitle(title)
            .setContentText(rows.flatMap { it.groups }.take(5).joinToString(" · ") { (c, list) -> "${c.emoji()} ${list.size}" })
            .setStyle(Notification.InboxStyle().setBigContentTitle(title).also { st ->
                rows.forEach { r -> st.addLine(r.key?.let { "${it.emoji()} ${it.label} · ${r.items.size}" } ?: "➕ More groups · ${r.items.size}") }
            })
            // One UI labels a folded stack with this, so it carries the count, not just the day.
            .setSubText(title)
            .setGroup(stack)
            .setGroupSummary(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setWhen(bottomWhen(slot = base))
            .setContentIntent(open(Tier.LATER, null))
            .addAction(Notification.Action.Builder(null, "Clear all", clearIds(DAY_BASE + slot, day.items)).build())
            .build()
        pacer.notifyIfChanged(manager, TAG, DAY_BASE + slot, summary)
    }

    /** Groups whose content is never sent for a summary: codes and money stay on the phone. */
    private val keptOnPhone = setOf(Category.SECURITY, Category.FINANCE)

    private fun summaryKey(category: Category, day: String?) = "${day.orEmpty()}|${category.id}"

    private fun aiLine(category: Category, entries: List<Entry>, day: String? = null): String? =
        if (app.settings.value?.aiSummaries == true && category !in keptOnPhone && entries.size >= GroupSummaries.MIN_ITEMS) summaries.cached(summaryKey(category, day), entries) else null

    /** Asks for fresh lines in the background; the drawer refreshes again when one arrives. */
    private fun summarizeLater(later: List<Entry>, byDay: Boolean) {
        if (app.summaryModel() == null) return
        val now = System.currentTimeMillis()
        val groups = if (byDay) drawerDays(later, Entry::postedAt, now, MAX_DAY_STACKS).flatMap { day ->
            day.items.groupBy { Category.fromId(it.category) }.map { (c, list) -> Triple(summaryKey(c, day.title), c, list) }
        } else later.groupBy { Category.fromId(it.category) }.map { (c, list) -> Triple(summaryKey(c, null), c, list) }
        val stale = groups.filter { (key, c, list) -> c !in keptOnPhone && summaries.isStale(key, list) }
        if (stale.isEmpty()) return
        app.scope.launch {
            val fresh = stale.map { (key, c, list) -> summaries.summarize(key, c.label, list) }
            if (fresh.any { it }) requestRefresh()
        }
    }

    private fun lineFor(e: Entry, showGroup: Boolean) =
        "${if (showGroup) Category.fromId(e.category).emoji() + " " else ""}${e.appLabel}: ${e.title.takeIf { it.isNotBlank() && it != e.appLabel } ?: e.text}".take(90)

    private fun clearIds(code: Int, entries: List<Entry>): PendingIntent = PendingIntent.getBroadcast(
        context, code,
        Intent(context, ClearGroupReceiver::class.java).putExtra(ClearGroupReceiver.EXTRA_IDS, entries.map { it.id }.toLongArray()),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun dayRowId(slot: Int, row: Int) = DAY_ROW_BASE + slot * 10 + row

    /** Every waiting-row id NotiSift may have posted, in either layout. */
    private fun allIds() = Category.entries.map(::groupId) + (0 until MAX_DAY_STACKS).flatMap { slot ->
        listOf(DAY_BASE + slot) + (0..MAX_DAY_GROUPS).map { dayRowId(slot, it) }
    }

    private fun dayEmoji(day: DaySection<Entry>) = if (day.title == "Today") "🕓" else "📅"

    /** The entries behind each day row, so swiping one away marks exactly those done. */
    @Volatile private var dayIds: Map<Int, List<Long>> = emptyMap()

    private fun groupId(category: Category) = 3000 + category.ordinal

    /** False when Android hides NotiSift's waiting rows (notifications or the "waiting" channel turned off). */
    fun canShowWaiting(): Boolean = manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(DIGEST_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    /** Re-files waiting items with today's knowledge of each app (see [Heuristics.regroup]) and saves any change. */
    private suspend fun regroup(entries: List<Entry>): List<Entry> {
        val types = entries.map { it.packageName }.distinct().associateWith { app.engineStore.appCategory(it) }
        val fixed = entries.map { e ->
            val input = NotificationInput(e.key, e.packageName, e.appLabel, e.channelId, title = e.title, text = e.text)
            val c = Heuristics.regroup(Category.fromId(e.category), input, types[e.packageName].orEmpty())
            if (c.id == e.category) e else e.copy(category = c.id)
        }
        fixed.filterIndexed { i, e -> e.category != entries[i].category }
            .groupBy { it.category }
            .forEach { (category, moved) -> app.dao.setCategory(moved.map { it.id }, category) }
        return fixed
    }

    /**
     * Drawers that sort by time (One UI's default) rank a row by its `when`. Waiting and running rows hide their
     * time, so dating them before anything in the Important stack (which only holds the last day) keeps them below
     * it however often they refresh. Not much older: Android silently drops notifications dated far in the past.
     * One UI also orders a stack's rows by `when`, ignoring sort keys, and shows only the first 8 when expanded,
     * so each [slot] gets a fixed time a minute apart (Running first, then the biggest groups). The base moves
     * once a day, not on every refresh, so rows reposted at different times keep their order.
     */
    private fun bottomWhen(slot: Int = 0): Long {
        val base = (System.currentTimeMillis() - 2 * SiftApp.DAY_MS) / SiftApp.DAY_MS * SiftApp.DAY_MS
        return base - slot * 60_000L
    }

    /** One row for snoozed "running in background" notifications (Tesla, Tasker…). "Show" brings them back. */
    private suspend fun postRunning(showing: Boolean) {
        val labels = SiftListener.running.values.distinct()
        if (labels.isEmpty()) {
            if (showing) pacer.run { manager.cancel(TAG, RUNNING_ID) }
            return
        }
        val show = PendingIntent.getBroadcast(
            context, RUNNING_ID,
            Intent(context, ClearGroupReceiver::class.java).putExtra(ClearGroupReceiver.EXTRA_SHOW_RUNNING, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, DIGEST_CHANNEL)
            .setSmallIcon(R.drawable.ic_sift)
            .setContentTitle("📌 Running in background · ${labels.size}")
            .setContentText(labels.joinToString(", "))
            .setGroup(GROUP)
            .setSortKey("!")
            // Swiping it away would leave the snoozed notifications with no way back; "Show" is the way out.
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setWhen(bottomWhen())
            .addAction(Notification.Action.Builder(null, "Show", show).build())
            .build()
        pacer.notifyIfChanged(manager, TAG, RUNNING_ID, n)
    }

    fun forget(tag: String?, id: Int) = pacer.forget(tag, id)

    /** Swiping a waiting group means "seen": mark it done so it doesn't come back on the next refresh. */
    suspend fun onUserRemoved(tag: String?, id: Int) {
        if (tag != TAG) return
        val category = Category.entries.firstOrNull { groupId(it) == id }
        when {
            category != null -> app.dao.dismissCategory(category.id, listOf(io.github.notisift.engine.Action.BUNDLE.id))
            id in dayIds -> app.dao.setDismissed(dayIds.getValue(id), true)
            id == SUMMARY_ID -> app.dao.dismissAllLater()
            // Cleared along with everything else: bring it back, the notifications it stands for are still hidden.
            id == RUNNING_ID -> Unit
            else -> return
        }
        requestRefresh()
    }

    private fun clearIntent(category: Category): PendingIntent = PendingIntent.getBroadcast(
        context, 3000 + category.ordinal,
        Intent(context, ClearGroupReceiver::class.java).putExtra(ClearGroupReceiver.EXTRA_CATEGORY, category.id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun open(tier: Tier, category: Category?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_TIER, tier.id)
            .putExtra(MainActivity.EXTRA_CATEGORY, category?.id)
        val code = tier.ordinal * 100 + (category?.ordinal?.plus(1) ?: 0)
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * Android drops an app's notifications when it is updated or force-stopped. The originals are already gone,
     * so rebuild NotiSift's drawer from the log: the Important stack, then the waiting groups.
     */
    suspend fun rebuild() {
        important.rebuild()
        refresh()
    }

    private fun launchIntent(pkg: String): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(pkg)?.let {
            PendingIntent.getActivity(context, pkg.hashCode(), it, PendingIntent.FLAG_IMMUTABLE)
        }

    /** Opens what the original notification would have opened, falling back to the app itself. */
    suspend fun openOriginal(entry: Entry): Boolean {
        (SiftListener.contentIntent(entry.key) ?: important.originalIntent(entry.key))?.let { pi ->
            val sent = runCatching {
                // The user tapped Open inside NotiSift, so NotiSift is on screen: lend the other app that visibility
                // to open its screen, and nothing more (Android 16 can limit it to "while visible").
                val options = when {
                    Build.VERSION.SDK_INT >= 36 -> ActivityOptions.makeBasic().apply {
                        setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
                    }.toBundle()
                    Build.VERSION.SDK_INT >= 34 -> ActivityOptions.makeBasic().apply {
                        @Suppress("DEPRECATION")
                        setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    }.toBundle()
                    else -> null
                }
                pi.send(context, 0, null, null, null, null, options)
            }.isSuccess
            if (sent) return true
        }
        val launch = context.packageManager.getLaunchIntentForPackage(entry.packageName) ?: return false
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun clearAll() {
        allIds().forEach { manager.cancel(TAG, it) }
        manager.cancel(TAG, SUMMARY_ID)
    }

    companion object {
        const val DIGEST_CHANNEL = "waiting"
        private const val GROUP = "notisift.waiting"
        private const val TAG = "digest"
        private const val SUMMARY_ID = 1000
        private const val RUNNING_ID = 3999
        private const val DAY_BASE = 3100
        private const val DAY_ROW_BASE = 3200
        /**
         * Today, Yesterday and Earlier, each with its biggest groups plus one "More groups" row. Android keeps at most
         * ~50 notifications per app and the Important stack uses up to 21, so 3 × 7 rows leaves headroom.
         */
        private const val MAX_DAY_STACKS = 3
        private const val MAX_DAY_GROUPS = 5
        private const val DAY_LOOKBACK = 7L
        /** Notifications listed when a group row is expanded. */
        private const val ROW_LINES = 5
    }
}
