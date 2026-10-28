package io.github.notisift.scan

import io.github.notisift.engine.AppMode
import io.github.notisift.engine.Category
import io.github.notisift.engine.Prompts
import io.github.notisift.llm.DecisionModel
import io.github.notisift.llm.DecisionRequest
import io.github.notisift.llm.DecisionResult
import io.github.notisift.llm.choice
import kotlinx.coroutines.CancellationException

data class AppProfile(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val appCategory: String,
    val launchable: Boolean,
    val notificationCount: Int,
    val channels: List<String>,
    val sampleTitles: List<String>,
    val currentMode: AppMode?,
)

data class Suggestion(
    val packageName: String,
    val label: String,
    val mode: AppMode,
    val category: Category,
    val reason: String,
    val byModel: Boolean,
)

/**
 * Turns a device inventory into per-app choices. Uses the configured model in batches and falls
 * back to offline heuristics for any batch (or app) the model does not answer.
 */
class PolicyAdvisor(private val model: DecisionModel?, private val rules: String) {

    suspend fun advise(apps: List<AppProfile>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<Suggestion> {
        val out = mutableListOf<Suggestion>()
        // Busiest apps first so the most valuable suggestions arrive early.
        val ordered = apps.sortedByDescending { it.notificationCount }
        ordered.chunked(BATCH).forEach { batch ->
            val fromModel = model?.let { runBatch(it, batch) }.orEmpty()
            batch.forEach { app -> out += fromModel[app.packageName] ?: offline(app) }
            onProgress(out.size, ordered.size)
        }
        return out
    }

    private suspend fun runBatch(model: DecisionModel, batch: List<AppProfile>): Map<String, Suggestion> = try {
        // One decision per app over a shared inventory context; batching providers answer them in one call.
        val inventory = mapOf("apps" to describe(batch))
        val requests = batch.map { Prompts.adviseRequest(inventory, clean(it.label, 40), it.packageName, rules) }
        parse(batch, requests, model.decideAll(requests))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyMap()
    }

    companion object {
        const val BATCH = 30

        private val unsafe = Regex("[\\r\\n|<>]+")

        /** Labels and titles come from other apps and push senders: flatten them so they can't forge list lines. */
        fun clean(s: String, max: Int) = s.replace(unsafe, " ").trim().take(max)

        fun describe(batch: List<AppProfile>): String = buildString {
            appendLine("<apps>")
            batch.forEach { a ->
                append("- ${a.packageName} | ${clean(a.label, 40)}")
                if (a.appCategory.isNotBlank()) append(" | store: ${a.appCategory}")
                if (a.isSystem) append(" | system")
                append(" | notifications(14d): ${a.notificationCount}")
                if (a.channels.isNotEmpty()) append(" | channels: ${a.channels.take(5).joinToString(", ") { clean(it, 30) }}")
                if (a.sampleTitles.isNotEmpty()) append(" | samples: ${a.sampleTitles.take(4).joinToString(" / ") { clean(it, 50) }}")
                appendLine()
            }
            append("</apps>")
        }

        fun parse(batch: List<AppProfile>, requests: List<DecisionRequest>, results: List<DecisionResult>): Map<String, Suggestion> =
            batch.indices.mapNotNull { i ->
                val app = batch[i]
                val r = results.getOrNull(i) ?: return@mapNotNull null
                val mode = AppMode.fromId(r.choice(requests[i])) ?: return@mapNotNull null
                val category = Offline.match(app)?.second ?: offline(app).category
                val reason = "AI · ${((r.confidence ?: 0.0) * 100).toInt()}% sure"
                app.packageName to safe(Suggestion(app.packageName, app.label, mode, category, reason, byModel = true), app)
            }.toMap()

        /**
         * Never let a suggestion remove everything from an app that is clearly personal or critical, and keep
         * "Always show" rare: Smart already keeps people, codes, money and calls on top, while sending that
         * app's promos to Later. Only authenticators are always shown.
         */
        private fun safe(s: Suggestion, app: AppProfile): Suggestion {
            val hint = Offline.match(app)
            val critical = hint?.second in CRITICAL
            val removesAll = s.mode in setOf(AppMode.BLOCK, AppMode.SILENCE, AppMode.BUNDLE)
            return when {
                critical && removesAll -> s.copy(mode = AppMode.AUTO, reason = "Smart: looks essential")
                s.mode == AppMode.ALWAYS_KEEP && hint?.second != Category.SECURITY -> s.copy(mode = AppMode.AUTO, reason = "Smart: important ones stay on top")
                s.mode == AppMode.UNMANAGED && hint?.first != AppMode.UNMANAGED && canNotify(app) -> s.copy(mode = AppMode.AUTO, reason = "AI sorts each one")
                else -> s
            }
        }

        private val CRITICAL = setOf(Category.PERSONAL, Category.SECURITY, Category.FINANCE, Category.CALLS_ALERTS)

        /** Launchable or seen notifying: leaving it unmanaged would let its noise through. */
        private fun canNotify(app: AppProfile) = app.launchable || app.notificationCount > 0

        fun offline(app: AppProfile): Suggestion {
            val (mode, category, reason) = Offline.match(app)?.let { Triple(it.first, it.second, "Offline rule") } ?: when {
                app.notificationCount == 0 && !app.launchable -> Triple(AppMode.UNMANAGED, Category.SYSTEM, "Never notified")
                app.appCategory == "game" -> Triple(AppMode.BLOCK, Category.GAMES, "Game")
                app.appCategory == "news" -> Triple(AppMode.BUNDLE, Category.NEWS, "News app")
                app.appCategory == "social" -> Triple(AppMode.AUTO, Category.SOCIAL, "Social app")
                app.isSystem && app.notificationCount == 0 -> Triple(AppMode.UNMANAGED, Category.SYSTEM, "Quiet system app")
                else -> Triple(AppMode.AUTO, Category.OTHER, "AI sorts each one")
            }
            return safe(Suggestion(app.packageName, app.label, mode, category, reason, byModel = false), app)
        }
    }
}

/** Name-based hints that hold for most phones. Matched against package name and label. */
internal object Offline {
    private val rules: List<Triple<Regex, AppMode, Category>> = listOf(
        Triple(Regex("dialer|telecom|incallui|\\.phone\\b|contacts|messag|\\bsms|mms|whatsapp|telegram|signal|\\bline\\b|viber|wechat|discord|teams|slack|messenger|\\bchat"), AppMode.AUTO, Category.PERSONAL),
        Triple(Regex("authenticator|\\bauthy|2fa|okta|duo|bitwarden|1password|lastpass|keepass"), AppMode.ALWAYS_KEEP, Category.SECURITY),
        Triple(Regex("bank|wallet|\\bpay\\b|spay|paypal|venmo|cashapp|zelle|chase|amex|capitalone|wellsfargo|citi|paytm|phonepe|gpay|upi|card|finance|credit"), AppMode.AUTO, Category.FINANCE),
        Triple(Regex("clock|alarm|calendar|reminder|smartthings|\\bring\\b|\\bnest\\b|samsung\\.android\\.app\\.watchmanager|wearable|findmy|tasks"), AppMode.AUTO, Category.CALLS_ALERTS),
        Triple(Regex("doordash|uber ?eats|ubercab\\.eats|instacart|grubhub|swiggy|zomato|postmates"), AppMode.AUTO, Category.ORDERS),
        Triple(Regex("maps|waze|uber|lyft|airbnb|booking|expedia|tripit|kayak|hopper|transit|united|delta|southwest"), AppMode.AUTO, Category.TRAVEL),
        Triple(Regex("health|fitbit|strava|garmin|oura|whoop|peloton|myfitnesspal"), AppMode.AUTO, Category.HEALTH),
        Triple(Regex("gmail|outlook|\\bmail\\b|email|zoom|webex|jira|notion|drive|docs"), AppMode.AUTO, Category.WORK),
        Triple(Regex("vending|galaxyapps|samsungapps|appstore|themestore|\\.ar(zone|emoji)|bixby|samsung\\.android\\.game|gamelauncher|tips|members|rewards|samsungpass\\.promo"), AppMode.SILENCE, Category.SYSTEM),
        Triple(Regex("amazon|temu|shein|aliexpress|ebay|walmart|target|etsy|flipkart|myntra|meesho|shop|store|deals|coupon|bestbuy|costco"), AppMode.AUTO, Category.ORDERS),
        Triple(Regex("\\bups\\b|fedex|usps|\\bdhl\\b|parcel|tracking"), AppMode.AUTO, Category.ORDERS),
        Triple(Regex("instagram|facebook|\\bfb\\b|tiktok|musically|snapchat|twitter|\\bx\\.android|threads|reddit|pinterest|linkedin|tumblr|bereal"), AppMode.AUTO, Category.SOCIAL),
        Triple(Regex("news|inshorts|googlequicksearchbox|\\.nis\\.app|flipboard|bbc|cnn|nytimes|upday|guardian|reuters|apnews|ndtv|timesofindia|hindustantimes|samsung\\.android\\.app\\.spage|discover|feed"), AppMode.BUNDLE, Category.NEWS),
        Triple(Regex("youtube|netflix|primevideo|hulu|disney|spotify|music|podcast|twitch"), AppMode.BUNDLE, Category.MEDIA),
        Triple(Regex("game|supercell|king\\.com|zynga|playrix|roblox|mihoyo|hoyoverse|candy|clash"), AppMode.BLOCK, Category.GAMES),
        Triple(Regex("keyboard|honeyboard|inputmethod|launcher|wallpaper|io\\.github\\.notisift"), AppMode.UNMANAGED, Category.SYSTEM),
    )

    fun match(app: AppProfile): Pair<AppMode, Category>? = match(app.packageName, app.label)

    fun match(packageName: String, label: String): Pair<AppMode, Category>? {
        val hay = "$packageName $label".lowercase()
        return rules.firstOrNull { it.first.containsMatchIn(hay) }?.let { it.second to it.third }
    }
}
