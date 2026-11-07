package io.github.angelomarzocchi.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** How far the menu leans towards the finger: by where it is, never past the most, and without a jump. */
class MenuStretchTest {
    private val height = 260f
    private val inside = 6f
    private val most = 16f

    private fun at(y: Float) = menuStretch(y, height, inside, most)

    @Test
    fun noneAtTheMiddleAndInsideAtTheEdges() {
        assertEquals(0f, at(height / 2f), 1e-4f)
        assertEquals(-inside, at(0f), 1e-4f)
        assertEquals(inside, at(height), 1e-4f)
    }

    @Test
    fun aboveTheMiddleTheTopIsPulledUpAndBelowTheBottomDown() {
        assertTrue(at(height * 0.3f) < 0f)
        assertTrue(at(height * 0.7f) > 0f)
    }

    @Test
    fun growsWithTheFingerNeverPastTheMostAndWithoutAJump() {
        var before = at(-400f)
        var y = -400f
        while (y <= height + 400f) {
            val now = at(y)
            assertTrue("not growing at $y", now >= before - 1e-4f)
            assertTrue("a jump at $y", abs(now - before) < 0.6f)
            assertTrue("past the most at $y", abs(now) <= most + 1e-4f)
            before = now
            y += 1f
        }
        assertTrue(at(height + 400f) > most * 0.95f)
    }

    @Test
    fun pastTheEdgeItGivesHalfTheFingersWayAtFirst() {
        assertEquals(inside + 0.5f, at(height + 1f), 0.02f)
    }
}
