package io.github.notisift.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSuggesterTest {
    private fun t(pkg: String, action: String, n: Int) = DecisionTally(pkg, pkg.uppercase(), action, n)

    @Test fun `consistent apps become fixed rule suggestions, busiest first`() {
        val out = RuleSuggester.suggest(listOf(t("temu", "bundle", 12), t("tos", "keep", 6), t("game", "block", 3), t("game", "silence", 4)), emptyMap())
        assertEquals(listOf("temu", "game", "tos"), out.map { it.packageName })
        assertEquals(AppMode.BUNDLE, out[0].mode)
        assertEquals(AppMode.BLOCK, out[1].mode)
        assertEquals(AppMode.ALWAYS_KEEP, out[2].mode)
    }

    @Test fun `mixed, rare or already fixed apps are not suggested`() {
        val out = RuleSuggester.suggest(
            listOf(t("mixed", "bundle", 9), t("mixed", "keep", 1), t("rare", "bundle", 4), t("fixed", "bundle", 20), t("smart", "bundle", 5)),
            mapOf("fixed" to AppMode.BUNDLE, "smart" to AppMode.AUTO),
        )
        assertEquals(listOf("smart"), out.map { it.packageName })
        assertTrue(out.all { it.times >= RuleSuggester.MIN_TIMES })
    }
}
