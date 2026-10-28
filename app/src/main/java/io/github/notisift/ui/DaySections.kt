package io.github.notisift.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** One calendar day of notifications, newest first. */
data class DaySection<T>(val day: LocalDate, val title: String, val items: List<T>)

/** "Today", "Yesterday", a weekday within the last week, then a short date (with the year if it isn't this year). */
fun dayLabel(time: Long, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    dayLabel(Instant.ofEpochMilli(time).atZone(zone).toLocalDate(), Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), locale)

private fun dayLabel(day: LocalDate, today: LocalDate, locale: Locale): String {
    val daysAgo = ChronoUnit.DAYS.between(day, today)
    return when {
        daysAgo <= 0L -> "Today"
        daysAgo == 1L -> "Yesterday"
        daysAgo < 7L -> day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        day.year == today.year -> day.format(DateTimeFormatter.ofPattern("EEE, MMM d", locale))
        else -> day.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", locale))
    }
}

/** Splits [items] into calendar days, newest day first and newest item first inside each day. */
fun <T> byDay(items: List<T>, time: (T) -> Long, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): List<DaySection<T>> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return items.sortedByDescending(time)
        .groupBy { Instant.ofEpochMilli(time(it)).atZone(zone).toLocalDate() }
        .map { (day, list) -> DaySection(day, dayLabel(day, today, locale), list) }
}

/** [items] split by [key], biggest group first; ties keep first-seen order. */
fun <T, K> biggestFirst(items: List<T>, key: (T) -> K): List<Pair<K, List<T>>> =
    items.groupBy(key).entries.sortedByDescending { it.value.size }.map { it.key to it.value }

/**
 * A drawer row for one group ([key] set), or for everything past the biggest groups ([key] null, [groups] lists
 * what it holds). [items] run group by group, biggest group first.
 */
data class GroupRow<K, T>(val key: K?, val groups: List<Pair<K, List<T>>>) {
    val items: List<T> get() = groups.flatMap { it.second }
}

/** The [max] biggest groups get their own row; anything smaller shares one last row, so no group goes missing. */
fun <T, K> topGroups(items: List<T>, max: Int, key: (T) -> K): List<GroupRow<K, T>> {
    val all = biggestFirst(items, key)
    if (all.size <= max + 1) return all.map { GroupRow(it.first, listOf(it)) }
    return all.take(max).map { GroupRow(it.first, listOf(it)) } + GroupRow(null, all.drop(max))
}

/**
 * Day rows for the drawer, which can only show a few rows per stack: the newest [maxRows] - 1 days get their own
 * row and anything older is merged into one "Earlier" row, so nothing silently falls off the bottom.
 */
fun <T> drawerDays(items: List<T>, time: (T) -> Long, now: Long, maxRows: Int, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): List<DaySection<T>> {
    val days = byDay(items, time, now, zone, locale)
    if (days.size <= maxRows) return days
    val kept = days.take(maxRows - 1)
    val older = days.drop(maxRows - 1)
    return kept + DaySection(older.first().day, "Earlier", older.flatMap { it.items })
}
