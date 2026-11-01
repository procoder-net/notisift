package io.github.notisift.engine

import io.github.notisift.data.Entry
import io.github.notisift.llm.JsonRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** What [MoveUp] needs from the database. */
interface MoveUpStore {
    suspend fun laterSince(since: Long): List<Entry>
    /** Of [ids], the ones still waiting in Later (not cleared or moved since). */
    suspend fun stillWaiting(ids: List<Long>): Set<Long>
    suspend fun setDecision(id: Long, action: String, source: String)
    /** True when the user moved this kind of notification back down before. */
    suspend fun movedBackBefore(templateKey: String): Boolean
    suspend fun rememberMovedBack(entry: Entry)
}

/** What [MoveUp] needs from the drawer. */
interface MoveUpDrawer {
    suspend fun showOnTop(entry: Entry)
    suspend fun takeOffTop(entry: Entry)
    fun refresh()
}

/**
 * "Important on top", the steps around [Rescue]: pick from today's Later, ask the phone's AI, move up only what is
 * still waiting, and undo for good.
 */
class MoveUp(private val store: MoveUpStore, private val drawer: MoveUpDrawer, private val clock: () -> Long = System::currentTimeMillis) {
    /** Returns what was moved up, as it was before (for the undo card). */
    suspend fun run(ask: suspend (JsonRequest) -> JsonObject): List<Entry> {
        val now = clock()
        val items = Rescue.candidates(store.laterSince(now - DAY_MS), now).filter { !store.movedBackBefore(it.templateKey) }
        if (items.isEmpty()) return emptyList()
        val answer = try {
            withTimeoutOrNull(TIMEOUT_MS) { ask(Rescue.request(items)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val parsed = Rescue.parse(answer, items)
        // The user may have cleared or moved some while the AI was reading: only what is still waiting moves.
        val waiting = store.stillWaiting(parsed.map { it.id })
        val picked = parsed.filter { it.id in waiting }
        picked.forEach { e ->
            store.setDecision(e.id, Action.KEEP.id, Source.RESCUED.name)
            drawer.showOnTop(e.copy(action = Action.KEEP.id, source = Source.RESCUED.name))
        }
        if (picked.isNotEmpty()) drawer.refresh()
        return picked
    }

    /** Puts [entries] back as they were, and that kind of notification is never moved up again. */
    suspend fun moveBack(entries: List<Entry>) {
        entries.forEach { e ->
            store.setDecision(e.id, e.action, e.source)
            drawer.takeOffTop(e)
            store.rememberMovedBack(e)
        }
        drawer.refresh()
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val TIMEOUT_MS = 30_000L
    }
}
