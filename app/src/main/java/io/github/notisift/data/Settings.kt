package io.github.notisift.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.notisift.BuildConfig
import io.github.notisift.engine.EngineSettings
import io.github.notisift.engine.CategoryChoices
import io.github.notisift.engine.ChatRules
import io.github.notisift.engine.KeywordRule
import io.github.notisift.engine.Prompts
import io.github.notisift.llm.ModelConfig
import io.github.notisift.llm.ProviderDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

data class AppSettings(
    val provider: String = BuildConfig.ENV_PROVIDER,
    val baseUrl: String = BuildConfig.ENV_BASE_URL,
    val apiKey: String = BuildConfig.ENV_API_KEY,
    val model: String = BuildConfig.ENV_MODEL,
    val aiEnabled: Boolean = true,
    /** Observe mode records decisions without removing anything. On by default. */
    val observe: Boolean = true,
    /** Off: NotiSift reads nothing, moves nothing and calls no model. Unlike preview (observe), nothing is logged. */
    val stopped: Boolean = false,
    /** The first-run tour was finished or skipped. */
    val tourDone: Boolean = false,
    /** Home lists by calendar day instead of by group. */
    val groupByDay: Boolean = false,
    /** "Not now" on Home's add-an-AI card: it stays hidden after that (Settings still has the guide). */
    val aiTipDismissed: Boolean = false,
    /** Per-chat choices, one "mode\tpackage\tchat" per line (see ChatRules). */
    val chatRules: String = "",
    /** Comma-separated names people @mention the user by. */
    val myNames: String = "",
    val threshold: Double = 0.75,
    val rules: String = Prompts.DEFAULT_RULES,
    val keywordRules: String = "",
    val manageUnlistedApps: Boolean = true,
    /** Only Important stays in the shade; everything else goes to Later. */
    val inboxMode: Boolean = true,
    /** NotiSift's stacks replace the drawer: important originals are re-shown by NotiSift, then removed. */
    val replaceShade: Boolean = true,
    /** Per-group choices, `news=later;home=important` (see CategoryChoices). */
    val categoryChoices: String = "",
    val digest: Boolean = true,
    val retentionDays: Int = 14,
) {
    val modelConfig: ModelConfig get() = ProviderDetector.resolve(provider, apiKey, baseUrl, model, BuildConfig.ENV_API_KEY_NAME)

    override fun toString() = "AppSettings(provider=$provider, model=$model, observe=$observe, apiKey=<redacted>)"

    val engine: EngineSettings get() = EngineSettings(
        rules = rules,
        keywordRules = KeywordRule.parse(keywordRules),
        threshold = threshold,
        manageUnlistedApps = manageUnlistedApps,
        inboxMode = inboxMode,
        categoryChoices = CategoryChoices.parse(categoryChoices),
        chatRules = ChatRules.parse(chatRules),
        myNames = myNames.split(',').map { it.trim() }.filter { it.isNotEmpty() },
    )
}

class SettingsStore(private val context: Context) {
    private object K {
        val provider = stringPreferencesKey("provider")
        val baseUrl = stringPreferencesKey("base_url")
        val apiKey = stringPreferencesKey("api_key_enc")
        val model = stringPreferencesKey("model")
        val aiEnabled = booleanPreferencesKey("ai_enabled")
        val observe = booleanPreferencesKey("observe")
        val stopped = booleanPreferencesKey("stopped")
        val tourDone = booleanPreferencesKey("tour_done")
        val groupByDay = booleanPreferencesKey("group_by_day")
        val aiTipDismissed = booleanPreferencesKey("ai_tip_dismissed")
        val chatRules = stringPreferencesKey("chat_rules")
        val myNames = stringPreferencesKey("my_names")
        val threshold = doublePreferencesKey("threshold")
        val rules = stringPreferencesKey("rules")
        val keywordRules = stringPreferencesKey("keyword_rules")
        val manageUnlisted = booleanPreferencesKey("manage_unlisted")
        val inboxMode = booleanPreferencesKey("inbox_mode")
        val replaceShade = booleanPreferencesKey("replace_shade")
        val categoryChoices = stringPreferencesKey("category_choices")
        val digest = booleanPreferencesKey("digest")
        val retention = intPreferencesKey("retention_days")
    }

    val flow: Flow<AppSettings> = context.store.data.map(::read)

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            provider = p[K.provider] ?: d.provider,
            baseUrl = p[K.baseUrl] ?: d.baseUrl,
            apiKey = p[K.apiKey]?.let { runCatching { KeyCipher.decrypt(it) }.getOrNull() } ?: d.apiKey,
            model = p[K.model] ?: d.model,
            aiEnabled = p[K.aiEnabled] ?: d.aiEnabled,
            observe = p[K.observe] ?: d.observe,
            stopped = p[K.stopped] ?: d.stopped,
            tourDone = p[K.tourDone] ?: d.tourDone,
            groupByDay = p[K.groupByDay] ?: d.groupByDay,
            aiTipDismissed = p[K.aiTipDismissed] ?: d.aiTipDismissed,
            chatRules = p[K.chatRules] ?: d.chatRules,
            myNames = p[K.myNames] ?: d.myNames,
            threshold = p[K.threshold] ?: d.threshold,
            rules = p[K.rules] ?: d.rules,
            keywordRules = p[K.keywordRules] ?: d.keywordRules,
            manageUnlistedApps = p[K.manageUnlisted] ?: d.manageUnlistedApps,
            inboxMode = p[K.inboxMode] ?: d.inboxMode,
            replaceShade = p[K.replaceShade] ?: d.replaceShade,
            categoryChoices = p[K.categoryChoices] ?: d.categoryChoices,
            digest = p[K.digest] ?: d.digest,
            retentionDays = p[K.retention] ?: d.retentionDays,
        )
    }

    private fun write(p: androidx.datastore.preferences.core.MutablePreferences, s: AppSettings, before: AppSettings) {
        p[K.provider] = s.provider
        p[K.baseUrl] = s.baseUrl
        // Only touch the stored ciphertext when the user actually changed the key. If it could not be
        // decrypted (Keystore reset), leaving it alone means a later fix can still recover it.
        if (s.apiKey != before.apiKey) p[K.apiKey] = KeyCipher.encrypt(s.apiKey)
        p[K.model] = s.model
        p[K.aiEnabled] = s.aiEnabled
        p[K.observe] = s.observe
        p[K.stopped] = s.stopped
        p[K.tourDone] = s.tourDone
        p[K.groupByDay] = s.groupByDay
        p[K.aiTipDismissed] = s.aiTipDismissed
        p[K.chatRules] = s.chatRules.take(20_000)
        p[K.myNames] = s.myNames.take(500)
        p[K.threshold] = s.threshold.coerceIn(0.5, 0.99)
        p[K.rules] = s.rules.take(4000)
        p[K.keywordRules] = s.keywordRules.take(4000)
        p[K.manageUnlisted] = s.manageUnlistedApps
        p[K.inboxMode] = s.inboxMode
        p[K.replaceShade] = s.replaceShade
        p[K.categoryChoices] = s.categoryChoices.take(1000)
        p[K.digest] = s.digest
        p[K.retention] = s.retentionDays.coerceIn(1, 90)
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.store.edit { p ->
            val before = read(p)
            write(p, transform(before), before)
        }
    }
}
