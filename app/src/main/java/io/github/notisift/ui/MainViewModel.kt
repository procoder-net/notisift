package io.github.notisift.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.notisift.BuildConfig
import io.github.notisift.SiftApp
import io.github.notisift.data.AppSettings
import io.github.notisift.data.Entry
import io.github.notisift.data.Policy
import io.github.notisift.engine.Action
import io.github.notisift.engine.AppMode
import io.github.notisift.engine.Category
import io.github.notisift.engine.DecisionEngine
import io.github.notisift.engine.NotificationInput
import io.github.notisift.engine.Prompts
import io.github.notisift.engine.BusyChat
import io.github.notisift.engine.CategoryChoices
import io.github.notisift.engine.ChatMessage
import io.github.notisift.engine.ChatMode
import io.github.notisift.engine.ChatRules
import io.github.notisift.engine.GroupChoice
import io.github.notisift.engine.RuleSuggester
import io.github.notisift.engine.Source
import io.github.notisift.engine.RuleSuggestion
import io.github.notisift.engine.Tier
import io.github.notisift.llm.LlmClient
import io.github.notisift.llm.ProviderDetector
import io.github.notisift.scan.DeviceScanner
import io.github.notisift.scan.PolicyAdvisor
import io.github.notisift.scan.Suggestion
import io.github.notisift.service.SiftListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

data class ScanState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val suggestions: List<Suggestion> = emptyList(),
    val message: String? = null,
)

data class ConnectionState(
    val testing: Boolean = false,
    val result: String? = null,
    val ok: Boolean? = null,
    val models: List<String> = emptyList(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SiftApp
    private val dao = app.dao

    val settings: StateFlow<AppSettings?> = app.settings
    val listenerConnected: StateFlow<Boolean> = SiftListener.connected
    val entries: StateFlow<List<Entry>> = dao.entries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val policies: StateFlow<List<Policy>> = dao.policies().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /** Apps that posted in the last 2 weeks: the Apps tab's default list. */
    val activeApps: StateFlow<Set<String>> = dao.activePackages(System.currentTimeMillis() - 14 * SiftApp.DAY_MS).map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val todayTotal = dao.countSince(startOfDay()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val todayByTier = dao.countByAction(startOfDay()).map { rows -> rows.groupBy({ Tier.of(it.action) }, { it.total }).mapValues { it.value.sum() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val todayRemoved = dao.removedSince(startOfDay()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val noisiest = dao.noisiest(System.currentTimeMillis() - 7 * SiftApp.DAY_MS).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Apps the AI always treats the same way: one tap turns them into fixed, AI-free rules. */
    val ruleSuggestions: StateFlow<List<RuleSuggestion>> = combine(dao.decisionTallies(System.currentTimeMillis() - 14 * SiftApp.DAY_MS), policies) { tallies, pols ->
        RuleSuggester.suggest(tallies, pols.associate { it.packageName to AppMode.fromId(it.mode) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun acceptRule(s: RuleSuggestion) = viewModelScope.launch {
        setModeNow(s.packageName, s.appLabel, s.mode)
        _toast.value = "${s.appLabel} now always goes to ${s.tier.label}"
    }

    /** Groups relevant on this phone with their 14-day counts, busiest first. */
    val groups: StateFlow<List<Pair<Category, Int>>> = dao.countByCategory(System.currentTimeMillis() - 14 * SiftApp.DAY_MS).map { rows ->
        val counts = rows.associate { Category.fromId(it.category) to it.total }
        app.engineStore.categories().map { it to (counts[it] ?: 0) }.sortedByDescending { it.second }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setGroupChoice(category: Category, choice: GroupChoice) = updateSettings { s ->
        val current = CategoryChoices.parse(s.categoryChoices)
        s.copy(categoryChoices = CategoryChoices.format(current + (category to choice)))
    }

    private val _shadeCount = MutableStateFlow(0)
    val shadeCount = _shadeCount.asStateFlow()

    private val _scan = MutableStateFlow(ScanState())
    val scan = _scan.asStateFlow()
    private val _connection = MutableStateFlow(ConnectionState())
    val connection = _connection.asStateFlow()
    /** A message with an Undo button, shown after anything that clears items away. */
    data class UndoNotice(val message: String, val undo: suspend () -> Unit)

    private val _undo = MutableStateFlow<UndoNotice?>(null)
    val undo = _undo.asStateFlow()

    fun consumeUndo() { _undo.value = null }

    fun performUndo(notice: UndoNotice) = viewModelScope.launch { notice.undo() }

    private val _toast = MutableStateFlow<String?>(null)
    val toast = _toast.asStateFlow()

    fun hasAccess() = SiftListener.hasAccess(app)
    fun consumeToast() { _toast.value = null }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            try {
                app.settingsStore.update(transform)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _toast.value = "Couldn't save settings: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Going active also sorts what is already in the shade, so the change is visible immediately. */
    fun setActive(active: Boolean) = viewModelScope.launch {
        app.settingsStore.update { it.copy(observe = !active, stopped = false) }
        if (active) cleanNow()
    }

    val chatRules: StateFlow<Map<String, ChatMode>> = app.settings.map { ChatRules.parse(it?.chatRules.orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Group chats that put many messages on top in the last day and have no choice yet: offered, never applied silently. */
    val busyChats: StateFlow<List<BusyChat>> = combine(entries, chatRules) { list, rules ->
        val since = System.currentTimeMillis() - SiftApp.DAY_MS
        val chats = list.filter { it.action == Action.KEEP.id && it.category == Category.PERSONAL.id && it.postedAt >= since }
        ChatRules.busyGroups(chats.map { ChatMessage(it.packageName, it.appLabel, it.title, it.text) }, rules)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setChatMode(entry: Entry, mode: ChatMode?) = applyChatMode(listOf(entry.packageName to entry.title), mode)

    fun setChatModes(busy: List<BusyChat>, mode: ChatMode) = applyChatMode(busy.map { it.packageName to it.chat }, mode)

    /**
     * Saves a choice for these chats and applies it to what is already on top: muted or mentions-only chats leave
     * the ⭐ stack now, not just from the next message. One Undo puts the choice and every moved item back.
     */
    private fun applyChatMode(chats: List<Pair<String, String>>, mode: ChatMode?) = viewModelScope.launch {
        val before = app.awaitSettings()
        app.settingsStore.update { it.copy(chatRules = ChatRules.format(ChatRules.with(ChatRules.rows(it.chatRules), chats, mode))) }
        val keys = chats.map { (pkg, title) -> ChatRules.key(pkg, title) }.toSet()
        val names = before.engine.myNames
        val moved = if (mode == ChatMode.MUTE || mode == ChatMode.MENTIONS) {
            dao.keptFrom(chats.map { it.first }.distinct()).filter {
                ChatRules.key(it.packageName, it.title) in keys && (mode == ChatMode.MUTE || !ChatRules.mentions(it.text, names))
            }
        } else emptyList()
        if (moved.isNotEmpty()) {
            dao.setDecision(moved.map { it.id }, Action.BUNDLE.id, Source.CHAT.name)
            moved.filter { it.applied }.map { it.key }.distinct().forEach { app.digest.important.remove(it) }
        }
        app.digest.requestRefresh()
        refreshShadeCount()
        val what = if (chats.size == 1) ChatRules.chatName(chats.single().second) else "${chats.size} chats"
        val message = when (mode) {
            null -> "$what: back to normal"
            ChatMode.ALWAYS -> "$what: always on top"
            ChatMode.MENTIONS -> "$what: only when you're mentioned" + if (moved.isNotEmpty()) " · ${moved.size} moved to Later" else ""
            ChatMode.MUTE -> "$what: muted" + if (moved.isNotEmpty()) " · ${moved.size} moved to Later" else ""
        }
        _undo.value = UndoNotice(message) {
            app.settingsStore.update { it.copy(chatRules = before.chatRules) }
            moved.forEach { dao.restoreDecision(it.id, it.action, it.source) }
            moved.filter { it.applied }.distinctBy { it.key }.forEach { app.digest.important.restore(it) }
            app.digest.requestRefresh()
        }
    }

    /** Home and the drawer's waiting rows switch together between groups and days. */
    fun setGroupByDay(on: Boolean) = viewModelScope.launch {
        app.settingsStore.update { it.copy(groupByDay = on) }
        app.digest.requestRefresh()
    }

    fun finishTour() = viewModelScope.launch { app.settingsStore.update { it.copy(tourDone = true) } }

    /** Off: stops reading notifications altogether (no logging, no AI calls). Preview still decides and logs. */
    fun setStopped(stopped: Boolean) = viewModelScope.launch { app.settingsStore.update { it.copy(stopped = stopped) } }

    /** The cloud provider decisions go to, or null when everything is decided on this phone. */
    fun cloudProvider(): String? = app.currentModel()?.let { app.settings.value?.modelConfig?.kind?.label }

    /** False when Android would hide NotiSift's own rows, so the drawer can't show what it moved. */
    fun drawerHealthy(): Boolean = BuildConfig.DEMO || app.digest.important.canShow() && app.digest.canShowWaiting()

    /** Deletes every logged notification and NotiSift's rows in the drawer. Learned choices stay. */
    fun deleteHistory() = viewModelScope.launch {
        app.dao.clearHistory()
        app.digest.important.removeAll()
        app.digest.requestRefresh()
        _toast.value = "Notification history deleted"
    }

    fun refreshShadeCount() {
        viewModelScope.launch {
            // The demo build posts nothing to the drawer, so it counts its sample Important items instead.
            _shadeCount.value = if (BuildConfig.DEMO) dao.entries().first().count { it.action == Action.KEEP.id } else app.digest.important.count()
        }
    }

    fun cleanNow() = viewModelScope.launch {
        val listener = SiftListener.instance ?: run { _toast.value = "Allow notification access first"; return@launch }
        val moved = listener.cleanNow()
        refreshShadeCount()
        _toast.value = if (moved == 0) "Nothing new to move" else "Moved $moved here"
    }

    /** Saves only the connection fields of [draft], so other settings changed meanwhile (e.g. observe mode) survive. */
    private suspend fun saveConnection(draft: AppSettings): AppSettings {
        var saved = draft
        app.settingsStore.update { cur ->
            cur.copy(provider = draft.provider, baseUrl = draft.baseUrl, apiKey = draft.apiKey, model = draft.model, aiEnabled = draft.aiEnabled).also { saved = it }
        }
        return saved
    }

    fun testConnection(draft: AppSettings) = viewModelScope.launch {
        val s = saveConnection(draft)
        val config = s.modelConfig
        _connection.update { it.copy(testing = true, result = null, ok = null) }
        val result = runCatching {
            val client = LlmClient(config)
            val models = runCatching { client.listModels() }.getOrDefault(emptyList())
            val started = System.currentTimeMillis()
            val sample = NotificationInput("test", "com.example.shop", "Example Shop", title = "Flash sale: 70% off today only", text = "Tap to shop the deals before midnight")
            val requests = Prompts.cleanupRequests(Prompts.notificationContext(sample, "shopping"), s.rules, "")
            val verdict = DecisionEngine.toVerdict(requests, client.decideAll(requests)) ?: error("Model gave no usable answer")
            models to "${client.id}\n" +
                "Sample promo → ${verdict.action.label} / ${verdict.category.label} (${(verdict.confidence * 100).toInt()}% sure) in ${System.currentTimeMillis() - started} ms"
        }
        _connection.value = result.fold(
            onSuccess = { (models, msg) -> ConnectionState(result = msg, ok = true, models = models) },
            onFailure = { e -> ConnectionState(result = e.message ?: e.javaClass.simpleName, ok = false, models = _connection.value.models) },
        )
    }

    fun fetchModels(draft: AppSettings) = viewModelScope.launch {
        val config = saveConnection(draft).modelConfig
        val models = runCatching { LlmClient(config).listModels() }
        models.onSuccess { list -> _connection.update { it.copy(models = list) } }
            .onFailure { e -> _connection.update { it.copy(result = "Could not list models: ${e.message}", ok = false) } }
        // Auto-select a sensible model if none is configured yet.
        if (config.model.isBlank()) models.getOrNull()?.let(ProviderDetector::pickCheapModel)?.let { m -> updateSettings { it.copy(model = m) } }
    }

    fun scanDevice() = viewModelScope.launch {
        if (_scan.value.running) return@launch
        _scan.value = ScanState(running = true, message = "Reading installed apps…")
        try {
            val profiles = DeviceScanner(app, dao).scan()
            _scan.update { it.copy(total = profiles.size, message = "Asking ${if (app.currentModel() != null) "the AI" else "offline rules"} about ${profiles.size} apps…") }
            // Batches of 30 apps take longer than one notification, so the scan gets a patient client.
            val scanModel = app.currentModel()?.let { app.awaitSettings().modelConfig }?.let { cfg -> runCatching { LlmClient(cfg, LlmClient.slowHttp) }.getOrNull() }
            val advisor = PolicyAdvisor(scanModel, app.awaitSettings().rules)
            val suggestions = advisor.advise(profiles) { done, total -> _scan.update { it.copy(done = done, total = total) } }
            // Remember store category so per-notification decisions get that context too.
            val existing = dao.allPolicies().associateBy { it.packageName }
            dao.upsertPolicies(profiles.map { p ->
                val s = suggestions.first { it.packageName == p.packageName }
                (existing[p.packageName] ?: Policy(p.packageName, p.label, mode = "")).copy(
                    label = p.label, appCategory = p.appCategory,
                    suggestedMode = s.mode.id, suggestedCategory = s.category.id, suggestionReason = s.reason,
                )
            })
            val ai = suggestions.count { it.byModel }
            _scan.value = ScanState(
                done = suggestions.size, total = suggestions.size, suggestions = suggestions,
                message = "${suggestions.size} apps scanned · $ai by AI, ${suggestions.size - ai} by offline rules",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _scan.value = ScanState(message = "Scan failed: ${e.message}")
        }
    }

    /** Bulk apply never blocks or silences: those need a per-app tap, so a bad suggestion can't hide a whole app. */
    /** First-run step 2: scan every app and use the safe suggestions in one go. */
    fun setupApps() = viewModelScope.launch {
        scanDevice().join()
        val suggestions = _scan.value.suggestions
        if (suggestions.isNotEmpty()) applySuggestions(suggestions).join()
    }

    fun applySuggestions(list: List<Suggestion>) = viewModelScope.launch {
        val (destructive, safe) = list.partition { it.mode == AppMode.BLOCK || it.mode == AppMode.SILENCE }
        safe.forEach { setModeNow(it.packageName, it.label, it.mode) }
        _toast.value = "Applied ${safe.size}" + if (destructive.isNotEmpty()) " · ${destructive.size} block/silence left for you to confirm" else ""
    }

    fun setMode(packageName: String, label: String, mode: AppMode) = viewModelScope.launch { setModeNow(packageName, label, mode) }

    private suspend fun setModeNow(packageName: String, label: String, mode: AppMode) {
        val current = dao.policy(packageName) ?: Policy(packageName, label, mode.id)
        dao.upsertPolicy(current.copy(mode = mode.id, updatedAt = System.currentTimeMillis()))
    }

    fun setInstructions(packageName: String, label: String, text: String) = viewModelScope.launch {
        val current = dao.policy(packageName) ?: Policy(packageName, label, "")
        dao.upsertPolicy(current.copy(instructions = text.take(1000)))
    }

    /** Done with it: it leaves NotiSift and, if it was showing in the drawer, the drawer too. */
    fun dismiss(entry: Entry) = viewModelScope.launch {
        val undo = clear(listOf(entry))
        _undo.value = UndoNotice("Done: ${entry.title.ifBlank { entry.appLabel }.take(40)}", undo)
    }

    /**
     * Marks [entries] done, takes their rows out of the drawer, and returns how to put everything back.
     * An Important row stands for every logged version of that notification, so all of them are done.
     */
    private suspend fun clear(entries: List<Entry>): suspend () -> Unit {
        val (important, rest) = entries.partition { Tier.of(it.action) == Tier.IMPORTANT }
        val shown = important.distinctBy { it.key }.filter { it.applied }
        val ids = rest.map { it.id } + important.distinctBy { it.key }.flatMap { dao.openImportantIds(it.key) } + important.map { it.id }
        dao.setDismissed(ids.distinct(), true)
        shown.forEach { app.digest.important.remove(it.key) }
        app.digest.requestRefresh()
        return {
            dao.setDismissed(ids.distinct(), false)
            shown.forEach { app.digest.important.restore(it) }
            app.digest.requestRefresh()
        }
    }

    fun clearCategory(category: Category, actions: List<String>) = viewModelScope.launch { dao.dismissCategory(category.id, actions); app.digest.requestRefresh() }

    /** Opening a notification means it's been seen, so it leaves NotiSift as it would leave the shade. */
    fun open(entry: Entry) = viewModelScope.launch {
        if (app.digest.openOriginal(entry)) clear(listOf(entry)) else _toast.value = "Can't open ${entry.appLabel}"
    }

    /** Clear / Clear all: exactly the items on screen, with Undo. */
    fun clearTier(entries: List<Entry>) = viewModelScope.launch {
        if (entries.isEmpty()) return@launch
        val undo = clear(entries)
        _undo.value = UndoNotice("Cleared ${entries.size}", undo)
    }

    /** "This mattered": keep this kind of notification from now on and put it back in the shade. Undo forgets the rule too. */
    fun markImportant(entry: Entry) = viewModelScope.launch {
        val undoRule = teach(entry, Action.KEEP)
        if (entry.applied) app.digest.important.restore(entry.copy(action = Action.KEEP.id))
        app.digest.requestRefresh()
        _undo.value = UndoNotice(if (entry.applied) "In the drawer · similar ones will be too" else "Similar ones will stay in the drawer") {
            undoRule()
            if (entry.applied) app.digest.important.remove(entry.key)
            app.digest.requestRefresh()
        }
    }

    /** "This was noise": always block this kind of notification. Undo forgets the rule too. */
    fun markJunk(entry: Entry) = viewModelScope.launch {
        val wasShowing = Tier.of(entry.action) == Tier.IMPORTANT && entry.applied
        val undoRule = teach(entry, Action.BLOCK)
        if (wasShowing) app.digest.important.remove(entry.key)
        app.digest.requestRefresh()
        _undo.value = UndoNotice("Junk · similar ones will be too") {
            undoRule()
            if (wasShowing) app.digest.important.restore(entry)
            app.digest.requestRefresh()
        }
    }

    /** Moves [entry] to [action] and saves that as the rule for similar ones; returns how to put both back. */
    private suspend fun teach(entry: Entry, action: Action): suspend () -> Unit {
        val previousRule = dao.cached(entry.templateKey)
        app.engineStore.pinCorrection(entry.templateKey, action, Category.fromId(entry.category))
        dao.correct(entry.id, action.id)
        return {
            if (previousRule != null) dao.cache(previousRule) else dao.uncache(entry.templateKey)
            dao.restoreDecision(entry.id, entry.action, entry.source)
        }
    }

    fun forgetLearned() = viewModelScope.launch { dao.clearLearned(); _toast.value = "Learned decisions cleared" }

    private fun startOfDay(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
