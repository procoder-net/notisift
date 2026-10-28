package io.github.notisift.data

import io.github.notisift.engine.Action
import io.github.notisift.engine.AppMode
import io.github.notisift.engine.Category
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What NotiSift learned and what you chose, as one JSON file for moving to a new phone. Never the API key (it is
 * encrypted to this phone and is a secret) and never notification history (private content).
 */
@Serializable
data class BackupFile(
    val format: String = Backup.FORMAT,
    val version: Int = Backup.VERSION,
    val exportedAt: Long = 0,
    val settings: BackupSettings = BackupSettings(),
    val apps: List<BackupApp> = emptyList(),
    val corrections: List<BackupCorrection> = emptyList(),
)

@Serializable
data class BackupSettings(
    val provider: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val aiEnabled: Boolean = true,
    val threshold: Double = 0.75,
    val rules: String = "",
    val keywordRules: String = "",
    val chatRules: String = "",
    val myNames: String = "",
    val categoryChoices: String = "",
    val inboxMode: Boolean = true,
    val replaceShade: Boolean = true,
    val digest: Boolean = true,
    val groupByDay: Boolean = false,
    val manageUnlistedApps: Boolean = true,
    val retentionDays: Int = 14,
)

@Serializable
data class BackupApp(val packageName: String, val label: String = "", val mode: String, val instructions: String = "")

@Serializable
data class BackupCorrection(val templateKey: String, val action: String, val category: String, val reason: String = "")

/** What an import changed, for the confirmation line. */
data class ImportSummary(val apps: Int, val corrections: Int)

object Backup {
    const val FORMAT = "notisift-backup"
    const val VERSION = 1
    /** A real backup is a few hundred KB at most; anything bigger isn't one. */
    const val MAX_BYTES = 5_000_000
    private val packageName = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun build(settings: AppSettings, policies: List<Policy>, corrections: List<CachedDecision>, now: Long): BackupFile = BackupFile(
        exportedAt = now,
        settings = BackupSettings(
            provider = settings.provider, baseUrl = settings.baseUrl, model = settings.model, aiEnabled = settings.aiEnabled,
            threshold = settings.threshold, rules = settings.rules, keywordRules = settings.keywordRules,
            chatRules = settings.chatRules, myNames = settings.myNames, categoryChoices = settings.categoryChoices,
            inboxMode = settings.inboxMode, replaceShade = settings.replaceShade, digest = settings.digest,
            groupByDay = settings.groupByDay, manageUnlistedApps = settings.manageUnlistedApps, retentionDays = settings.retentionDays,
        ),
        // Only apps you (or a suggestion you accepted) set: scan suggestions are redone on the new phone.
        apps = policies.filter { it.mode.isNotBlank() }.map { BackupApp(it.packageName, it.label, it.mode, it.instructions) },
        corrections = corrections.map { BackupCorrection(it.templateKey, it.action, it.category, it.reason) },
    )

    fun encode(file: BackupFile): String = json.encodeToString(BackupFile.serializer(), file)

    /** Parses and cleans an untrusted file: unknown modes, actions and package names are dropped, text is capped. */
    fun decode(text: String): BackupFile {
        require(text.length <= MAX_BYTES) { "That file is too big to be a NotiSift backup." }
        val raw = runCatching { json.decodeFromString(BackupFile.serializer(), text) }
            .getOrElse { throw IllegalArgumentException("That file isn't a NotiSift backup.") }
        require(raw.format == FORMAT) { "That file isn't a NotiSift backup." }
        require(raw.version in 1..VERSION) { "This backup is from a newer NotiSift. Update the app, then import again." }
        val s = raw.settings
        return raw.copy(
            settings = s.copy(
                provider = s.provider.take(40), baseUrl = s.baseUrl.take(300), model = s.model.take(120),
                threshold = s.threshold.coerceIn(0.5, 0.95), rules = s.rules.take(4_000), keywordRules = s.keywordRules.take(10_000),
                chatRules = s.chatRules.take(20_000), myNames = s.myNames.take(500), categoryChoices = s.categoryChoices.take(4_000),
                retentionDays = s.retentionDays.coerceIn(1, 365),
            ),
            apps = raw.apps.filter { packageName.matches(it.packageName) && AppMode.fromId(it.mode) != null }
                .distinctBy { it.packageName }.take(5_000)
                .map { it.copy(label = it.label.take(100), instructions = it.instructions.take(1_000)) },
            corrections = raw.corrections.filter { it.templateKey.isNotBlank() && it.templateKey.length <= 500 && Action.fromId(it.action) != null }
                .distinctBy { it.templateKey }.take(20_000)
                .map { it.copy(category = Category.fromId(it.category).id, reason = it.reason.take(200)) },
        )
    }

    /** [current] with the backup's choices; the API key and this phone's own state (tour, Off, preview) stay as they are. */
    fun applyTo(current: AppSettings, s: BackupSettings): AppSettings = current.copy(
        provider = s.provider, baseUrl = s.baseUrl, model = s.model, aiEnabled = s.aiEnabled, threshold = s.threshold,
        rules = s.rules.ifBlank { current.rules }, keywordRules = s.keywordRules, chatRules = s.chatRules, myNames = s.myNames,
        categoryChoices = s.categoryChoices, inboxMode = s.inboxMode, replaceShade = s.replaceShade, digest = s.digest,
        groupByDay = s.groupByDay, manageUnlistedApps = s.manageUnlistedApps, retentionDays = s.retentionDays,
    )
}
