package io.github.notisift.engine

import io.github.notisift.data.Entry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueTest {
    private val now = 10L * 24 * 60 * 60 * 1000
    private fun later(id: Long, source: Source = Source.HEURISTIC, category: Category = Category.ORDERS, ago: Long = id * 60_000, template: String = "t$id") = Entry(
        id = id, key = "k$id", packageName = "p", appLabel = "Shop", channelId = "", title = "Title $id", text = "Text $id", postedAt = now - ago,
        category = category.id, action = Action.BUNDLE.id, source = source.name, confidence = 1.0, reason = "", templateKey = template, applied = true,
    )

    @Test fun `only what NotiSift decided on its own today is looked at, newest first, one per kind`() {
        val list = listOf(
            later(1), later(2, source = Source.APP_POLICY), later(3, source = Source.CORRECTION), later(4, source = Source.KEYWORD),
            later(5, category = Category.PERSONAL), later(6, ago = 2L * 24 * 60 * 60 * 1000), later(7, template = "t1"), later(8, source = Source.MODEL),
            later(9, category = Category.PROMOTION), later(10, category = Category.NEWS),
        )
        assertEquals(listOf(1L, 8L), Rescue.candidates(list, now).map { it.id })
    }

    @Test fun `numbers map to notifications, outside ones are ignored, and at most three move`() {
        val items = (1L..6L).map { later(it) }
        val o = Json.parseToJsonElement("""{"numbers":[2,99,2,0,5,6,1]}""").jsonObject
        assertEquals(listOf(2L, 5L, 6L), Rescue.parse(o, items).map { it.id })
        assertEquals(emptyList<Entry>(), Rescue.parse(Json.parseToJsonElement("""{"numbers":[]}""").jsonObject, items))
    }

    @Test fun `the prompt fences notifications off as data and expects few picks`() {
        val r = Rescue.request(listOf(later(1)))
        assertTrue("<notifications>" in r.user && "1. Shop: Title 1" in r.user)
        assertTrue("empty list is normal" in r.system)
    }
}
