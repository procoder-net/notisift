package io.github.notisift.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class DaySectionsTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()
    private val now = at(2026, 10, 27, 9, 30) // Tuesday

    private fun label(t: Long) = dayLabel(t, now, zone, Locale.US)

    @Test fun `same calendar day is Today even just after midnight`() {
        assertEquals("Today", label(at(2026, 10, 27, 0, 5)))
    }

    @Test fun `previous calendar day is Yesterday even late at night`() {
        assertEquals("Yesterday", label(at(2026, 10, 26, 23, 59)))
        assertEquals("Yesterday", label(at(2026, 10, 26, 0, 1)))
    }

    @Test fun `within the last week shows the weekday`() {
        assertEquals("Saturday", label(at(2026, 10, 24)))
        assertEquals("Wednesday", label(at(2026, 10, 21)))
    }

    @Test fun `older this year shows short date, other years add the year`() {
        assertEquals("Tue, Oct 20", label(at(2026, 10, 20)))
        assertEquals("Wed, Dec 31, 2025", label(at(2025, 12, 31)))
    }

    @Test fun `byDay keeps newest day first and order inside each day`() {
        val items = listOf(at(2026, 10, 27, 9), at(2026, 10, 27, 8), at(2026, 10, 26, 22), at(2026, 10, 20))
        val sections = byDay(items, { it }, now, zone, Locale.US)
        assertEquals(listOf("Today", "Yesterday", "Tue, Oct 20"), sections.map { it.title })
        assertEquals(listOf(items[0], items[1]), sections[0].items)
        assertEquals(1, sections[2].items.size)
    }

    @Test fun `byDay sorts unsorted input newest first`() {
        val items = listOf(at(2026, 10, 20), at(2026, 10, 27, 8), at(2026, 10, 27, 9))
        val sections = byDay(items, { it }, now, zone, Locale.US)
        assertEquals(listOf("Today", "Tue, Oct 20"), sections.map { it.title })
        assertEquals(listOf(items[2], items[1]), sections[0].items)
    }

    @Test fun `empty input gives no sections`() {
        assertEquals(emptyList<DaySection<Long>>(), byDay(emptyList<Long>(), { it }, now, zone, Locale.US))
    }

    @Test fun `drawerDays keeps the newest days and merges the rest into Earlier`() {
        val items = (0..8).map { at(2026, 10, 27 - it, 10) } + at(2026, 10, 27, 8)
        val rows = drawerDays(items, { it }, now, maxRows = 5, zone = zone, locale = Locale.US)
        assertEquals(listOf("Today", "Yesterday", "Sunday", "Saturday", "Earlier"), rows.map { it.title })
        assertEquals(2, rows[0].items.size)
        assertEquals(5, rows[4].items.size)
    }

    @Test fun `drawerDays does not add Earlier when everything fits`() {
        val items = listOf(at(2026, 10, 27, 8), at(2026, 10, 26, 8))
        assertEquals(listOf("Today", "Yesterday"), drawerDays(items, { it }, now, maxRows = 5, zone = zone, locale = Locale.US).map { it.title })
    }

    @Test fun `biggestFirst orders groups by size`() {
        val groups = biggestFirst(listOf("news", "shop", "news", "chat", "news", "shop")) { it }
        assertEquals(listOf("news" to 3, "shop" to 2, "chat" to 1), groups.map { it.first to it.second.size })
    }

    @Test fun `topGroups gives the biggest groups a row and shares one row for the rest`() {
        val items = List(5) { "news" } + List(4) { "chat" } + List(3) { "shop" } + listOf("film", "trip")
        val rows = topGroups(items, max = 2) { it }
        assertEquals(listOf("news", "chat", null), rows.map { it.key })
        assertEquals(listOf("shop", "film", "trip"), rows[2].groups.map { it.first })
        assertEquals(5, rows[2].items.size)
        assertEquals(items.size, rows.sumOf { it.items.size })
    }

    @Test fun `topGroups never makes a More row for just one extra group`() {
        val rows = topGroups(listOf("a", "a", "b", "c"), max = 2) { it }
        assertEquals(listOf("a", "b", "c"), rows.map { it.key })
    }
}
