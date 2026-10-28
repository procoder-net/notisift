package io.github.notisift.engine

/** What happens to a notification. Ordered from least to most intrusive. */
enum class Action(val id: String, val label: String) {
    KEEP("keep", "Show"),
    BUNDLE("bundle", "Later"),
    SILENCE("silence", "Junk (quiet)"),
    BLOCK("block", "Junk"),
    ;

    companion object {
        fun fromId(id: String?): Action? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }
    }
}

/** Where a notification lives: important ones stay in the shade; everything removed lives only in NotiSift. */
enum class Tier(val id: String, val label: String, val emoji: String) {
    IMPORTANT("important", "Important", "⭐"),
    LATER("later", "Later", "🕓"),
    JUNK("junk", "Junk", "🗑️"),
    ;

    companion object {
        fun of(action: Action): Tier = when (action) {
            Action.KEEP -> IMPORTANT
            Action.BUNDLE -> LATER
            Action.SILENCE, Action.BLOCK -> JUNK
        }

        fun of(actionId: String): Tier = of(Action.fromId(actionId) ?: Action.KEEP)
        fun fromId(id: String?): Tier? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Notification groups: the AI's answer space and the user's per-group choices. Like jev's category config, each
 * one carries what the AI needs (a description with examples) and NotiSift's default; the user's choices are
 * layered on top (see [CategoryChoices]). Optional groups are only offered when the phone has matching apps,
 * which keeps the AI's options short and personal. Protected groups can never be sent to Later or Junk by a
 * group choice.
 */
enum class Category(
    val id: String,
    val label: String,
    val emoji: String,
    val description: String,
    val protected: Boolean = false,
    /** Package/label pattern of apps that make this group relevant; null = always offered. */
    val apps: Regex? = null,
) {
    PERSONAL("personal", "People & messages", "💬", "A real person wrote to the user: chats, texts, DMs, personal email", protected = true),
    SECURITY("security", "Codes & security", "🔐", "Verification codes, sign-in alerts, password and security warnings", protected = true),
    FINANCE("finance", "Money & bills", "💳", "Money moving or owed: payments, transfers, bills due, bank alerts, refunds", protected = true),
    CALLS_ALERTS("alerts", "Calls, alarms & reminders", "⏰", "Calls, missed calls, alarms, calendar events and reminders", protected = true),
    ORDERS("orders", "Orders & deliveries", "📦", "Order confirmations, shipping, out for delivery, delivered, pickups"),
    WORK("work", "Work", "💼", "Meetings, invites, work chat, tickets, documents shared with the user"),
    SCHOOL("school", "School", "🎓", "Assignments, grades, teacher and school announcements",
        apps = Regex("classroom|aeries|canvas|schoology|remind|seesaw|powerschool|clever|class ?dojo|blackboard|parentsquare|edu", RegexOption.IGNORE_CASE)),
    HOME("home", "Smart home & car", "🏠", "Cameras and doorbells (motion, person detected), locks, thermostats, vacuums, car status",
        apps = Regex("wyze|hualai|\\bring\\b|nest|arlo|blink|eufy|smartthings|tesla|ecobee|tuya|hue|roborock|irobot|august|myq|kasa|tplink|homeassistant|rivian|mybmw|fordpass", RegexOption.IGNORE_CASE)),
    MARKETS("markets", "Stocks & crypto", "📈", "Stock and crypto price alerts, market moves, watchlists, trade fills",
        apps = Regex("thinkorswim|devexperts|webull|robinhood|etrade|fidelity|schwab|coinbase|binance|kraken|moomoo|tradingview|stocktwits|yahoo.*finance|public\\.com|sofi", RegexOption.IGNORE_CASE)),
    TRAVEL("travel", "Travel & places", "✈️", "Flights, boarding, rides, hotels, bookings, maps and place reviews",
        apps = Regex("maps|waze|uber|lyft|airbnb|booking|expedia|united|delta|american ?airlines|southwest|tripit|kayak|hopper|transit", RegexOption.IGNORE_CASE)),
    HEALTH("health", "Health & fitness", "❤️", "Workouts, steps, sleep, heart rate, medication and wellness reminders",
        apps = Regex("fitbit|health|strava|garmin|shealth|myfitnesspal|oura|whoop|peloton|calm|headspace|medisafe", RegexOption.IGNORE_CASE)),
    SOCIAL("social", "Social activity", "👥", "Likes, follows, comments, mentions, friend suggestions, memories"),
    NEWS("news", "News, weather & sports", "📰", "Headlines, breaking news, weather, scores and sports updates"),
    MEDIA("media", "Video & music", "🎬", "New episodes and videos, live streams, playlists, podcasts",
        apps = Regex("youtube|netflix|spotify|hulu|disney|primevideo|twitch|podcast|music|plex|\\bmax\\b|hbo|pandora|audible|tiktok", RegexOption.IGNORE_CASE)),
    PROMOTION("promotion", "Promotions & ads", "🏷️", "Sales, deals, coupons, marketing, rewards and offers"),
    SYSTEM("system", "Updates & system", "⚙️", "App updates, backups, storage, battery, sync and app tips"),
    GAMES("games", "Games", "🎮", "Game rewards, energy refills, events and reminders to play"),
    OTHER("other", "Other", "•", "Anything that fits none of the other groups"),
    ;

    companion object {
        fun fromId(id: String?): Category = entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: OTHER

        /**
         * The groups worth offering on this phone: every always-on group, plus optional ones that match an
         * installed app ([appNames] are package names and labels) or that already have notifications.
         */
        fun relevant(appNames: Collection<String>, seen: Set<Category> = emptySet()): List<Category> =
            entries.filter { c -> c.apps == null || c in seen || appNames.any { c.apps.containsMatchIn(it) } }
    }
}

/** The user's per-group choice, layered over NotiSift's defaults like jev's category overrides. */
enum class GroupChoice(val id: String, val tier: Tier?) {
    AI("ai", null), IMPORTANT("important", Tier.IMPORTANT), LATER("later", Tier.LATER), JUNK("junk", Tier.JUNK);

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: AI
    }
}

object CategoryChoices {
    /** Parses `news=later;home=important`. Unknown groups or choices are ignored, never fatal. */
    fun parse(text: String): Map<Category, GroupChoice> = text.split(';').mapNotNull { part ->
        val (k, v) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        val c = Category.entries.firstOrNull { it.id == k.trim() } ?: return@mapNotNull null
        c to GroupChoice.fromId(v.trim())
    }.filter { (c, choice) -> choice != GroupChoice.AI && allowed(c, choice) }.toMap()

    fun format(choices: Map<Category, GroupChoice>): String =
        choices.filter { it.value != GroupChoice.AI }.entries.joinToString(";") { "${it.key.id}=${it.value.id}" }

    /** Protected groups (people, codes, money, calls) can be pinned on top but never hidden by a group choice. */
    fun allowed(c: Category, choice: GroupChoice) = !c.protected || choice == GroupChoice.AI || choice == GroupChoice.IMPORTANT

    fun Tier.action(): Action = when (this) {
        Tier.IMPORTANT -> Action.KEEP
        Tier.LATER -> Action.BUNDLE
        Tier.JUNK -> Action.BLOCK
    }
}

/** How an app is managed. AUTO lets the pipeline decide per notification. */
enum class AppMode(val id: String, val label: String) {
    UNMANAGED("off", "Leave alone"),
    ALWAYS_KEEP("keep", "Always show"),
    AUTO("auto", "Smart"),
    BUNDLE("bundle", "Always later"),
    SILENCE("silence", "Always junk (quiet)"),
    BLOCK("block", "Always junk"),
    ;

    companion object {
        fun fromId(id: String?): AppMode? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }
    }
}

enum class Source(val label: String) {
    PROTECTED("Protected"), OTP("Code detected"), KEYWORD("Your keyword rule"), APP_POLICY("App policy"),
    DUPLICATE("Duplicate"), CORRECTION("Your correction"), CACHE("Learned"), MODEL("AI model"),
    HEURISTIC("Offline rules"), FALLBACK("Kept: uncertain"), INBOX("Inbox: not important"), GROUP("Your group choice"),
    CHAT("Your chat choice"),
}

/** Android-free snapshot of a posted notification. */
data class NotificationInput(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val channelId: String = "",
    val androidCategory: String? = null,
    val title: String = "",
    val text: String = "",
    val subText: String = "",
    val isOngoing: Boolean = false,
    val isClearable: Boolean = true,
    val isGroupSummary: Boolean = false,
    val isMessagingStyle: Boolean = false,
    val hasMediaSession: Boolean = false,
    /** Android 15+ hid this notification's content from NotiSift (codes); only the original can show it. */
    val isSensitive: Boolean = false,
    /** The user's own name in this chat app (MessagingStyle), used to spot @mentions. */
    val selfName: String = "",
    val postedAt: Long = System.currentTimeMillis(),
) {
    /** Exact identity of what the user sees; full string, so no hash collisions. */
    val contentKey: String get() = "$packageName\u0000$title\u0000$text"

    /** Nothing to judge: no text and a title that is empty or just the app's name (e.g. "thinkorswim"). */
    val isUnreadable: Boolean get() = text.isBlank() && subText.isBlank() && (title.isBlank() || title.trim().equals(appLabel.trim(), ignoreCase = true))

    /** Template key: digits and long tokens collapsed so "Order #123 shipped" matches "Order #456 shipped". */
    val templateKey: String get() = "$packageName|$channelId|${normalize(title)}|${normalize(text).take(60)}"

    companion object {
        private val digits = Regex("\\d+")
        private val space = Regex("\\s+")
        fun normalize(s: String) = s.lowercase().replace(digits, "#").replace(space, " ").trim()
    }
}

data class Verdict(
    val action: Action,
    val category: Category,
    val confidence: Double,
    val source: Source,
    val reason: String = "",
)

data class KeywordRule(val keyword: String, val action: Action) {
    companion object {
        /** Parses lines such as `flash sale => block` or `invoice => keep`. Invalid lines are ignored. */
        fun parse(text: String): List<KeywordRule> = text.lines().mapNotNull { line ->
            val parts = line.split("=>", "->").map { it.trim() }
            if (parts.size != 2 || parts[0].isBlank()) return@mapNotNull null
            Action.fromId(parts[1])?.let { KeywordRule(parts[0].lowercase(), it) }
        }

        fun format(rules: List<KeywordRule>): String = rules.joinToString("\n") { "${it.keyword} => ${it.action.id}" }
    }
}
