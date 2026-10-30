package io.github.notisift.ui

import io.github.notisift.engine.Action
import io.github.notisift.engine.Tier

/**
 * What Home lists: one tier, the Cleared list, or search results across every tier. [limit] grows with
 * "Show more", so a busy phone's two weeks of history never silently falls off the end.
 */
data class HomeView(val view: String = Tier.LATER.id, val search: String = "", val limit: Int = PAGE) {
    val searching: Boolean get() = search.isNotBlank()
    val cleared: Boolean get() = !searching && view == CLEARED
    val tier: Tier? get() = if (searching) null else Tier.fromId(view)

    companion object {
        const val CLEARED = "cleared"
        const val PAGE = 300
        const val MAX_SEARCH = 100

        fun actionsOf(tier: Tier): List<String> = Action.entries.filter { Tier.of(it) == tier }.map { it.id }

        /** A LIKE pattern matching [query] anywhere, with `%`, `_` and `\` taken literally (the DAO escapes with `\`). */
        fun likePattern(query: String): String = io.github.notisift.data.Like.contains(query)
    }
}
