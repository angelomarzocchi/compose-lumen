package io.github.angelomarzocchi.lumen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * The island a row of tabs sits in, and what it does under a finger: the light the finger carries, the
 * slide from one tab to the next, the island's ends giving way to the plate and its squash on a landing.
 *
 * Shared by [FloatingNavBar] and [LightTabs], so that every row of tabs is the same thing to look at
 * and to touch.
 */

/**
 * The light a finger carries across a row of tabs: where it is, in the island's pixels, how bright, and
 * how lit each tab is. Read in draw blocks only — all but [litIndex], which tints the marks.
 */
@Stable
internal class IslandLight(count: Int, held: Int = -1, at: Offset = Offset.Zero) {
    val x = Animatable(at.x)
    val y = Animatable(at.y)

    /** The glow round the finger: 1 while it is on the island, 0 once it has gone. */
    val on = Animatable(if (held >= 0) 1f else 0f)

    /** How lit each tab is. One at a time heads for 1; the one it took over from fades out. */
    val lit = List(count) { Animatable(if (it == held) 1f else 0f) }

    /** The tab under the finger, or -1. */
    var litIndex by mutableIntStateOf(held)
        private set

    /** The tab the keyboard is on, or -1: lit the same way, and put out when the keyboard moves on. */
    var focused = -1

    fun moveTo(at: Offset, scope: CoroutineScope, glide: Boolean) {
        scope.launch { if (glide) x.animateTo(at.x, LightFollow) else x.snapTo(at.x) }
        scope.launch { if (glide) y.animateTo(at.y, LightFollow) else y.snapTo(at.y) }
    }

    /** Lights tab [index] — quickly, the light should be there as soon as the finger is — or none, for -1. */
    fun light(index: Int, scope: CoroutineScope) {
        litIndex = index
        scope.launch { on.animateTo(if (index >= 0) 1f else 0f, if (index >= 0) LightIn else LightOut) }
        lit.forEachIndexed { i, amount ->
            scope.launch { amount.animateTo(if (i == index) 1f else 0f, if (i == index) LightIn else LightOut) }
        }
    }

    /** Reached from the keyboard, a tab is lit as if a finger were on it, at [center]. */
    fun focus(index: Int, focused: Boolean, center: Offset, scope: CoroutineScope) {
        if (focused) {
            this.focused = index
            moveTo(center, scope, glide = on.value > 0f)
            light(index, scope)
        } else if (this.focused == index) {
            this.focused = -1
            light(-1, scope)
        }
    }
}

/**
 * The light, under the plate and the marks: a glow that spills round the finger, and the tab it is on
 * lit up, brightest under the finger.
 *
 * The lit tab takes its plate ([plate], from the island's top left), the shape the selection settles
 * into. At either end of the row it is then the island's own curve, moved in — not a rounded cell laid
 * across the island's end.
 *
 * Where the selected tab's plate is, [selection], the light goes on the plate instead
 * ([drawPlateLight]): a lit tab gives way under the plate as the plate covers it, so a plate pressed in
 * or arriving never shows a white ring of another shape round itself.
 *
 * [reach] is how far the light reaches, a tab's width; [overhang] how far past its own bounds the
 * island may have been pushed out, which the spill covers too.
 */
internal fun DrawScope.drawIslandLight(
    light: IslandLight,
    look: FingerLight,
    plate: (index: Int) -> Rect,
    reach: Float,
    selection: Rect,
    overhang: Float,
) {
    // A gradient of no radius is an error on Android: an island not yet measured has no light.
    if (reach <= 0f) return
    val center = Offset(light.x.value, light.y.value)
    val on = light.on.value
    if (on > 0f) {
        drawRect(
            Brush.radialGradient(
                0f to Color.White.copy(alpha = look.spill * on),
                1f to Color.Transparent,
                center = center,
                radius = reach * SpillReach,
            ),
            topLeft = Offset(-overhang, 0f),
            size = Size(size.width + 2 * overhang, size.height),
        )
    }
    light.lit.forEachIndexed { index, amount ->
        if (amount.value <= 0f) return@forEachIndexed
        val rest = plate(index)
        if (rest.width <= 0f) return@forEachIndexed
        val covered = (min(rest.right, selection.right) - max(rest.left, selection.left)) / rest.width
        val lit = amount.value * (1f - smoothstep(CoverFrom, CoverTo, covered))
        if (lit <= 0f) return@forEachIndexed
        drawRoundRect(
            brush = Brush.radialGradient(
                0f to Color.White.copy(alpha = look.core * lit),
                1f to Color.White.copy(alpha = look.edge * lit),
                center = center,
                radius = reach,
            ),
            topLeft = rest.topLeft,
            size = rest.size,
            cornerRadius = CornerRadius(rest.height / 2f),
        )
    }
}

/**
 * The same light where it falls on the selected tab's plate, which would otherwise hide it: the spill,
 * and the tab lit under the finger when it is this one ([index]), at [FingerLight.onPlate] of their
 * strength. [origin] is where the plate is this frame, from the island's top left; the plate clips it.
 */
internal fun DrawScope.drawPlateLight(
    light: IslandLight,
    look: FingerLight,
    reach: Float,
    origin: Offset,
    index: Int,
) {
    if (reach <= 0f) return
    val center = Offset(light.x.value, light.y.value) - origin
    val on = light.on.value
    if (on > 0f) {
        drawRect(
            Brush.radialGradient(
                0f to Color.White.copy(alpha = look.spill * look.onPlate * on),
                1f to Color.Transparent,
                center = center,
                radius = reach * SpillReach,
            ),
        )
    }
    val lit = light.lit.getOrNull(index)?.value ?: 0f
    if (lit > 0f) {
        drawRect(
            Brush.radialGradient(
                0f to Color.White.copy(alpha = look.core * look.onPlate * lit),
                1f to Color.White.copy(alpha = look.edge * look.onPlate * lit),
                center = center,
                radius = reach,
            ),
        )
    }
}

/**
 * The island takes every touch itself, rather than each tab its own: a tab's click would end the moment
 * the finger left it, and the finger has to be free to slide from one tab to the next.
 *
 * Put down, the finger lights the tab under it; slid along the row, the light goes with it, lighting
 * each tab it crosses — the pill leaning that way, a tick under the finger at every one — and lifting
 * picks the tab that is lit, as iOS's tab bar picks the one its glass lens is over. Slid off the island
 * the light goes out, and lifting then picks nothing.
 *
 * [cellAt] is the tab at a distance from the island's left edge, and [onChosen] hears the one picked:
 * both are asked during the gesture, so they should read what they need through state rather than
 * capture it. [key] restarts the gesture handling, for what they do capture.
 *
 * In a page that scrolls ([yieldsToScroll]) a finger that sets off up or down is the page's: the light
 * goes out and nothing is picked. The press — the tick and the tab sinking — waits until the finger is
 * plainly on the tabs, so a scroll that starts on them is not felt as one.
 */
internal fun Modifier.slideToSelect(
    light: IslandLight,
    motions: List<TabMotion>,
    scope: CoroutineScope,
    cellAt: (x: Float) -> Int,
    onChosen: (index: Int) -> Unit,
    yieldsToScroll: Boolean = false,
    key: Any? = null,
): Modifier = pointerInput(light, motions, yieldsToScroll, key) {
    val letGo = LetGoMargin.toPx()
    val slop = viewConfiguration.touchSlop

    // The tab under the finger, or none once the finger has wandered off the island.
    fun cellUnder(at: Offset): Int {
        val off = at.y < -letGo || at.y > size.height + letGo ||
            at.x < -letGo || at.x > size.width + letGo
        return if (off) -1 else cellAt(at.x).coerceIn(0, motions.size - 1)
    }

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!yieldsToScroll) down.consume()
        var index = -1
        var press: PressInteraction.Press? = null
        // Outside a scroll the finger presses as soon as it lands; inside one, once it has shown it
        // means the tabs.
        var pressing = !yieldsToScroll
        var sliding = !yieldsToScroll

        fun pressAt(at: Offset) {
            if (index >= 0) press = PressInteraction.Press(at).also { motions[index].interactionSource.tryEmit(it) }
        }

        // Into another tab is a press on it and a quiet let-go of the one before: the tab under the
        // finger sinks, the pill leans its way, and the finger feels each one.
        fun hover(at: Offset) {
            val next = cellUnder(at)
            if (next == index) return
            press?.let { motions[index].interactionSource.tryEmit(PressInteraction.Cancel(it)) }
            press = null
            index = next
            light.light(next, scope)
            if (pressing) pressAt(at)
        }

        // Still fading from the touch before, the light glides over rather than jumping.
        light.moveTo(down.position, scope, glide = light.on.value > 0f)
        hover(down.position)
        var lifted = false
        while (true) {
            val event = if (pressing) {
                awaitPointerEvent()
            } else {
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis / PressWaitShare) { awaitPointerEvent() }
            }
            if (event == null) {
                // Held still on the tabs: that is a press, not a scroll.
                pressing = true
                pressAt(down.position)
                continue
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                // A gesture the system takes away ends in a lift that is already consumed: nothing
                // was chosen.
                lifted = !change.isConsumed
                change.consume()
                // A tap quicker than the wait: pressed and let go at once, which the tab's spring
                // still shows (see Squish.release).
                if (lifted && !pressing) {
                    pressing = true
                    pressAt(change.position)
                }
                break
            }
            // Something under the page took the gesture first: it is a scroll.
            if (change.isConsumed) break
            if (!sliding) {
                val travel = change.position - down.position
                if (abs(travel.y) > slop && abs(travel.y) > abs(travel.x)) break
                if (abs(travel.x) > slop) {
                    sliding = true
                    if (!pressing) {
                        pressing = true
                        pressAt(change.position)
                    }
                }
            }
            if (sliding) {
                if (change.positionChange() != Offset.Zero) {
                    light.moveTo(change.position, scope, glide = true)
                    hover(change.position)
                }
                change.consume()
            }
        }

        val chosen = if (lifted) index else -1
        press?.let {
            motions[index].interactionSource.tryEmit(
                if (chosen >= 0) PressInteraction.Release(it) else PressInteraction.Cancel(it),
            )
        }
        light.light(-1, scope)
        if (chosen >= 0) onChosen(chosen)
    }
}

/** A capsule, its two ends pushed out past its bounds by [left] and [right] pixels. */
internal data class PushedCapsule(val left: Float, val right: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(
            RoundRect(
                left = -left,
                top = 0f,
                right = size.width + right,
                bottom = size.height,
                cornerRadius = CornerRadius(size.height / 2f),
            ),
        )
}

/** Eased at both ends between [from] and [to], so nothing pops in or cuts out. */
internal fun smoothstep(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * The plate's two edges. Calmer than the bare pill's ([LeadingEdge], [TrailingEdge]), because a plate
 * is a whole tab wide and its bounce reads as far too much: spring(0.72, 650) passes
 * its mark by about 4% of the way it came and turns back once, which is still the landing the mark is
 * knocked by and the finger feels; the trailing edge arrives without passing it.
 */
internal val PlateLeadingEdge = spring<Float>(dampingRatio = 0.72f, stiffness = 650f)
internal val PlateTrailingEdge = spring<Float>(dampingRatio = 0.9f, stiffness = 380f)

/**
 * Between an island's edge and the plates inside it, all the way round: what makes a plate concentric
 * with the island.
 */
internal val PlateInset = 4.dp

/** A press on the selected tab pushes its plate in by this much all round: smaller, still round. */
internal val PokeInset = 2.5.dp

/** A raised island's shadow. */
internal val IslandShadow = 6.dp

/**
 * The most an island may be pushed out at either end by its plate, or widened by a squash: about 2% of
 * an open navigation bar on a phone, enough to see and too little to read as the island coming loose.
 */
internal val MaxGive = 8.dp

/**
 * How stiff an island's ends are under the plate: a plate past the end of the row moves them 1/1.5 as
 * far, at first, and less and less as they near the most they may give. Softer than the bare pill's own
 * wall (4), because here the wall is the point.
 */
internal const val ContainerGive = 1.5f

/** A landing squashes the island by this share of its height at the hardest, from the top down. */
internal const val SquashShare = 0.035f
internal val SquashIn = tween<Float>(durationMillis = 70, easing = FastOutSlowInEasing)

/** Back up from a squash: spring(0.55, 500) passes its height by about an eighth of the squash, once. */
internal val SquashOut = spring<Float>(dampingRatio = 0.55f, stiffness = 500f)

/** A lit tab starts giving way to the plate once the plate covers this share of it, and is gone at the second. */
private const val CoverFrom = 0.3f
private const val CoverTo = 0.85f

/** The light follows the finger as a card's gloss does. */
private val LightFollow = LumenMotion.Follow
private val LightIn = tween<Float>(durationMillis = 90)
private val LightOut = tween<Float>(durationMillis = 320)

/** How far the glow spills round the finger, in tabs. */
private const val SpillReach = 1.1f

/** How far off the island a finger may wander and still be on it. Past this the light goes out. */
private val LetGoMargin = 48.dp

/**
 * In a page that scrolls, a finger held still on the tabs for this share of a long press is pressing
 * them: about 100 ms, Android's own tap timeout.
 */
private const val PressWaitShare = 4
