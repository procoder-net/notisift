package io.github.notisift.ui

import io.github.notisift.engine.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeViewTest {
    @Test
    fun `each tier maps to its actions`() {
        assertEquals(listOf("keep"), HomeView.actionsOf(Tier.IMPORTANT))
        assertEquals(listOf("bundle"), HomeView.actionsOf(Tier.LATER))
        assertEquals(listOf("silence", "block"), HomeView.actionsOf(Tier.JUNK))
    }

    @Test
    fun `like pattern matches anywhere and treats wildcards literally`() {
        assertEquals("%pizza%", HomeView.likePattern("  pizza "))
        assertEquals("%50\\% off%", HomeView.likePattern("50% off"))
        assertEquals("%a\\_b%", HomeView.likePattern("a_b"))
        assertEquals("%c:\\\\x%", HomeView.likePattern("c:\\x"))
    }

    @Test
    fun `search wins over the tier and the cleared list`() {
        val searching = HomeView(view = HomeView.CLEARED, search = "otp")
        assertTrue(searching.searching)
        assertFalse(searching.cleared)
        assertNull(searching.tier)
        assertTrue(HomeView(view = HomeView.CLEARED).cleared)
        assertEquals(Tier.JUNK, HomeView(view = "junk").tier)
        assertFalse(HomeView(search = "   ").searching)
    }
}
