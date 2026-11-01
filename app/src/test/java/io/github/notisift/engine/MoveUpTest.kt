package io.github.notisift.engine

import io.github.notisift.data.Entry
import io.github.notisift.llm.JsonRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class MoveUpFakeStore(entries: List<Entry>) : MoveUpStore {
    val rows = entries.associateBy { it.id }.toMutableMap()
    val movedBack = mutableSetOf<String>()
    override suspend fun laterSince(since: Long) = rows.values.filter { it.action == Action.BUNDLE.id && !it.dismissed && it.postedAt >= since }
    override suspend fun stillWaiting(ids: List<Long>) = ids.filter { rows[it]?.let { e -> e.action == Action.BUNDLE.id && !e.dismissed } == true }.toSet()
    override suspend fun setDecision(id: Long, action: String, source: String) { rows[id]?.let { rows[id] = it.copy(action = action, source = source) } }
    override suspend fun movedBackBefore(templateKey: String) = templateKey in movedBack
    override suspend fun rememberMovedBack(entry: Entry) { movedBack += entry.templateKey }
}

private class MoveUpFakeDrawer : MoveUpDrawer {
    val onTop = mutableListOf<String>()
    var refreshes = 0
    override suspend fun showOnTop(entry: Entry) { onTop += entry.key }
    override suspend fun takeOffTop(entry: Entry) { onTop -= entry.key }
    override fun refresh() { refreshes++ }
}

class MoveUpTest {
    private val now = 10L * 24 * 60 * 60 * 1000
    private fun later(id: Long) = Entry(
        id = id, key = "k$id", packageName = "p", appLabel = "Airline", channelId = "", title = "Flight $id", text = "Gate changed",
        postedAt = now - id * 60_000, category = Category.TRAVEL.id, action = Action.BUNDLE.id, source = Source.HEURISTIC.name,
        confidence = 1.0, reason = "", templateKey = "t$id", applied = true,
    )
    private fun picks(vararg n: Int): suspend (JsonRequest) -> JsonObject = { Json.parseToJsonElement("""{"numbers":[${n.joinToString(",")}]}""").jsonObject }

    @Test fun `what the AI picks moves up and the card gets it as it was`() = runTest {
        val store = MoveUpFakeStore(listOf(later(1), later(2)))
        val drawer = MoveUpFakeDrawer()
        val moved = MoveUp(store, drawer) { now }.run(picks(1))
        assertEquals(listOf(later(1)), moved)
        assertEquals(Source.RESCUED.name, store.rows.getValue(1).source)
        assertEquals(Action.KEEP.id, store.rows.getValue(1).action)
        assertEquals(listOf("k1"), drawer.onTop)
        assertEquals(1, drawer.refreshes)
    }

    @Test fun `putting it back restores it and that kind never moves up again`() = runTest {
        val store = MoveUpFakeStore(listOf(later(1)))
        val drawer = MoveUpFakeDrawer()
        val moveUp = MoveUp(store, drawer) { now }
        val moved = moveUp.run(picks(1))
        moveUp.moveBack(moved)
        assertEquals(later(1), store.rows.getValue(1))
        assertTrue(drawer.onTop.isEmpty())
        var asked = false
        assertEquals(emptyList<Entry>(), moveUp.run { asked = true; picks(1)(it) })
        assertEquals("nothing left to ask about", false, asked)
    }

    @Test fun `one the user cleared while the AI was reading stays cleared`() = runTest {
        val store = MoveUpFakeStore(listOf(later(1), later(2)))
        val drawer = MoveUpFakeDrawer()
        val moved = MoveUp(store, drawer) { now }.run { r ->
            store.rows[1] = store.rows.getValue(1).copy(dismissed = true)
            picks(1, 2)(r)
        }
        assertEquals(listOf(2L), moved.map { it.id })
        assertEquals(listOf("k2"), drawer.onTop)
    }

    @Test fun `a failing or empty answer changes nothing`() = runTest {
        val store = MoveUpFakeStore(listOf(later(1)))
        val drawer = MoveUpFakeDrawer()
        assertEquals(emptyList<Entry>(), MoveUp(store, drawer) { now }.run { throw IllegalStateException("BACKGROUND_USE_BLOCKED") })
        assertEquals(emptyList<Entry>(), MoveUp(store, drawer) { now }.run(picks()))
        assertEquals(later(1), store.rows.getValue(1))
        assertEquals(0, drawer.refreshes)
    }
}
