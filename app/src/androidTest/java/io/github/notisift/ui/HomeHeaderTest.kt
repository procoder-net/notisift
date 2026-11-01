package io.github.notisift.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.notisift.engine.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeHeaderTest {
    @get:Rule val rule = createComposeRule()

    @Test fun compactStatusSaysItAllInOneLineWithLargeButtons() {
        var cleaned = 0
        rule.setContent { MaterialTheme { CompactStatus(onTop = 2, movedToday = 14, where = "Built-in rules, on this phone", onClean = { cleaned++ }, more = emptyList()) } }
        rule.onNodeWithContentDescription("Sorting. 2 on top, 14 moved today. Built-in rules, on this phone").assertIsDisplayed()
        rule.onNodeWithContentDescription("Clean again").assertTouchTargetAtLeast48().performClick()
        rule.onNodeWithContentDescription("More choices").assertTouchTargetAtLeast48()
        assertEquals(1, cleaned)
    }

    @Test fun aiBannerOffersAddAndNotNow() {
        var added = false
        var dismissed = false
        rule.setContent { MaterialTheme { AiKeyBanner(onAddKey = { added = true }, onNotNow = { dismissed = true }) } }
        rule.onNodeWithText("Add key").assertTouchTargetAtLeast48().performClick()
        rule.onNodeWithContentDescription("Not now").assertTouchTargetAtLeast48().performClick()
        assertTrue(added && dismissed)
    }

    @Test fun tierBarShowsCountsAndSwitches() {
        var picked = ""
        rule.setContent {
            MaterialTheme {
                TierBar(Tier.LATER, cleared = false, counts = mapOf(Tier.LATER to 7, Tier.IMPORTANT to 2), clearedCount = 3, pinned = true, onTier = { picked = it })
            }
        }
        rule.onNodeWithText("Later 7", substring = true).assertIsSelected()
        rule.onNodeWithText("Important 2", substring = true).performClick()
        assertEquals(Tier.IMPORTANT.id, picked)
    }
}

/** Material draws small buttons at 40dp but makes them 48dp to touch; that touch area is what accessibility needs. */
private fun SemanticsNodeInteraction.assertTouchTargetAtLeast48(): SemanticsNodeInteraction {
    val node = fetchSemanticsNode()
    val min = with(node.layoutInfo.density) { 48f * density }
    val touch = node.touchBoundsInRoot
    assertTrue("touch target ${touch.width}x${touch.height}px, need ${min}px", touch.width >= min - 1 && touch.height >= min - 1)
    return this
}
