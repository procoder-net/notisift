package io.github.notisift.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.homeStats by preferencesDataStore("home_stats")

/** Which Home layout a visit used, so the two can be compared. */
enum class HomeVariant(val id: String) {
    COMPACT("compact"),
    CLASSIC("classic"),
    ;

    companion object {
        fun of(compact: Boolean) = if (compact) COMPACT else CLASSIC
    }
}

/** Times Home was seen, and how many of those visits had at least one action (open, clear, move). */
data class HomeStats(val visits: Int = 0, val acted: Int = 0) {
    val actedPercent: Int get() = if (visits == 0) 0 else minOf(100, acted * 100 / visits)
}

/**
 * The current visit: it keeps the layout it started with, only its first action counts, and it ends when Home goes
 * away (not when the screen merely rotates), so one visit is never counted twice or credited to the wrong layout.
 */
class HomeVisits {
    private var variant: HomeVariant? = null
    private var acted = false

    /** True when this starts a new visit; false when one is already open (e.g. after a rotation). */
    fun start(variant: HomeVariant): Boolean {
        if (this.variant != null) return false
        this.variant = variant
        acted = false
        return true
    }

    fun end() {
        variant = null
    }

    /** The visit's layout when this is its first action; null otherwise or when Home isn't showing. */
    fun act(): HomeVariant? {
        val v = variant ?: return null
        if (acted) return null
        acted = true
        return v
    }
}

/**
 * Home's own measure, kept on this phone and never sent anywhere: whether the layout gets people to act on what
 * waits. Shown in Settings → Advanced next to the switch that picks the layout.
 */
class HomeStatsStore(private val context: Context) {
    val flow: Flow<Map<HomeVariant, HomeStats>> = context.homeStats.data.map { p ->
        HomeVariant.entries.associateWith { HomeStats(p[visits(it)] ?: 0, p[acted(it)] ?: 0) }
    }

    suspend fun visit(variant: HomeVariant) = bump(visits(variant))

    suspend fun act(variant: HomeVariant) = bump(acted(variant))

    private suspend fun bump(key: Preferences.Key<Int>) {
        context.homeStats.edit { it[key] = (it[key] ?: 0) + 1 }
    }

    private fun visits(v: HomeVariant) = intPreferencesKey("${v.id}_visits")
    private fun acted(v: HomeVariant) = intPreferencesKey("${v.id}_acted")
}
