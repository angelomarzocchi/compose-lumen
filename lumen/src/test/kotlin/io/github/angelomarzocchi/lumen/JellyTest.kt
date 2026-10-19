package io.github.angelomarzocchi.lumen

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VectorizedSpringSpec
import androidx.compose.animation.core.AnimationVector1D
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The jelly's arithmetic: rebounds felt, the pill's shape, the wall it presses into. */
class JellyTest {

    /** A spring from [from] to [to], sampled every 16 ms: what an `animateTo` block hears. */
    private fun springPath(from: Float, to: Float, damping: Float, stiffness: Float, startVelocity: Float = 0f): List<Pair<Float, Float>> {
        val spec = VectorizedSpringSpec<AnimationVector1D>(damping, stiffness, null)
        val start = AnimationVector1D(from)
        val end = AnimationVector1D(to)
        val v0 = AnimationVector1D(startVelocity)
        return (0..120).map { frame ->
            val t = frame * 16_000_000L
            spec.getValueFromNanos(t, start, end, v0).value to spec.getVelocityFromNanos(t, start, end, v0).value
        }
    }

    @Test
    fun aBouncySpringIsFeltAtEveryReboundEachFainter() {
        val overshoots = mutableListOf<Float>()
        val watch = ReboundWatch(target = 100f) { count, overshoot ->
            assertEquals(overshoots.size + 1, count)
            overshoots += overshoot
        }
        springPath(0f, 100f, damping = 0.35f, stiffness = 600f).forEach { (value, velocity) -> watch.frame(value, velocity) }
        assertTrue("a bouncy spring rebounds more than once: $overshoots", overshoots.size >= 2)
        overshoots.zipWithNext { a, b -> assertTrue("each rebound fainter: $overshoots", b < a) }
    }

    @Test
    fun aSpringWithoutBounceIsNotFelt() {
        var rebounds = 0
        val watch = ReboundWatch(target = 100f) { _, _ -> rebounds++ }
        springPath(0f, 100f, damping = Spring.DampingRatioNoBouncy, stiffness = 600f).forEach { (v, s) -> watch.frame(v, s) }
        assertEquals(0, rebounds)
    }

    @Test
    fun turningRoundBeforeTheTargetIsNotARebound() {
        var rebounds = 0
        val watch = ReboundWatch(target = 100f) { _, _ -> rebounds++ }
        // Heading away from the target at first, then towards it without passing it.
        watch.frame(0f, -50f)
        watch.frame(-5f, -10f)
        watch.frame(-6f, 20f)
        watch.frame(50f, 40f)
        assertEquals(0, rebounds)
    }

    @Test
    fun theStrengthOfABounceIsFloorFullAndASquareRootBetween() {
        assertEquals(0f, bounceStrength(overshoot = 0.5f, full = 20f, floor = 1f), 0f)
        assertEquals(1f, bounceStrength(overshoot = 40f, full = 20f, floor = 1f), 0f)
        assertEquals(0.5f, bounceStrength(overshoot = 5f, full = 20f, floor = 1f), 1e-6f)
    }

    @Test
    fun atRestThePillIsItsTab() {
        val pill = JellyPill(start = 100f, end = 200f, index = 1)
        val frame = pill.frame(trackWidth = 400f, trackHeight = 40f, slack = 8f)
        assertEquals(100f, frame.left, 1e-3f)
        assertEquals(200f, frame.right, 1e-3f)
        assertEquals(0f, frame.top, 1e-3f)
        assertEquals(40f, frame.bottom, 1e-3f)
    }

    @Test
    fun stretchedItThinsAndSqueezedItBulgesWithinItsLimits() = runBlocking {
        val pill = JellyPill(start = 100f, end = 200f, index = 1)
        // The leading edge has run on to the next tab, the trailing one not yet.
        pill.endEdge.snapTo(300f)
        val stretched = pill.frame(trackWidth = 400f, trackHeight = 40f, slack = 8f)
        assertTrue("stretched it thins: ${stretched.height}", stretched.height < 40f)
        assertTrue(stretched.height >= 40f * 0.76f - 1e-3f)

        pill.endEdge.snapTo(160f)
        val squeezed = pill.frame(trackWidth = 400f, trackHeight = 40f, slack = 8f)
        assertTrue("squeezed it bulges: ${squeezed.height}", squeezed.height > 40f)
        assertTrue(squeezed.height <= 40f + 2 * 8f)
    }

    @Test
    fun pastTheEndOfTheTrackItPressesIntoTheWallButNeverThroughIt() = runBlocking {
        val pill = JellyPill(start = 300f, end = 400f, index = 3)
        for (overshoot in listOf(2f, 10f, 50f, 1000f)) {
            pill.endEdge.snapTo(400f + overshoot)
            val frame = pill.frame(trackWidth = 400f, trackHeight = 40f, slack = 8f)
            assertTrue("$overshoot past the end gives ${frame.right - 400f}", frame.right - 400f in 0f..8f)
            assertTrue("and gives less than it was pushed", frame.right - 400f < overshoot)
        }
    }

    @Test
    fun smoothstepEasesAtBothEnds() {
        assertEquals(0f, smoothstep(0.3f, 0.8f, 0.1f), 0f)
        assertEquals(1f, smoothstep(0.3f, 0.8f, 0.9f), 0f)
        assertEquals(0.5f, smoothstep(0f, 1f, 0.5f), 1e-6f)
        assertTrue(smoothstep(0f, 1f, 0.05f) < 0.05f)
    }

    @Test
    fun theSpringsAreTheCalmOnes() {
        // The plate's leading edge bounces a little; its trailing edge does not overshoot noticeably.
        val lead = PlateLeadingEdge as SpringSpec<Float>
        val trail = PlateTrailingEdge as SpringSpec<Float>
        assertTrue(lead.dampingRatio < 1f && trail.dampingRatio > lead.dampingRatio)
        assertTrue(Float.VectorConverter.convertToVector(1f).value == 1f)
    }
}
