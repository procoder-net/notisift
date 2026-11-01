package io.github.notisift

import android.app.Application
import android.util.Log
import io.github.notisift.data.AppSettings
import io.github.notisift.data.HomeStatsStore
import io.github.notisift.data.RoomEngineStore
import io.github.notisift.data.SettingsStore
import io.github.notisift.data.SiftDatabase
import io.github.notisift.engine.Action
import io.github.notisift.engine.Category
import io.github.notisift.engine.DecisionEngine
import io.github.notisift.engine.NotificationInput
import io.github.notisift.engine.OnPhoneTidy
import io.github.notisift.engine.MoveUp
import io.github.notisift.engine.MoveUpDrawer
import io.github.notisift.engine.MoveUpStore
import io.github.notisift.engine.Source
import io.github.notisift.data.Entry
import io.github.notisift.llm.DecisionModel
import io.github.notisift.llm.BatchingModel
import io.github.notisift.llm.GeminiNano
import io.github.notisift.llm.JsonRequest
import io.github.notisift.llm.NanoStatus
import io.github.notisift.llm.LlmClient
import io.github.notisift.llm.ModelConfig
import io.github.notisift.service.DigestNotifier
import io.github.notisift.service.SiftListener
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay

/** Hand-wired dependency graph; small enough not to need a DI framework. */
class SiftApp : Application() {
    /** Background work outlives screens; one failing job is logged instead of crashing the listener. */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("NotiSift", "Background task failed", e) },
    )
    val database by lazy { SiftDatabase.create(this) }
    val dao get() = database.dao()
    val settingsStore by lazy { SettingsStore(this) }
    val homeStats by lazy { HomeStatsStore(this) }
    val engineStore by lazy { RoomEngineStore(dao) }
    val engine by lazy { DecisionEngine(engineStore, ::currentModel) }
    val digest by lazy { DigestNotifier(this) }
    val nano by lazy { GeminiNano(scope) }
    private val tidy by lazy { OnPhoneTidy(engine) }

    /** NotiSift is on screen: the only time Android lets the phone's own AI answer. */
    @Volatile private var onScreen = false
    private var lastTidy = 0L
    private var lastMoveUp = 0L
    private val _learnedOnPhone = MutableStateFlow(0)

    /** Kinds of notification the on-phone AI taught NotiSift since the app started. */
    val learnedOnPhone: StateFlow<Int> = _learnedOnPhone.asStateFlow()
    private val _movedUp = MutableStateFlow<List<Entry>>(emptyList())

    /** What the on-phone AI last moved from Later to Important (as they were before), for Home's "Moved up" card. */
    val movedUp: StateFlow<List<Entry>> = _movedUp.asStateFlow()

    lateinit var settings: StateFlow<AppSettings?>
        private set

    @Volatile private var client: Pair<ModelConfig, DecisionModel>? = null

    override fun onCreate() {
        super.onCreate()
        settings = settingsStore.flow.stateIn(scope, SharingStarted.Eagerly, null)
        // The demo also fills its own drawer rows from the made-up log, for screenshots of the sorted drawer.
        if (BuildConfig.DEMO) scope.launch { DemoData.seed(this@SiftApp); digest.rebuild() }
        else scope.launch { dao.deleteFromNotiSift(SiftListener.BASE_PACKAGE); digest.requestRefresh() }
        // History is kept exactly as long as Settings says: re-checked when that changes and every few hours.
        scope.launch {
            settings.filterNotNull().map { it.retentionDays }.distinctUntilChanged().collectLatest { days ->
                while (true) {
                    dao.prune(System.currentTimeMillis() - days * DAY_MS)
                    dao.pruneCache(System.currentTimeMillis() - RoomEngineStore.CACHE_TTL_MS)
                    delay(PRUNE_EVERY_MS)
                }
            }
        }
    }

    suspend fun awaitSettings(): AppSettings = settings.filterNotNull().first()

    /** The configured model, or null when AI is off or not configured (offline rules are used then). */
    fun currentModel(): DecisionModel? {
        val s = settings.value ?: return null
        if (!s.aiEnabled) return null
        val config = s.modelConfig
        if (!config.isUsable) return null
        client?.takeIf { it.first == config }?.let { return it.second }
        return runCatching { BatchingModel(LlmClient(config)) }.getOrNull()?.also { client = config to it }
    }

    /**
     * NotiSift came on screen. On phones with their own AI it writes the waiting groups' summaries and takes a
     * second look at what the offline rules sorted; anything else keeps using the AI key or the rules.
     */
    fun onShown() {
        onScreen = true
        checkOnPhoneAi()
    }

    /**
     * Also run when the user turns the on-phone AI on ([justTurnedOn]: the saved setting may not have reached
     * [settings] yet), so it starts without leaving the app.
     */
    fun checkOnPhoneAi(justTurnedOn: Boolean = false) {
        if (BuildConfig.DEMO) return
        scope.launch {
            val s = awaitSettings().let { if (justTurnedOn) it.copy(onDeviceAi = true) else it }
            if (!s.onDeviceAi || s.stopped) return@launch
            nano.refresh(allowDownload = true)
            if (nano.status.value != NanoStatus.READY) return@launch
            digest.requestRefresh()
            if (claimMoveUp()) moveUpWhatNeedsYou()
            if (!claimTidy()) return@launch
            val sources = OnPhoneTidy.guessed(hasCloudModel = currentModel() != null).map { it.name }
            val recent = dao.sortedBySince(sources, System.currentTimeMillis() - TIDY_LOOKBACK_MS, limit = 200)
                .filter { OnPhoneTidy.worthAsking(Category.fromId(it.category)) }
                .map { e -> NotificationInput(e.key, e.packageName, e.appLabel, e.channelId, title = e.title, text = e.text, postedAt = e.postedAt) }
            val learned = tidy.run(recent, s.engine, nano.model)
            if (learned > 0) _learnedOnPhone.value += learned
        }
    }

    private val moveUp by lazy {
        MoveUp(
            object : MoveUpStore {
                override suspend fun laterSince(since: Long) = dao.laterSince(since)
                override suspend fun stillWaiting(ids: List<Long>) = dao.stillWaiting(ids).toSet()
                override suspend fun setDecision(id: Long, action: String, source: String) = dao.setDecision(listOf(id), action, source)
                override suspend fun movedBackBefore(templateKey: String) = engineStore.cached(templateKey)?.source == Source.CORRECTION
                override suspend fun rememberMovedBack(entry: Entry) = engineStore.pinCorrection(entry.templateKey, Action.BUNDLE, Category.fromId(entry.category))
            },
            object : MoveUpDrawer {
                override suspend fun showOnTop(entry: Entry) = digest.important.restore(entry)
                override suspend fun takeOffTop(entry: Entry) = digest.important.remove(entry.key)
                override fun refresh() = digest.requestRefresh()
            },
        )
    }

    /** "Important on top": the few Later notifications that need the user today go up to Important. */
    private suspend fun moveUpWhatNeedsYou() {
        if (!onPhoneAiReady()) return
        val picked = moveUp.run(nano.model::completeJson)
        if (picked.isNotEmpty()) _movedUp.update { (it + picked).distinctBy { e -> e.id } }
    }

    /** Puts what was moved up back in Later, and remembers it, so that kind of notification is never moved up again. */
    suspend fun moveBackDown(entries: List<Entry>) {
        moveUp.moveBack(entries)
        dismissMovedUp(entries)
    }

    /** Takes [handled] off the "Moved up" card; anything moved up since stays on it. */
    fun dismissMovedUp(handled: List<Entry>) {
        val ids = handled.map { it.id }.toSet()
        _movedUp.update { list -> list.filter { it.id !in ids } }
    }

    /** Looking at Later is cheap (one short prompt), so it runs on most opens, but not on every quick switch back. */
    @Synchronized
    private fun claimMoveUp(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastMoveUp < MOVE_UP_EVERY_MS) return false
        lastMoveUp = now
        return true
    }

    /**
     * Who answers "Talk to NotiSift": the phone's own AI while it's ready (it is, the user is typing in the app),
     * otherwise the user's AI model. The flag says whether it runs on the phone.
     */
    fun assistantModel(): Pair<suspend (JsonRequest) -> JsonObject, Boolean>? {
        if (onPhoneAiReady()) return nano.model::completeJson to true
        return chatModel()?.let { (it::completeJson as suspend (JsonRequest) -> JsonObject) to false }
    }

    /** At most one second look every [TIDY_EVERY_MS], however often the app is opened. */
    @Synchronized
    private fun claimTidy(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastTidy < TIDY_EVERY_MS) return false
        lastTidy = now
        return true
    }

    fun onHidden() {
        onScreen = false
    }

    /** True when the phone's own AI is on and can answer right now. */
    fun onPhoneAiReady(): Boolean = onScreen && settings.value?.onDeviceAi == true && nano.status.value == NanoStatus.READY

    /**
     * Who writes the drawer's one-line group summaries: the phone's own AI while NotiSift is open, otherwise the
     * user's AI key if they turned cloud summaries on, otherwise nobody.
     */
    fun summarizer(): (suspend (JsonRequest) -> JsonObject)? {
        if (onPhoneAiReady()) return nano.model::completeJson
        return summaryModel()?.let { it::completeJson }
    }

    @Volatile private var summaryClient: Pair<ModelConfig, LlmClient>? = null

    /** The model for drawer summaries: only when AI is on, configured, and the user turned summaries on. */
    fun summaryModel(): LlmClient? {
        val s = settings.value ?: return null
        if (!s.aiSummaries) return null
        return chatModel()
    }

    /** The user's AI model as a JSON writer, when AI is on and set up. */
    private fun chatModel(): LlmClient? {
        val s = settings.value ?: return null
        if (!s.aiEnabled) return null
        val config = s.modelConfig
        if (!config.isUsable) return null
        summaryClient?.takeIf { it.first == config }?.let { return it.second }
        return runCatching { LlmClient(config) }.getOrNull()?.also { summaryClient = config to it }
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        private const val PRUNE_EVERY_MS = 6L * 60 * 60 * 1000
        private const val MOVE_UP_EVERY_MS = 2L * 60 * 1000
        private const val TIDY_EVERY_MS = 10L * 60 * 1000
        private const val TIDY_LOOKBACK_MS = 2 * DAY_MS
    }
}
