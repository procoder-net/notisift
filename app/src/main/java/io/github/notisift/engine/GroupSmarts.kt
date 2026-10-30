package io.github.notisift.engine

/** One logged chat message with what the user did with it, enough to spot chats they never read. */
data class ChatActivity(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val opened: Boolean,
)

/** A group chat with many messages this week and none of them opened. */
data class IgnoredChat(val packageName: String, val appLabel: String, val chat: String, val count: Int)

/**
 * Group-chat smarts that need no AI: sale posts and forwards in groups wait in Later, and groups the user never
 * opens are offered "only when mentioned". 1:1 chats are never touched by any of this.
 */
object GroupSmarts {
    /** Messages in a day after which a group with no choice of its own shows only @mentions on top. */
    const val BUSY_PER_DAY = 20

    /** Messages in a week, none opened, before NotiSift asks whether to quiet a group. */
    const val IGNORED_PER_WEEK = 15

    private val promo = Regex(
        "forwarded( many times)?|\\b\\d{1,2}\\s?% ?off\\b|\\b(mega|flash|big|clearance|festive|season) sale\\b|\\bsale (is )?(live|now on|starts|ends)\\b|\\bon sale\\b|\\bdiscount|\\bcoupon|\\bpromo ?code|\\bfree (courses?|webinar|class|demo)\\b|" +
            "\\b(buy|order|shop|book|enrol+|register) now\\b|\\bdm (me )?for (price|details|order)\\b|\\blimited (stock|seats|offer|time)\\b|" +
            "\\bcash ?on ?delivery\\b|\\bwholesale\\b|\\bnew (stock|arrivals?|collection)\\b|\\bjoin (my|our) (channel|group)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val price = Regex("(?:₹|rs\\.?|inr|\\$|€|£)\\s?\\d", RegexOption.IGNORE_CASE)
    private val link = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    /**
     * Rows of the same broadcast emoji ("🔴🔴🔴", "👉👉👉", "📢📢📢"): sellers' habit. Friends' "😂😂😂" or "🔥🔥🔥"
     * are left alone.
     */
    private val shouting = Regex("(🔴|🟢|🟡|🔵|📢|🚨|👉|✅|❗|🛍️?|💥|⚡)\\1{2,}")

    /** A broadcast-style sale or forward: sale words, a price next to a link, or a row of attention emoji. */
    fun isPromo(text: String): Boolean =
        promo.containsMatchIn(text) || (price.containsMatchIn(text) && link.containsMatchIn(text)) || shouting.containsMatchIn(text)

    /** Groups with [IGNORED_PER_WEEK]+ messages this week and none opened, without a choice yet, busiest first. */
    fun ignoredGroups(history: List<ChatActivity>, chosen: Map<String, ChatMode>, skipped: Set<String>): List<IgnoredChat> =
        history.filter { ChatRules.looksLikeGroup(it.title, it.text) }
            .groupBy { ChatRules.key(it.packageName, it.title) }
            .filter { (k, list) -> k !in chosen && k !in skipped && list.size >= IGNORED_PER_WEEK && list.none { it.opened } }
            .map { (_, list) -> list.first().let { IgnoredChat(it.packageName, it.appLabel, ChatRules.chatName(it.title), list.size) } }
            .sortedByDescending { it.count }

    /** Keys the user said "Not now" to, one per line. */
    fun parseSkipped(raw: String): Set<String> = raw.lines().filter { it.isNotBlank() }.toSet()

    fun formatSkipped(keys: Collection<String>): String = keys.distinct().takeLast(MAX_SKIPPED).joinToString("\n")

    private const val MAX_SKIPPED = 200
}
