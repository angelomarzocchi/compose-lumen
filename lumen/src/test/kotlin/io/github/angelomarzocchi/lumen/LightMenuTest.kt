package io.github.angelomarzocchi.lumen

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The menu under a finger: a tap does the item, a slide does the item where it lifts, a slide off the side
 * does nothing, and a finger that opened the menu by holding something down goes on into it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LightMenuTest {

    @get:Rule
    val rule = createComposeRule()

    private val done = mutableListOf<String>()

    private fun entries(disabled: String? = null): List<LightMenuEntry> = listOf(
        LightMenuHeader("Recipe", "you"),
        item("Favourite"),
        item("Move", trailing = "Travel"),
        item("Share", enabled = disabled != "Share"),
        LightMenuDivider,
        item("Delete", destructive = true),
    )

    private fun item(label: String, trailing: String? = null, enabled: Boolean = true, destructive: Boolean = false) =
        LightMenuItem(label, onClick = { done += label }, trailing = trailing, enabled = enabled, destructive = destructive, testTag = label)

    private fun showMenu(entries: List<LightMenuEntry> = entries()) {
        rule.setContent {
            MaterialTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    LightMenu(entries, Modifier.offset(80.dp, 200.dp).width(236.dp).testTag("menu"), haptics = false)
                }
            }
        }
    }

    @Test
    fun aTapDoesTheItem() {
        showMenu()
        rule.onNodeWithTag("Move").performClick()
        rule.runOnIdle { assertEquals(listOf("Move"), done) }
    }

    @Test
    fun aSlideDoesTheItemWhereItLifts() {
        showMenu()
        val from = rule.onNodeWithTag("Favourite").fetchSemanticsNode().boundsInRoot.center
        val to = rule.onNodeWithTag("Delete").fetchSemanticsNode().boundsInRoot.center
        rule.onNodeWithTag("Favourite").performTouchInput {
            down(center)
            val step = (to.y - from.y) / 10f
            repeat(10) { moveBy(Offset(0f, step)) }
            up()
        }
        rule.runOnIdle { assertEquals(listOf("Delete"), done) }
    }

    @Test
    fun aSlideOffTheSideDoesNothing() {
        showMenu()
        rule.onNodeWithTag("Favourite").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(-30f, 0f)) }
            up()
        }
        rule.runOnIdle { assertTrue("done $done", done.isEmpty()) }
    }

    @Test
    fun aSlidePastTheTopStretchesAndDoesNothing() {
        showMenu()
        rule.onNodeWithTag("Favourite").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, -15f)) }
            up()
        }
        rule.runOnIdle { assertTrue("done $done", done.isEmpty()) }
    }

    @Test
    fun aDisabledItemDoesNothing() {
        showMenu(entries(disabled = "Share"))
        rule.onNodeWithTag("Share").performClick()
        rule.runOnIdle { assertTrue("done $done", done.isEmpty()) }
    }

    @Test
    fun aCheckedItemIsSelected() {
        showMenu(listOf(LightMenuItem("Travel", onClick = {}, checked = true, testTag = "Travel")))
        rule.onNodeWithTag("Travel").assertIsSelected()
    }

    @Test
    fun theFingerThatOpenedTheMenuChoosesWithoutLifting() {
        var open by mutableStateOf(false)
        rule.setContent {
            MaterialTheme {
                val handOff = rememberLightMenuHandOff()
                Box(Modifier.size(400.dp, 800.dp)) {
                    Box(
                        Modifier.offset(100.dp, 600.dp).size(90.dp, 110.dp).testTag("card")
                            .lightMenuHandOff(handOff)
                            .combinedClickable(onLongClick = { open = true }, onClick = {}),
                    )
                    if (open) {
                        LightMenu(entries(), Modifier.offset(80.dp, 300.dp).width(236.dp), handOff = handOff, haptics = false)
                    }
                }
            }
        }
        rule.onNodeWithTag("card").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        assertTrue(open)
        val share = rule.onNodeWithTag("Share").fetchSemanticsNode().boundsInRoot.center
        val card = rule.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot.center
        rule.onNodeWithTag("card").performTouchInput {
            val step = (share - card) / 12f
            repeat(12) { moveBy(step) }
            up()
        }
        rule.runOnIdle { assertEquals(listOf("Share"), done) }
    }

    @Test
    fun liftingOnWhatOpenedTheMenuLeavesItOpenAndDoesNothing() {
        var open by mutableStateOf(false)
        rule.setContent {
            MaterialTheme {
                val handOff = rememberLightMenuHandOff()
                Box(Modifier.size(400.dp, 800.dp)) {
                    Box(
                        Modifier.offset(100.dp, 600.dp).size(90.dp, 110.dp).testTag("card")
                            .lightMenuHandOff(handOff)
                            .combinedClickable(onLongClick = { open = true }, onClick = {}),
                    )
                    if (open) LightMenu(entries(), Modifier.offset(80.dp, 300.dp).width(236.dp), handOff = handOff, haptics = false)
                }
            }
        }
        rule.onNodeWithTag("card").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        rule.onNodeWithTag("card").performTouchInput { up() }
        rule.runOnIdle { assertTrue("done $done", done.isEmpty()) }
        // Still open: a tap now does an item.
        rule.onNodeWithTag("Favourite").performClick()
        rule.runOnIdle { assertEquals(listOf("Favourite"), done) }
    }
}
