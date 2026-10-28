package io.github.notisift.engine

/** How often the AI (or what it taught the cache) sent one app's notifications to one action. */
data class DecisionTally(val packageName: String, val appLabel: String, val action: String, val total: Int)

/** "The AI always does X for this app": offered to the user as a fixed rule that needs no AI at all. */
data class RuleSuggestion(val packageName: String, val appLabel: String, val tier: Tier, val times: Int) {
    val mode: AppMode get() = when (tier) {
        Tier.IMPORTANT -> AppMode.ALWAYS_KEEP
        Tier.LATER -> AppMode.BUNDLE
        Tier.JUNK -> AppMode.BLOCK
    }
}

object RuleSuggester {
    /** Enough identical calls in a row that a fixed rule would decide the same way, instantly and for free. */
    const val MIN_TIMES = 5

    /**
     * Apps whose AI decisions all landed in one tier at least [MIN_TIMES] times. Apps the user already gave a
     * fixed choice ([fixedModes], anything but Smart) are skipped. Busiest first.
     */
    fun suggest(tallies: List<DecisionTally>, fixedModes: Map<String, AppMode?>): List<RuleSuggestion> =
        tallies.groupBy { it.packageName }.mapNotNull { (pkg, rows) ->
            val mode = fixedModes[pkg]
            if (mode != null && mode != AppMode.AUTO) return@mapNotNull null
            val byTier = rows.groupBy { Tier.of(it.action) }.mapValues { (_, r) -> r.sumOf { it.total } }
            val (tier, times) = byTier.entries.singleOrNull()?.toPair() ?: return@mapNotNull null
            if (times < MIN_TIMES) return@mapNotNull null
            RuleSuggestion(pkg, rows.first().appLabel, tier, times)
        }.sortedByDescending { it.times }
}
