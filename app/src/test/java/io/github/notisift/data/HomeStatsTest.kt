package io.github.notisift.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeStatsTest {
    @Test fun `only the first action of a visit counts`() {
        val visits = HomeVisits()
        assertNull("nothing counts before Home is seen", visits.act())
        assertEquals(true, visits.start(HomeVariant.COMPACT))
        assertEquals(HomeVariant.COMPACT, visits.act())
        assertNull(visits.act())
        visits.end()
        visits.start(HomeVariant.COMPACT)
        assertEquals("a new visit counts again", HomeVariant.COMPACT, visits.act())
    }

    @Test fun `a visit keeps the layout it started with`() {
        val visits = HomeVisits()
        visits.start(HomeVariant.CLASSIC)
        assertEquals("switching layout mid-visit doesn't split it", HomeVariant.CLASSIC, visits.act())
    }

    @Test fun `actions after leaving Home don't count`() {
        val visits = HomeVisits()
        visits.start(HomeVariant.COMPACT)
        visits.end()
        assertNull("an undo from another tab is not a Home action", visits.act())
    }

    @Test fun `resuming an open visit is not a new one`() {
        val visits = HomeVisits()
        visits.start(HomeVariant.COMPACT)
        assertEquals(HomeVariant.COMPACT, visits.act())
        assertEquals("rotation resumes the same visit", false, visits.start(HomeVariant.COMPACT))
        assertNull(visits.act())
    }

    @Test fun `share of visits with an action`() {
        assertEquals(0, HomeStats().actedPercent)
        assertEquals(25, HomeStats(visits = 8, acted = 2).actedPercent)
        assertEquals(100, HomeStats(visits = 1, acted = 3).actedPercent)
    }

    @Test fun `variants follow the compact flag`() {
        assertEquals(HomeVariant.COMPACT, HomeVariant.of(compact = true))
        assertEquals(HomeVariant.CLASSIC, HomeVariant.of(compact = false))
    }
}
