package io.github.notisift.llm

import io.github.notisift.data.Entry
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * One AI-written line per waiting group ("40 sale posts, nothing addressed to you"), so a whole group can be
 * cleared without reading it. Opt-in: the group's titles and texts go to the user's AI provider. Each group is
 * summarised again only when what it holds changed, and at most every [MIN_GAP_MS].
 */
class GroupSummaries(
    private val complete: suspend (JsonRequest) -> JsonObject?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Line(val signature: Int, val text: String)

    private val lines = ConcurrentHashMap<String, Line>()
    private val asked = ConcurrentHashMap<String, Long>()

    /**
     * [group]'s line, only while it still describes exactly [entries]: a line like "nothing for you" must never sit
     * over messages it didn't read.
     */
    fun cached(group: String, entries: List<Entry>): String? = lines[group]?.takeIf { it.signature == signature(entries) }?.text

    /** True when [entries] differ from what [group]'s line describes and it hasn't been asked for too recently. */
    fun isStale(group: String, entries: List<Entry>): Boolean {
        if (entries.size < MIN_ITEMS) return false
        if (lines[group]?.signature == signature(entries)) return false
        return asked[group]?.let { clock() - it >= MIN_GAP_MS } ?: true
    }

    /** Asks the AI for [group]'s line. Returns true when a new line arrived (the drawer should refresh). */
    suspend fun summarize(group: String, label: String, entries: List<Entry>): Boolean {
        if (!claim(group, entries)) return false
        val text = try {
            complete(request(label, entries))?.let(::parse)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return false
        lines[group] = Line(signature(entries), text)
        return true
    }

    /** Marks [group] as asked, atomically, so two quick refreshes never send two requests. Old groups are forgotten. */
    @Synchronized
    private fun claim(group: String, entries: List<Entry>): Boolean {
        if (!isStale(group, entries)) return false
        if (asked.size > MAX_GROUPS) {
            asked.clear()
            lines.clear()
        }
        asked[group] = clock()
        return true
    }

    companion object {
        const val MIN_ITEMS = 3
        const val MIN_GAP_MS = 5 * 60 * 1000L
        private const val MAX_GROUPS = 60
        private const val MAX_ITEMS = 15
        private const val MAX_LINE = 110

        private fun signature(entries: List<Entry>) = entries.map { it.id }.sorted().hashCode()

        fun request(label: String, entries: List<Entry>) = JsonRequest(
            system = "You summarise a group of phone notifications the user hasn't read. Reply with one short line " +
                "(under 100 characters) saying what they are and whether anything needs the user, e.g. " +
                "\"12 sale posts and 2 group polls, nothing addressed to you\". Never invent details.",
            user = "Group: $label (${entries.size} notifications)\n" + entries.take(MAX_ITEMS).joinToString("\n") { e ->
                "- ${e.appLabel}: ${e.title.take(80)} — ${e.text.replace('\n', ' ').take(120)}"
            },
            schemaName = "group_summary",
            schema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("summary") { put("type", "string") } }
                putJsonArray("required") { add("summary") }
                put("additionalProperties", false)
            },
            maxTokens = 120,
        )

        /** The model's line, on one line and capped; null when it sent nothing usable. */
        fun parse(o: JsonObject): String? = (o["summary"] as? JsonPrimitive)?.contentOrNull
            ?.replace(Regex("\\s+"), " ")?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
            ?.let { if (it.length > MAX_LINE) it.take(MAX_LINE - 1).trimEnd() + "…" else it }
    }
}
