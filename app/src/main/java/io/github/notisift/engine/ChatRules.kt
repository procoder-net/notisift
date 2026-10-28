package io.github.notisift.engine

/** What the user chose for one chat. Conversations are never hidden automatically; only these choices move them. */
enum class ChatMode(val id: String, val label: String) {
    ALWAYS("always", "Always on top"),
    MENTIONS("mentions", "Only when mentioned"),
    MUTE("mute", "Muted"),
    ;

    companion object {
        fun fromId(id: String?): ChatMode? = entries.firstOrNull { it.id == id }
    }
}

/** One saved choice: [chat] is the chat's name as the app shows it. */
data class ChatRule(val packageName: String, val chat: String, val mode: ChatMode)

/** One chat message as stored, enough to spot busy groups. */
data class ChatMessage(val packageName: String, val appLabel: String, val title: String, val text: String)

/** A group chat that put many messages on top recently. */
data class BusyChat(val packageName: String, val appLabel: String, val chat: String, val count: Int)

/** Per-chat choices, keyed by app and chat name (as the chat app shows it, without "(12 messages): Sender"). */
object ChatRules {
    private val marks = Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]")
    private val count = Regex("\\s*\\(\\d+ (?:new )?messages?\\)")
    private val replyWords = Regex("\\b(?:replied to you|mentioned you|tagged you)\\b|(?:^|\\s)@you\\b", RegexOption.IGNORE_CASE)

    /** "Stocks Club (123 messages): ~ SMR" → "Stocks Club". */
    fun chatName(title: String): String {
        val clean = title.replace(marks, "").replace(count, "").trim()
        // Group chats append ": Sender"; a sender name is short, a chat name with a colon in it is rare.
        val colon = clean.indexOf(": ")
        return if (colon > 0 && clean.length - colon <= MAX_SENDER) clean.substring(0, colon).trim() else clean
    }

    fun key(packageName: String, title: String) = "$packageName\u0000${chatName(title).lowercase()}"

    /** Saved choices as written, keeping the chat's own spelling for display. */
    fun rows(raw: String): List<ChatRule> = raw.lines().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size != 3 || parts[1].isBlank() || parts[2].isBlank()) return@mapNotNull null
        ChatMode.fromId(parts[0])?.let { ChatRule(parts[1], parts[2], it) }
    }

    /** Lookup map for the engine: [key] → mode. */
    fun parse(raw: String): Map<String, ChatMode> = rows(raw).associate { key(it.packageName, it.chat) to it.mode }

    fun format(rows: List<ChatRule>): String =
        rows.distinctBy { key(it.packageName, it.chat) }.joinToString("\n") { "${it.mode.id}\t${it.packageName}\t${it.chat.replace('\t', ' ').replace('\n', ' ')}" }

    /** [rows] with [chats] set to [mode], or removed when [mode] is null. */
    fun with(rows: List<ChatRule>, chats: List<Pair<String, String>>, mode: ChatMode?): List<ChatRule> {
        val keys = chats.map { (pkg, title) -> key(pkg, title) }.toSet()
        val kept = rows.filter { key(it.packageName, it.chat) !in keys }
        return if (mode == null) kept else kept + chats.map { (pkg, title) -> ChatRule(pkg, chatName(title), mode) }.distinctBy { key(it.packageName, it.chat) }
    }

    /** True when [text] @mentions one of [names] (full name or first name), or says it replied to / mentioned you. */
    fun mentions(text: String, names: List<String>): Boolean {
        if (replyWords.containsMatchIn(text)) return true
        val lower = text.lowercase()
        return names.map { it.trim().lowercase() }.filter { it.length >= 2 }.any { name ->
            listOf(name, name.substringBefore(' ')).distinct().any { Regex("(?:^|[\\s(])@${Regex.escape(it)}\\b").containsMatchIn(lower) }
        }
    }

    /** WhatsApp-style group rows: "Chat (n messages): Sender", "Chat: Sender", or text starting "~ Sender:". */
    fun looksLikeGroup(title: String, text: String): Boolean {
        val clean = title.replace(marks, "")
        return chatName(clean) != clean.replace(count, "").trim() || text.trimStart().startsWith("~ ")
    }

    /** Group chats with at least [minMessages] messages and no choice yet, busiest first. */
    fun busyGroups(messages: List<ChatMessage>, chosen: Map<String, ChatMode>, minMessages: Int = 5): List<BusyChat> =
        messages.filter { looksLikeGroup(it.title, it.text) }
            .groupBy { key(it.packageName, it.title) }
            .filter { (k, list) -> k !in chosen && list.size >= minMessages }
            .map { (_, list) -> list.first().let { BusyChat(it.packageName, it.appLabel, chatName(it.title), list.size) } }
            .sortedByDescending { it.count }

    private const val MAX_SENDER = 40
}
