package io.github.notisift.service

import android.app.Notification
import android.app.PendingIntent
import android.os.Build
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.notisift.BuildConfig
import io.github.notisift.SiftApp
import io.github.notisift.data.Entry
import io.github.notisift.engine.Action
import io.github.notisift.engine.Category
import io.github.notisift.engine.LocalRules
import io.github.notisift.engine.NotificationInput
import io.github.notisift.engine.Source
import io.github.notisift.engine.Verdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

class SiftListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val permits = Semaphore(3)
    private val lastHash = ConcurrentHashMap<String, String>()
    /** One decision per notification key at a time, so an app's update never races its predecessor's cancel. */
    private val keyLocks = Array(32) { Mutex() }
    /** Keys NotiSift itself cancelled recently, with what they showed, to recognise an update caught by that cancel. */
    private val cancelledByUs = ConcurrentHashMap<String, Pair<Long, String>>()
    private val labels = ConcurrentHashMap<String, String>()
    private val app get() = application as SiftApp

    /** Snoozed "running" notifications survive NotiSift restarts and updates, so the Running row does too. */
    private val runningPrefs by lazy { getSharedPreferences("running", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        runningPrefs.all.forEach { (key, label) -> (label as? String)?.let { running[key] = it } }
    }

    private fun saveRunning() {
        runningPrefs.edit().clear().apply { running.forEach { (k, v) -> putString(k, v) } }.apply()
    }

    override fun onListenerConnected() {
        instance = this
        _connected.value = true
        scope.launch {
            safely { app.digest.rebuild() }
            safely { tuckAwayRunning() }
        }
    }

    /**
     * Ongoing notifications (Tesla, Tasker) are only seen when they are posted. After NotiSift restarts or is
     * updated, the ones already in the drawer would stay there until they change, so check them once now.
     */
    private suspend fun tuckAwayRunning() {
        // Apps can cancel a notification while it is snoozed, without telling listeners: drop those from the row.
        runCatching { snoozedNotifications }.getOrNull()?.map { it.key }?.toSet()?.let { snoozed ->
            if (running.keys.retainAll(snoozed)) saveRunning()
        }
        val settings = app.awaitSettings()
        if (!settings.replaceShade || settings.observe || settings.stopped) return
        runCatching { activeNotifications }.getOrNull().orEmpty()
            .filter { it.packageName != packageName && ownProfile(it) && isRunning(it) && it.key !in running }
            .forEach { sbn -> permits.withPermit { safely { handleLocked(sbn, toInput(sbn), cleaning = true) } } }
    }

    override fun onListenerDisconnected() {
        instance = null
        _connected.value = false
        // Some OEM ROMs unbind listeners under memory pressure; ask to come back.
        runCatching { requestRebind(ComponentName(this, SiftListener::class.java)) }
    }

    override fun onDestroy() {
        instance = null
        _connected.value = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isNotiSift(sbn.packageName) || !ownProfile(sbn)) return
        val input = toInput(sbn)
        // Apps re-post the same key for progress/updates; only new content is worth a decision.
        if (lastHash.put(sbn.key, input.contentKey) == input.contentKey) return
        scope.launch { permits.withPermit { safely { handleLocked(sbn, input, cleaning = false) } } }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        lastHash.remove(sbn.key)
        // Whatever removed one of NotiSift's rows, the next refresh must be allowed to post it again.
        if (sbn.packageName == packageName) app.digest.forget(sbn.tag, sbn.id)
        // Tapping an original (still in the drawer) means it was read: teaches "you never open this chat".
        if (sbn.packageName != packageName && reason == REASON_CLICK) {
            scope.launch { safely { app.dao.markOpenedKey(sbn.key, System.currentTimeMillis() - SiftApp.DAY_MS) } }
        }
        if (sbn.packageName != packageName || reason !in userRemovals) return
        // The user swiped or tapped one of NotiSift's rows: it's handled, so don't bring it back on rebuild.
        val key = ImportantStack.keyOf(sbn.tag)
        if (key != null) scope.launch { safely { app.digest.important.onRowRemoved(key, opened = reason == REASON_CLICK) } }
        else scope.launch { safely { app.digest.onUserRemoved(sbn.tag, sbn.id) } }
    }

    private val userRemovals = setOf(REASON_CLICK, REASON_CANCEL, REASON_CANCEL_ALL, REASON_GROUP_SUMMARY_CANCELED)

    private val liveCategories = setOf(
        Notification.CATEGORY_CALL, Notification.CATEGORY_ALARM, Notification.CATEGORY_NAVIGATION, Notification.CATEGORY_TRANSPORT,
        Notification.CATEGORY_STOPWATCH, Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE, Notification.CATEGORY_SYSTEM,
        "location_sharing",
    )

    /** Notifications the user still sees in the shade, excluding NotiSift's own and group headers. */
    fun shadeCount(): Int = runCatching { activeNotifications }.getOrNull().orEmpty()
        .count { it.packageName != packageName && it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0 }

    /**
     * What NotiSift would do with everything in the drawer right now, without moving or logging anything: the
     * first-run "here's your drawer, sorted" preview. Live and ongoing notifications are left out, as sorting would.
     */
    suspend fun preview(limit: Int = 40): List<Pair<NotificationInput, Verdict>> = withContext(Dispatchers.Default) {
        val settings = app.awaitSettings()
        runCatching { activeNotifications }.getOrNull().orEmpty()
            .filter { it.packageName != packageName && !isNotiSift(it.packageName) && ownProfile(it) && it.isClearable && !isRunning(it) }
            .map(::toInput).filter { !it.isGroupSummary && !it.isUnreadable }
            .distinctBy { it.contentKey }.take(limit)
            .map { input -> async { permits.withPermit { safely { app.engine.decide(input, settings.engine, checkDuplicates = false) }?.let { input to it } } } }
            .awaitAll().filterNotNull()
    }

    /** Applies current rules to everything already in the shade, even in observe mode. */
    suspend fun cleanNow(): Int = withContext(Dispatchers.Default) {
        val active = runCatching { activeNotifications }.getOrNull().orEmpty().filter { it.packageName != packageName && ownProfile(it) }
        active.map { sbn ->
            async { permits.withPermit { safely { handleLocked(sbn, toInput(sbn), cleaning = true) } } }
        }.awaitAll().count { it != null && it.action != Action.KEEP }
    }

    private suspend fun handleLocked(sbn: StatusBarNotification, input: NotificationInput, cleaning: Boolean): Verdict? =
        keyLocks[(sbn.key.hashCode() and Int.MAX_VALUE) % keyLocks.size].withLock { handle(sbn, input, cleaning) }

    /**
     * An app updated a notification just as NotiSift cancelled the previous version, so the cancel took the update
     * with it. The update is still in hand here, so it is handled as already removed instead of being lost.
     */
    private fun caughtByOurCancel(key: String, input: NotificationInput): Boolean {
        val (at, content) = cancelledByUs[key] ?: return false
        return System.currentTimeMillis() - at < CANCEL_RACE_MS && content != input.contentKey
    }

    private suspend fun handle(sbn: StatusBarNotification, input: NotificationInput, cleaning: Boolean): Verdict? {
        val settings = app.awaitSettings()
        if (settings.stopped) return null
        // Removed notifications live in NotiSift itself, so they stay reachable even if its own notifications are off.
        val verdict = app.engine.decide(input, settings.engine, checkDuplicates = !cleaning)
        if (BuildConfig.DEBUG) Log.d("NotiSift", "${input.packageName}: ${verdict?.action ?: "unmanaged"} ${verdict?.source ?: ""} ${verdict?.reason ?: ""}")
        if (verdict == null) return null
        // Important notifications move too: NotiSift re-shows them in its own Important stack, so the drawer
        // holds only NotiSift. Live ones (calls, alarms, media, navigation, ongoing) are left exactly as they are.
        val mirror = verdict.action == Action.KEEP && settings.replaceShade && !settings.observe && canMirror(sbn, input) &&
            app.digest.important.canShow()
        val wantsRemoval = mirror || (verdict.action != Action.KEEP && (cleaning || !settings.observe))
        if (tuckAway(sbn, input, verdict, settings.replaceShade && !settings.observe)) return verdict
        if (cleaning && !wantsRemoval) return verdict
        // The decision may have taken seconds; never cancel if the app has since replaced this notification.
        val alreadyGone = wantsRemoval && !isStillShowing(sbn, input) && caughtByOurCancel(sbn.key, input)
        if (wantsRemoval && !alreadyGone && !isStillShowing(sbn, input)) return verdict
        // Ongoing notifications (car status, timers, downloads) re-post constantly; logging them is pure noise.
        if (!wantsRemoval && (input.isOngoing || !input.isClearable)) return verdict
        val redact = verdict.source == Source.OTP || verdict.source == Source.PROTECTED
        // Log first so a removed notification can always be found again in the app.
        val id = app.dao.insert(
            Entry(
                key = sbn.key, packageName = input.packageName, appLabel = input.appLabel, channelId = input.channelId,
                title = (if (redact) LocalRules.maskDigits(input.title) else input.title).take(500),
                text = if (redact) "" else input.text.take(2000), postedAt = input.postedAt,
                category = verdict.category.id, action = verdict.action.id, source = verdict.source.name,
                confidence = verdict.confidence, reason = verdict.reason, templateKey = input.templateKey, applied = wantsRemoval,
            ),
        )
        if (!wantsRemoval) return verdict
        // Show the copy before removing the original, so a failure leaves the original in place.
        if (mirror && !app.digest.important.show(sbn, input.appLabel, input.templateKey, chat = verdict.category == Category.PERSONAL)) {
            app.dao.setApplied(id, false)
            return verdict
        }
        val removed = alreadyGone || (isStillShowing(sbn, input) && runCatching {
            val now = System.currentTimeMillis()
            cancelledByUs.entries.removeIf { now - it.value.first > CANCEL_RACE_MS }
            cancelledByUs[sbn.key] = now to input.contentKey
            cancelNotification(sbn.key)
        }.isSuccess)
        if (!removed) {
            app.dao.setApplied(id, false)
            if (mirror) app.digest.important.remove(sbn.key)
            return verdict.copy(action = Action.KEEP)
        }
        // Forget it now rather than in onNotificationRemoved: apps that immediately re-post the same
        // content (Grok, Gmail sync) must be decided again, not skipped as an unchanged update.
        lastHash.remove(sbn.key)
        sbn.notification.contentIntent?.let { remember(sbn.key, it) }
        if (settings.digest && !mirror) app.digest.requestRefresh()
        return verdict
    }

    /**
     * "Running in background" notifications (car status, automation apps) can't be removed, but they can be
     * snoozed. NotiSift snoozes them for an hour at a time and lists them in one "Running" row instead.
     * Anything live (calls, media, navigation, downloads, timers) is never touched.
     */
    private fun tuckAway(sbn: StatusBarNotification, input: NotificationInput, verdict: Verdict, enabled: Boolean): Boolean {
        if (!enabled || showRunning || verdict.action != Action.KEEP || !input.isOngoing || input.isGroupSummary) return false
        val n = sbn.notification
        val live = input.hasMediaSession || n.fullScreenIntent != null || input.androidCategory in liveCategories - Notification.CATEGORY_SERVICE ||
            n.extras.getInt(Notification.EXTRA_PROGRESS_MAX) > 0 || n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) ||
            n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        if (live) return false
        if (runCatching { snoozeNotification(sbn.key, RUNNING_SNOOZE_MS) }.isFailure) return false
        running[sbn.key] = input.appLabel
        saveRunning()
        app.digest.requestRefresh()
        return true
    }

    /** Ongoing, or a foreground service: Android 14+ lets those be swiped, so they lack the ongoing flag (Tesla). */
    private fun isRunning(sbn: StatusBarNotification) =
        sbn.notification.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0

    /** "Show" on the Running row: let them back into the drawer until NotiSift restarts. */
    fun showRunning() {
        showRunning = true
        val keys = running.keys.toList()
        running.clear()
        saveRunning()
        // There's no unsnooze call, but re-snoozing a snoozed notification replaces its timer: 1 ms brings it back now.
        app.scope.launch(Dispatchers.IO) {
            keys.forEach { key -> runCatching { snoozeNotification(key, 1) }.onFailure { Log.w(TAG, "Unsnooze failed", it) } }
        }
        app.digest.requestRefresh()
    }

    /** Work-profile and dual-app notifications belong to another user: NotiSift can't remove them, so it leaves them be. */
    private fun ownProfile(sbn: StatusBarNotification) = sbn.user == android.os.Process.myUserHandle()

    /**
     * Android 15+ swaps sensitive content (codes) for a placeholder before listeners like NotiSift see it. There is
     * no public flag, so the placeholder is recognised by the system's own string (plus the English wording).
     * Such notifications stay exactly as they are: a copy would show the placeholder instead of the code.
     */
    private val redactedTexts: Set<String> by lazy {
        val res = android.content.res.Resources.getSystem()
        val names = listOf("redacted_otp_notification_message", "redacted_notification_message", "redacted_otp_notification_title")
        (names.mapNotNull { n -> res.getIdentifier(n, "string", "android").takeIf { it != 0 }?.let { runCatching { res.getString(it) }.getOrNull() } } +
            "Sensitive notification content hidden").map { it.trim().lowercase() }.toSet()
    }

    private fun isSensitive(title: String, text: String): Boolean =
        Build.VERSION.SDK_INT >= 35 && (text.trim().lowercase() in redactedTexts || title.trim().lowercase() in redactedTexts)

    /** Only plain, dismissible notifications are copied; anything live must stay the app's own. */
    private fun canMirror(sbn: StatusBarNotification, input: NotificationInput): Boolean =
        input.isClearable && !input.isOngoing && !input.hasMediaSession && !input.isGroupSummary && !input.isSensitive &&
            sbn.notification.fullScreenIntent == null && input.androidCategory !in liveCategories

    private fun isStillShowing(sbn: StatusBarNotification, input: NotificationInput): Boolean {
        val current = runCatching { getActiveNotifications(arrayOf(sbn.key)) }.getOrNull()?.firstOrNull() ?: return false
        // Apps re-post identical content all the time (Grok, Telegram); the decision still applies to it.
        return toInput(current).contentKey == input.contentKey
    }

    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Notification left untouched after error", e)
        null
    }

    private fun toInput(sbn: StatusBarNotification): NotificationInput {
        val n = sbn.notification
        val x = n.extras
        val template = x.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        val lastMessage = runCatching {
            @Suppress("DEPRECATION")
            x.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull()?.let { (it as? android.os.Bundle)?.getCharSequence("text")?.toString() }
        }.getOrNull()
        val title = (x.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: x.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()
        val text = (x.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: x.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: lastMessage.orEmpty()
        return NotificationInput(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = label(sbn.packageName),
            channelId = n.channelId.orEmpty(),
            androidCategory = n.category,
            title = title,
            text = text,
            subText = x.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty(),
            isOngoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0,
            isClearable = sbn.isClearable,
            isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            isMessagingStyle = template.endsWith("MessagingStyle"),
            hasMediaSession = x.containsKey(Notification.EXTRA_MEDIA_SESSION),
            isSensitive = isSensitive(title, text),
            // EXTRA_SELF_DISPLAY_NAME is API 28+; the key string is the same on every version.
            selfName = x.getCharSequence("android.selfDisplayName")?.toString().orEmpty(),
            isPriorityConversation = isPriority(sbn.key),
            postedAt = sbn.postTime,
        )
    }

    /** Android 11+: the user starred this chat as a priority conversation in the system's settings. */
    private fun isPriority(key: String): Boolean = Build.VERSION.SDK_INT >= 30 && runCatching {
        val ranking = Ranking()
        currentRanking?.getRanking(key, ranking) == true && ranking.channel?.isImportantConversation == true
    }.getOrDefault(false)

    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    companion object {
        /** Package of every NotiSift build (release, debug and personal share it; demo adds .demo): never sort NotiSift's own rows. */
        const val BASE_PACKAGE = "io.github.notisift"
        /** Exact ids only: the test sender (io.github.notisift.testsender) is an ordinary app to sort. */
        fun isNotiSift(pkg: String) = pkg == BASE_PACKAGE || pkg == "$BASE_PACKAGE.demo"

        private const val RUNNING_SNOOZE_MS = 60 * 60 * 1000L
        private const val CANCEL_RACE_MS = 10_000L
        /** Snoozed "running" notifications by key → app label, for the drawer's Running row. */
        val running: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
        @Volatile private var showRunning = false

        private const val TAG = "NotiSift"
        private val _connected = MutableStateFlow(BuildConfig.DEMO)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        @Volatile var instance: SiftListener? = null
            private set

        private val intents = object : LinkedHashMap<String, PendingIntent>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PendingIntent>?) = size > 300
        }

        private fun remember(key: String, intent: PendingIntent) = synchronized(intents) { intents[key] = intent }

        /** The original tap action, if the process has not restarted since it was removed. */
        fun contentIntent(key: String): PendingIntent? = synchronized(intents) { intents[key] }

        fun hasAccess(context: Context): Boolean {
            if (BuildConfig.DEMO) return true
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val me = ComponentName(context, SiftListener::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
