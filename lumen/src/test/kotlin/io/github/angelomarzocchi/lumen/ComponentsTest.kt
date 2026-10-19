package io.github.angelomarzocchi.lumen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
 * The components under a finger: a tap picks, a slide picks where it lifts, a slide off the island picks
 * nothing, and the screen reader hears tabs. Haptics are off: Robolectric has no vibrator to feel them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComponentsTest {

    @get:Rule
    val rule = createComposeRule()

    private val navItems = listOf("Home", "Search", "Saved", "You").map {
        FloatingNavItem(label = it, icon = ColorPainter(Color.Gray), testTag = "nav-$it")
    }

    private var navSelected by mutableIntStateOf(0)
    private val navPicks = mutableListOf<Int>()
    private val navState = FloatingNavBarState(folded = false)
    private var navFolded by mutableStateOf(false)

    private fun showNavBar(width: Int = 400, fontScale: Float = 1f) {
        rule.setContent {
            MaterialTheme {
                Box(Modifier.size(width.dp, 300.dp)) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, fontScale),
                    ) {
                        FloatingNavBar(
                            items = navItems,
                            selectedIndex = navSelected,
                            onSelect = {
                                navPicks += it
                                navSelected = it
                            },
                            folded = navFolded,
                            state = navState,
                            haptics = false,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .testTag("bar"),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun aTapOnTheNavBarPicksThatTab() {
        showNavBar()
        rule.onNodeWithTag("nav-Saved").performClick()
        rule.runOnIdle {
            assertEquals(listOf(2), navPicks)
            assertEquals(2, navSelected)
        }
        rule.onNodeWithTag("nav-Saved").assertIsSelected()
    }

    @Test
    fun aSlideAlongTheBarPicksTheTabWhereItLifts() {
        showNavBar()
        val home = rule.onNodeWithTag("nav-Home").fetchSemanticsNode().boundsInRoot.center
        val you = rule.onNodeWithTag("nav-You").fetchSemanticsNode().boundsInRoot.center
        rule.onNodeWithTag("nav-Home").performTouchInput {
            down(center)
            // From Home to You, in steps, as a finger slides.
            val step = (you.x - home.x) / 10f
            repeat(10) { moveBy(Offset(step, 0f)) }
            up()
        }
        rule.runOnIdle { assertEquals(listOf(3), navPicks) }
    }

    @Test
    fun aSlideOffTheIslandPicksNothing() {
        showNavBar()
        rule.onNodeWithTag("nav-Home").performTouchInput {
            down(center)
            moveBy(Offset(20f, 0f))
            // Far above the island: the light goes out.
            repeat(10) { moveBy(Offset(0f, -20f)) }
            up()
        }
        rule.runOnIdle { assertTrue("picked $navPicks", navPicks.isEmpty()) }
    }

    @Test
    fun folding() {
        showNavBar()
        rule.runOnIdle { assertEquals(0f, navState.foldFraction, 0f) }
        navFolded = true
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(1f, navState.foldFraction, 0f)
            assertTrue("the top came down: ${navState.drop}", navState.drop > 0f)
        }
        navFolded = false
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(0f, navState.drop, 0.5f) }
    }

    @Test
    fun theSelectedTabCanBeFoundInTheWindow() {
        showNavBar()
        rule.runOnIdle {
            val tab = navState.tabInWindow(1)!!
            val cell = rule.onNodeWithTag("nav-Search").fetchSemanticsNode().boundsInRoot
            assertTrue("$tab inside $cell", tab.left >= cell.left - 1f && tab.right <= cell.right + 1f)
        }
    }

    @Test
    fun whereTheLabelsCannotFitTheyGoToTheScreenReader() {
        showNavBar(width = 220, fontScale = 2f)
        rule.onNodeWithTag("nav-Search").assert(
            SemanticsMatcher("says its label") {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Search") == true
            },
        )
        rule.runOnIdle {
            // No labels to make room for: there is nothing to fold away.
            navFolded = true
        }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(0f, navState.drop, 0.5f) }
    }

    // ---- LightTabs ----------------------------------------------------------------------------

    private var tabSelected by mutableStateOf<Any>("day")
    private val tabPicks = mutableListOf<Any>()

    private fun showTabs() {
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LightTabs(
                        tabs = listOf(
                            LightTab(id = "day", title = "Day"),
                            LightTab(id = "week", title = "Week"),
                            LightTab(id = "month", title = "Month", description = "The whole month"),
                        ),
                        selectedId = tabSelected,
                        onSelect = {
                            tabPicks += it
                            tabSelected = it
                        },
                        haptics = false,
                        modifier = Modifier.width(360.dp),
                        fillWidth = true,
                    )
                }
            }
        }
    }

    @Test
    fun aTapOnALightTabPicksIt() {
        showTabs()
        rule.onNodeWithText("Week").performClick()
        rule.runOnIdle { assertEquals(listOf<Any>("week"), tabPicks) }
        rule.onNodeWithText("Week").assertIsSelected()
    }

    @Test
    fun aSlideAcrossTheTabsPicksWhereItLifts() {
        showTabs()
        val day = rule.onNodeWithText("Day").fetchSemanticsNode().boundsInRoot.center
        val month = rule.onNodeWithText("Month", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        rule.onNodeWithText("Day").performTouchInput {
            down(center)
            val step = (month.x - day.x) / 10f
            repeat(10) { moveBy(Offset(step, 0f)) }
            up()
        }
        rule.runOnIdle { assertEquals(listOf<Any>("month"), tabPicks) }
    }

    @Test
    fun aTabsDescriptionIsWhatTheScreenReaderSays() {
        showTabs()
        rule.onNodeWithText("Month").assert(
            SemanticsMatcher("described") {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("The whole month") == true
            },
        )
    }

    // ---- GlossCard ----------------------------------------------------------------------------

    @Test
    fun aTapOnAGlossCardClicksAndADragDoesNot() {
        var clicks = 0
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    GlossCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .size(300.dp, 160.dp)
                            .testTag("card"),
                        onClick = { clicks++ },
                    ) {
                        Text("Card")
                    }
                }
            }
        }
        rule.onNodeWithTag("card").performClick()
        rule.runOnIdle { assertEquals(1, clicks) }
        rule.onNodeWithTag("card").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(8f, 4f)) }
            up()
        }
        rule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun aFingerOffCentreTiltsTheCardTowardsItAndLiftingSettlesIt() {
        val tilt = CardTilt()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    GlossCard(
                        tilt = tilt,
                        modifier = Modifier.size(300.dp, 160.dp).testTag("card"),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                        colors = GlossCardDefaults.Violet,
                        tiltOnTouch = true,
                        tiltWithDevice = false,
                        onClick = null,
                    ) {}
                }
            }
        }
        // Near the top left corner: the top comes towards the finger (rotationX > 0) and the left
        // side too (rotationY < 0), and the gloss slides there.
        rule.onNodeWithTag("card").performTouchInput { down(Offset(10f, 10f)) }
        rule.mainClock.advanceTimeBy(500)
        rule.runOnIdle {
            assertTrue("tilted towards the top: ${tilt.x}", tilt.x > 5f)
            assertTrue("tilted towards the left: ${tilt.y}", tilt.y < -5f)
            assertTrue("gloss under the finger: ${tilt.glossX}", tilt.glossX < 0.2f)
        }
        rule.onNodeWithTag("card").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(3_000)
        rule.runOnIdle {
            assertEquals(0f, tilt.x, 0.01f)
            assertEquals(0f, tilt.y, 0.01f)
        }
    }
}
