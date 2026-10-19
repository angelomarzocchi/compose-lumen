package io.github.angelomarzocchi.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A card's idle tilt: it answers the phone moving, stays within ±6°, settles flat when the phone
 * is held still at any angle, and gives way while something else moves.
 */
class GyroTiltFilterTest {

    private fun GyroTiltFilter.feed(pitch: Float, roll: Float, samples: Int, idle: Boolean = true) =
        repeat(samples) { update(pitch, roll, idle) }

    @Test
    fun `the pose the phone starts in is flat`() {
        val filter = GyroTiltFilter()
        filter.feed(pitch = -40f, roll = 12f, samples = 200)
        assertEquals(0f, filter.rotationX, 1e-3f)
        assertEquals(0f, filter.rotationY, 1e-3f)
    }

    @Test
    fun `a turn of the wrist tilts the card the other way round the same axis`() {
        val filter = GyroTiltFilter()
        filter.feed(pitch = -40f, roll = 0f, samples = 10)
        filter.feed(pitch = -37f, roll = 3f, samples = 10)
        assertTrue("rotationX follows the pitch, inverted: ${filter.rotationX}", filter.rotationX < -1f)
        assertTrue("rotationY follows the roll: ${filter.rotationY}", filter.rotationY > 1f)
    }

    @Test
    fun `never past six degrees`() {
        val filter = GyroTiltFilter()
        filter.feed(pitch = 0f, roll = 0f, samples = 1)
        filter.feed(pitch = -60f, roll = 70f, samples = 30)
        assertTrue(abs(filter.rotationX) <= GyroTiltFilter.TiltLimitDegrees + 1e-3f)
        assertTrue(abs(filter.rotationY) <= GyroTiltFilter.TiltLimitDegrees + 1e-3f)
    }

    @Test
    fun `held still at a new angle it settles back to flat`() {
        val filter = GyroTiltFilter()
        filter.feed(pitch = -40f, roll = 0f, samples = 10)
        filter.feed(pitch = -20f, roll = 15f, samples = 600)
        assertEquals(0f, filter.rotationX, .05f)
        assertEquals(0f, filter.rotationY, .05f)
    }

    @Test
    fun `it follows with the low pass, not in one jump`() {
        val filter = GyroTiltFilter()
        filter.feed(pitch = 0f, roll = 0f, samples = 1)
        filter.update(pitchDegrees = 0f, rollDegrees = 4f, idle = true)
        // One sample in, a tenth of the way (α = 0.1) towards what is by then a little less than 4°.
        assertTrue(filter.rotationY > 0f && filter.rotationY < .5f)
    }

    @Test
    fun `while something else moves the card it eases back to flat`() {
        val filter = GyroTiltFilter()
        filter.feed(pitch = 0f, roll = 0f, samples = 1)
        filter.feed(pitch = 0f, roll = 5f, samples = 8)
        assertTrue(filter.rotationY > 1f)
        filter.feed(pitch = 0f, roll = 5f, samples = 60, idle = false)
        assertEquals(0f, filter.rotationY, .01f)
    }

    @Test
    fun `a roll across the half turn is a small step`() {
        val filter = GyroTiltFilter()
        filter.feed(pitch = 0f, roll = 179f, samples = 5)
        filter.feed(pitch = 0f, roll = -179f, samples = 10)
        assertTrue("2° of roll, not 358°: ${filter.rotationY}", filter.rotationY in 0f..2f)
    }

    @Test
    fun `below a fiftieth of a degree nothing is written, and near flat is flat`() {
        assertEquals(2f, settled(current = 2f, next = 2.01f), 0f)
        assertEquals(2.05f, settled(current = 2f, next = 2.05f), 0f)
        assertEquals(0f, settled(current = .3f, next = .015f), 0f)
    }

    @Test
    fun `after a reset the next pose is the new flat`() {
        val filter = GyroTiltFilter()
        filter.feed(pitch = 0f, roll = 0f, samples = 1)
        filter.feed(pitch = 0f, roll = 5f, samples = 10)
        filter.reset()
        filter.feed(pitch = 30f, roll = -30f, samples = 50)
        assertEquals(0f, filter.rotationX, 1e-3f)
        assertEquals(0f, filter.rotationY, 1e-3f)
    }

    @Test
    fun `a NaN sample is dropped instead of sticking in the filter`() {
        val filter = GyroTiltFilter()
        filter.feed(pitch = 0f, roll = 0f, samples = 1)
        filter.update(pitchDegrees = Float.NaN, rollDegrees = 3f, idle = true)
        filter.feed(pitch = -2f, roll = 3f, samples = 10)
        assertTrue(filter.rotationX.isFinite() && filter.rotationY.isFinite())
    }
}
