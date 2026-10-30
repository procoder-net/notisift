package io.github.notisift.engine

import io.github.notisift.llm.DecisionRequest
import io.github.notisift.llm.DecisionResult
import io.github.notisift.llm.choice
import io.github.notisift.llm.DecisionModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Storage the engine needs. Backed by Room in the app and by maps in tests. */
interface EngineStore {
    /** null = the user has not chosen a mode for this app yet. */
    suspend fun appMode(packageName: String): AppMode?
    suspend fun appInstructions(packageName: String): String
    suspend fun appCategory(packageName: String): String
    suspend fun cached(templateKey: String): Verdict?
    suspend fun remember(templateKey: String, verdict: Verdict)

    /** Groups worth offering the AI on this phone (see [Category.relevant]). */
    suspend fun categories(): List<Category> = Category.entries

    /** Messages logged in the last day from this chat ([ChatRules.chatName]) of this app. */
    suspend fun recentChatMessages(packageName: String, chat: String): Int = 0
}

data class EngineSettings(
    val rules: String = Prompts.DEFAULT_RULES,
    val keywordRules: List<KeywordRule> = emptyList(),
    /** Model answers below this confidence are kept (fail open). */
    val threshold: Double = 0.75,
    val manageUnlistedApps: Boolean = true,
    val modelTimeoutMs: Long = 15_000,
    /** Only notifications the model confidently keeps stay in the shade; the rest go to Later. */
    val inboxMode: Boolean = true,
    /** The user's per-group choices (e.g. News → Later), applied after app rules and before the AI's action. */
    val categoryChoices: Map<Category, GroupChoice> = emptyMap(),
    /** Per-chat choices ([ChatRules.key] → mode); the only way a conversation leaves the top automatically. */
    val chatRules: Map<String, ChatMode> = emptyMap(),
    /** Names people @mention the user by, for "only when mentioned" chats. */
    val myNames: List<String> = emptyList(),
    /** Group chats without a choice of their own: sale posts and busy days wait in Later, @mentions stay on top. */
    val smartGroups: Boolean = true,
)

/**
 * Pipeline, cheapest and safest first:
 * protected → keyword rules → app policy → duplicate → learned cache → model (or offline heuristics) → fail open.
 * Returns null when the notification is not ours to manage.
 */
class DecisionEngine(
    private val store: EngineStore,
    private val model: () -> DecisionModel?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val recent = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 256
    }

    suspend fun decide(n: NotificationInput, settings: EngineSettings, checkDuplicates: Boolean = true): Verdict? {
        if (n.isGroupSummary) return null
        LocalRules.protect(n)?.let { return it }
        val communication = LocalRules.isConversation(n)
        // Keyword rules match loosely ("sale" is in "I got the sale done"), so they never touch conversations.
        if (!communication) LocalRules.keyword(n, settings.keywordRules)?.let { return it }
        // A choice for this exact chat is more specific than the app's, so it wins over it.
        if (communication) chatChoice(n, settings)?.let { return it }

        val mode = store.appMode(n.packageName) ?: if (settings.manageUnlistedApps) AppMode.AUTO else AppMode.UNMANAGED
        if (mode == AppMode.UNMANAGED) return null
        // Starred in Android's own conversation settings: that is already the user's choice.
        if (communication && n.isPriorityConversation) return Verdict(Action.KEEP, Category.PERSONAL, 1.0, Source.PROTECTED, "Priority conversation")
        // An explicit per-app choice is the only thing allowed to remove conversations.
        LocalRules.fromAppMode(mode, n, store.appCategory(n.packageName))?.let { return it }

        val template = n.templateKey
        val cacheable = n.title.isNotBlank() || n.text.isNotBlank()
        val cached = if (cacheable) store.cached(template) else null
        if (communication) {
            // Only the user's own "Junk"/"Important" tap on this exact template overrides conversation protection.
            if (cached?.source == Source.CORRECTION) return cached
            smartGroup(n, settings)?.let { return it }
            return Verdict(Action.KEEP, Category.PERSONAL, 1.0, Source.PROTECTED, "Conversation or email")
        }
        cached?.let { return guard(withChoice(it, settings), n, settings.inboxMode).also { v -> noteRemoval(n, v) } }

        if (settings.inboxMode && n.isUnreadable) return Verdict(Action.BUNDLE, Heuristics.category(n), 1.0, Source.INBOX, "No readable content").also { noteRemoval(n, it) }
        if (checkDuplicates && isDuplicate(n)) return Verdict(Action.BLOCK, Heuristics.category(n), 1.0, Source.DUPLICATE, "Repeat of a removed notification")
        return decideFresh(n, settings, template, cacheable).also { noteRemoval(n, it) }
    }

    private fun chatChoice(n: NotificationInput, s: EngineSettings): Verdict? {
        val mode = s.chatRules[ChatRules.key(n.packageName, n.title)] ?: return null
        fun verdict(action: Action, reason: String) = Verdict(action, Category.PERSONAL, 1.0, Source.CHAT, reason)
        return when (mode) {
            ChatMode.ALWAYS -> verdict(Action.KEEP, "Always on top: your choice for this chat")
            ChatMode.MUTE -> verdict(Action.BUNDLE, "Muted chat: waits in Later")
            ChatMode.MENTIONS -> {
                if (ChatRules.mentions(n.text, mentionNames(n, s))) verdict(Action.KEEP, "You were mentioned")
                else verdict(Action.BUNDLE, "Busy chat: only mentions go on top")
            }
        }
    }

    /**
     * A group chat with no choice of its own: a sale post or forward waits in Later, and so does everything on a
     * busy day ([GroupSmarts.BUSY_PER_DAY]+ messages) unless it mentions the user. 1:1 chats never get here.
     */
    private suspend fun smartGroup(n: NotificationInput, s: EngineSettings): Verdict? {
        if (!s.smartGroups || !ChatRules.looksLikeGroup(n.title, n.text)) return null
        val names = mentionNames(n, s)
        if (ChatRules.mentions(n.text, names)) return null
        // Until NotiSift knows the user's name, any @mention might be them: never risk hiding it.
        if (names.isEmpty() && anyMention.containsMatchIn(n.text)) return null
        fun later(reason: String) = Verdict(Action.BUNDLE, Category.PERSONAL, 1.0, Source.SMART_GROUP, reason)
        if (GroupSmarts.isPromo(n.text)) return later("Sale post or forward in a group chat")
        val today = store.recentChatMessages(n.packageName, ChatRules.chatName(n.title))
        return if (today + 1 >= GroupSmarts.BUSY_PER_DAY) later("Busy group (${today + 1} today): only mentions go on top") else null
    }

    private val anyMention = Regex("(?:^|[\\s(])@\\S")

    private fun mentionNames(n: NotificationInput, s: EngineSettings) =
        s.myNames + n.selfName.takeUnless { it.isBlank() || it.equals("you", ignoreCase = true) }.let(::listOfNotNull)

    private suspend fun decideFresh(n: NotificationInput, settings: EngineSettings, template: String, cacheable: Boolean): Verdict {
        val appCategory = store.appCategory(n.packageName)
        val client = model() ?: return guard(withChoice(Heuristics.classify(n, appCategory), settings), n, settings.inboxMode)
        val verdict = try {
            withTimeoutOrNull(settings.modelTimeoutMs) { ask(client, n, appCategory, settings) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return Verdict(Action.KEEP, Heuristics.category(n, appCategory), 0.0, Source.FALLBACK, "Model unavailable")

        // The AI said "other": the on-phone rules often know better (app type, keywords).
        // A news, social, video or game app's notifications belong to the app's group whatever the AI read into them.
        val appGroup = Heuristics.contentApp(appCategory, "${n.packageName} ${n.appLabel}")
        val grouped = when {
            verdict.category == Category.PERSONAL || verdict.category == Category.SECURITY -> verdict
            appGroup != null -> verdict.copy(category = appGroup)
            verdict.category == Category.OTHER -> verdict.copy(category = Heuristics.category(n, appCategory))
            else -> verdict
        }
        val chosen = withChoice(grouped, settings)
        val decided = when {
            chosen.source == Source.GROUP -> chosen
            settings.inboxMode -> inbox(grouped, settings)
            else -> confident(grouped, settings)
        }
        val guarded = guard(decided, n, settings.inboxMode)
        // Learn only reversible outcomes; a generalised BLOCK/SILENCE could hide a different notification.
        if (cacheable && guarded.source == Source.MODEL && guarded.confidence >= 0.9 && (guarded.action == Action.KEEP || guarded.action == Action.BUNDLE)) store.remember(template, guarded)
        return guarded
    }

    /** Applies the user's choice for the verdict's group, if they made one (protected groups only allow Important). */
    private fun withChoice(v: Verdict, settings: EngineSettings): Verdict {
        if (v.source == Source.CORRECTION) return v
        val choice = settings.categoryChoices[v.category]?.takeIf { CategoryChoices.allowed(v.category, it) } ?: return v
        val tier = choice.tier ?: return v
        return v.copy(action = with(CategoryChoices) { tier.action() }, source = Source.GROUP, reason = "Your choice for ${v.category.label}")
    }

    /** Bundling is reversible (it sits in the digest), so it needs less certainty than silence/block. */
    private fun confident(v: Verdict, settings: EngineSettings): Verdict {
        val needed = if (v.action == Action.BUNDLE) settings.threshold - BUNDLE_MARGIN else settings.threshold
        if (v.action == Action.KEEP || v.confidence >= needed) return v
        return v.copy(action = Action.KEEP, source = Source.FALLBACK, reason = "Low confidence (${"%.2f".format(v.confidence)}): ${v.reason}")
    }

    /** Inbox mode: stay in the shade only when the model is sure it matters; anything doubtful goes to Later. */
    private fun inbox(v: Verdict, settings: EngineSettings): Verdict {
        val important = v.action == Action.KEEP && v.source == Source.MODEL && v.confidence >= settings.threshold - BUNDLE_MARGIN
        val sureRemoval = v.action != Action.KEEP && v.confidence >= settings.threshold
        return when {
            important || sureRemoval || v.action == Action.BUNDLE -> v
            else -> v.copy(action = Action.BUNDLE, source = Source.INBOX, reason = "Not clearly important · ${v.reason}")
        }
    }

    /**
     * Safety net for automatic decisions: protected categories, codes and conversations are never removed,
     * and emails/business texts can at most be bundled (always recoverable from the digest).
     */
    private fun guard(v: Verdict, n: NotificationInput, inboxMode: Boolean = false): Verdict = when {
        v.action == Action.KEEP || v.source == Source.CORRECTION -> v
        LocalRules.isCode(n) || LocalRules.isConversation(n) -> v.copy(action = Action.KEEP, reason = "Protected: ${v.category.label}")
        // Inbox mode protects money movements, not every money-app notification: price alerts, card offers and
        // money emails the model wants gone go to Later (bundled, never deleted).
        inboxMode && v.category == Category.FINANCE && (!Heuristics.isMoneyMovement(n) || (v.source == Source.MODEL && LocalRules.isEmailLike(n))) ->
            v.copy(action = Action.BUNDLE, reason = "Money update to Later · ${v.reason}")
        v.category.protected -> v.copy(action = Action.KEEP, reason = "Protected: ${v.category.label}")
        LocalRules.isEmailLike(n) && v.action != Action.BUNDLE -> v.copy(action = Action.BUNDLE, reason = "Emails are bundled, never removed · ${v.reason}")
        else -> v
    }

    /**
     * True for an exact repeat of something we chose to remove within the window. Repeats of kept
     * notifications are decided normally, and a hit does not extend the window.
     */
    private fun isDuplicate(n: NotificationInput): Boolean = synchronized(recent) {
        val seen = recent[n.contentKey] ?: return false
        clock() - seen < DUPLICATE_WINDOW_MS
    }

    private fun noteRemoval(n: NotificationInput, v: Verdict) {
        if (v.action == Action.KEEP) return
        synchronized(recent) { recent[n.contentKey] = clock() }
    }

    private suspend fun ask(client: DecisionModel, n: NotificationInput, appCategory: String, s: EngineSettings): Verdict? {
        val requests = Prompts.cleanupRequests(Prompts.notificationContext(n, appCategory), s.rules, store.appInstructions(n.packageName), store.categories())
        return toVerdict(requests, client.decideAll(requests))
    }

    companion object {
        const val DUPLICATE_WINDOW_MS = 10 * 60 * 1000L
        const val BUNDLE_MARGIN = 0.15

        /** [requests] = [action, category]. "unsure" and answers outside the options become KEEP or no answer. */
        fun toVerdict(requests: List<DecisionRequest>, results: List<DecisionResult>): Verdict? {
            val actionId = results.getOrNull(0)?.choice(requests[0]) ?: return null
            val p = results[0].confidence?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: return null
            val category = Category.fromId(results.getOrNull(1)?.choice(requests[1]))
            if (actionId == Prompts.UNSURE) return Verdict(Action.KEEP, category, p, Source.FALLBACK, "Model unsure")
            val action = Action.fromId(actionId) ?: return null
            return Verdict(action, category, p, Source.MODEL, "${(p * 100).toInt()}% sure · ${category.label}")
        }
    }
}
