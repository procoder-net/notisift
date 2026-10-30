package io.github.notisift.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * "Later at set times": waiting rows appear in the drawer only at the hours the user picked, holding whatever
 * arrived before the latest of those hours. Nothing is lost in between; NotiSift's Later tab always has it all.
 */
object LaterSchedule {
    /** The choices offered in Settings, as stored ("" = whenever something arrives). */
    val PRESETS = listOf("" to "As they arrive", "9,18" to "9 am & 6 pm", "8,13,19" to "8 am, 1 pm & 7 pm", "19" to "7 pm only")

    /** "9,18" → [9, 18]. Anything not an hour of the day is ignored. */
    fun parse(raw: String): List<Int> = raw.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..23 }.distinct().sorted()

    /** The latest delivery time at or before [now]; items posted after it wait for the next one. */
    fun lastSlot(now: Long, hours: List<Int>, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (hours.isEmpty()) return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return listOf(today, today.minusDays(1)).flatMap { day -> hours.map { at(day, it, zone) } }.filter { it <= now }.maxOrNull()
    }

    /** The next delivery time after [now], for the alarm that refreshes the drawer then. */
    fun nextSlot(now: Long, hours: List<Int>, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (hours.isEmpty()) return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return listOf(today, today.plusDays(1)).flatMap { day -> hours.map { at(day, it, zone) } }.filter { it > now }.minOrNull()
    }

    private fun at(day: LocalDate, hour: Int, zone: ZoneId) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
}
