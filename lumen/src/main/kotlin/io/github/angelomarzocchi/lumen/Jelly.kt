package io.github.angelomarzocchi.lumen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * The jelly under a row of tabs: the selected tab's pill, whose two edges travel on their own and
 * bounce, the press that pushes a tab in, and the knock and pop of the mark a pill lands on.
 */

/**
 * Each finger as it lands on a row of jelly tabs and lifts. A press is felt at once, pushes its tab's
 * content in and either leans the pill towards that tab or, on the pill's own tab, pushes the pill in
 * too; the release lets both spring back. The move itself is the click's: see [settleOn].
 *
 * Shared by [LightTabs] and [FloatingNavBar], so that a tab feels the same under the finger wherever
 * it is.
 */
@Composable
internal fun JellyPressEffects(
    pill: JellyPill,
    tabMotions: List<TabMotion>,
    selectedIndex: () -> Int,
    haptics: BounceHaptics?,
    scope: CoroutineScope,
) {
    val density = LocalDensity.current
    // A fast spatial spring, for everything that follows the finger down: a press should land as
    // quickly as the finger does, with only a hint of give.
    val pressSpec = LumenMotion.FastSpatial
    tabMotions.forEachIndexed { index, motion ->
        LaunchedEffect(motion) {
            val tugPx = with(density) { TugDistance.toPx() }
            motion.interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        haptics?.press()
                        scope.launch { motion.squish.pressIn(pressSpec) }
                        val selected = selectedIndex()
                        if (index == selected) {
                            pill.pokedBy = index
                            scope.launch { pill.poke.pressIn(pressSpec) }
                        } else {
                            pill.tuggedBy = index
                            scope.launch { pill.tug.animateTo(if (index > selected) tugPx else -tugPx, pressSpec) }
                        }
                    }

                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        // A cancel is the finger sliding off, usually into a scroll: nothing to feel.
                        val tapped = interaction is PressInteraction.Release
                        scope.launch { if (tapped) motion.squish.release() else motion.squish.cancel() }
                        if (pill.pokedBy == index) {
                            pill.pokedBy = -1
                            scope.launch {
                                if (tapped) {
                                    pill.poke.release { count, overshoot ->
                                        haptics?.bounce(
                                            PokeFeel * bounceStrength(overshoot, PokeFullBounce, PokeBounceFloor),
                                            landing = count == 1,
                                        )
                                    }
                                } else {
                                    pill.poke.cancel()
                                }
                            }
                        }
                        if (pill.tuggedBy == index) {
                            pill.tuggedBy = -1
                            scope.launch { pill.tug.animateTo(0f, TugBack) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Sends the pill to [start]..[end], the edges of tab [index], and lands it there: the leading edge
 * overshoots and rebounds, the mark it lands on is knocked over and pops, and — when a tap sent it —
 * the finger feels every bounce.
 *
 * The first time the pill simply is where it belongs: nothing moved to get there. Nor did anything
 * move when the tab is the one the pill was already sent to and only the tabs shifted under it — a
 * font that landed, a window that changed size.
 */
internal suspend fun JellyPill.settleOn(
    index: Int,
    start: Float,
    end: Float,
    tabMotions: List<TabMotion>,
    haptics: BounceHaptics?,
    scope: CoroutineScope,
    density: Density,
    mirrored: Boolean,
    /** The springs the two edges travel on: see [JellyPill.travel]. */
    lead: AnimationSpec<Float> = LeadingEdge,
    trail: AnimationSpec<Float> = TrailingEdge,
    /** Hears the landing, with how hard it was, as the haptics would feel it: 0 to 1. */
    onLanding: (strength: Float) -> Unit = {},
) {
    if (end <= start) return
    if (!placed || index == destination) {
        snapTo(start, end)
        destination = index
        placed = true
        return
    }
    destination = index
    val tapped = felt.also { felt = false }
    // Clockwise when the pill arrives from the left: it knocks the mark's top away from it.
    val towardsEnd = start > startEdge.value
    val knock = (if (towardsEnd) 1f else -1f) * (if (mirrored) -1f else 1f)
    val fullBounce = with(density) { FullBounce.toPx() }
    val bounceFloor = with(density) { BounceFloor.toPx() }
    val rebounds = travel(start, end, lead, trail) { count, overshoot ->
        val strength = bounceStrength(overshoot, fullBounce, bounceFloor)
        if (count == 1) {
            scope.launch { tabMotions[index].land(knock) }
            onLanding(strength)
        }
        if (tapped) haptics?.bounce(strength, landing = count == 1)
    }
    // With animations turned off nothing bounces, but something still changed under the finger, and
    // that much it should feel.
    if (rebounds == 0 && tapped) haptics?.bounce(1f, landing = true)
}

/**
 * The pill's look: the app's tonal colour with a soft shadow, and the press that pushes it in — lower,
 * squarer, its shadow sinking towards the track. Sized and placed by whoever draws it. Without
 * [squarePressed] it stays round under the press, for a pill that has to keep the shape of what holds it.
 */
internal fun Modifier.jellyPillSurface(pill: JellyPill, color: Color, squarePressed: Boolean = true): Modifier = this
    .graphicsLayer {
        val pressed = pill.poke.value.coerceIn(0f, 1f)
        shape = if (pressed == 0f || !squarePressed) {
            PillShape
        } else {
            RoundedCornerShape(lerp(size.height / 2f, PokeCorner.toPx(), pressed))
        }
        // Pressed in, it sinks towards the track.
        shadowElevation = IndicatorElevation.toPx() * (1f - PokeSink * pressed)
        ambientShadowColor = IndicatorShadow
        spotShadowColor = IndicatorShadow
        clip = true
    }
    .background(color)

/**
 * The selected tab's pill, as a soft thing: two edges that move on their own, a width it would rather
 * be, a lean towards a tab being pressed and a dent from a press on itself. All in the row's own
 * pixels, measured from its start.
 */
@Stable
internal class JellyPill(
    /**
     * Where the pill starts, when the caller already knows: it is then drawn from the first frame
     * instead of the one after, which a bar that comes and goes with the screen would show as a blink.
     */
    start: Float = 0f,
    end: Float = 0f,
    index: Int = -1,
) {
    val startEdge = Animatable(start)
    val endEdge = Animatable(end)

    /** The width the pill is heading for, eased there without overshoot: what a stretch is judged against. */
    val restWidth = Animatable(end - start)

    /** The lean towards a tab under the finger, signed: positive towards the end of the row. */
    val tug = Animatable(0f)

    /** A press on the tab the pill already sits on. */
    val poke = Squish()

    var placed by mutableStateOf(index >= 0 && end > start)

    /** The tab the pill was last sent to. */
    var destination = index

    /** The next move was sent by a tap, so the finger gets to feel it land. */
    var felt = false

    var pokedBy = -1
    var tuggedBy = -1

    suspend fun snapTo(start: Float, end: Float) {
        startEdge.snapTo(start)
        endEdge.snapTo(end)
        restWidth.snapTo(end - start)
    }

    /**
     * Sends the pill to [start]..[end] and returns once it has settled, with the number of times its
     * leading edge rebounded. [onRebound] hears each rebound as it happens, with how far past the
     * target that edge got. [leadSpec] moves the edge on the side of travel, [trailSpec] the other.
     */
    suspend fun travel(
        start: Float,
        end: Float,
        leadSpec: AnimationSpec<Float> = LeadingEdge,
        trailSpec: AnimationSpec<Float> = TrailingEdge,
        onRebound: (count: Int, overshoot: Float) -> Unit,
    ): Int =
        coroutineScope {
            // The edge on the side of travel is the quick one; the other one catches up.
            val towardsEnd = start > startEdge.value
            val lead = if (towardsEnd) endEdge else startEdge
            val leadTarget = if (towardsEnd) end else start
            val trail = if (towardsEnd) startEdge else endEdge
            val trailTarget = if (towardsEnd) start else end
            launch { restWidth.animateTo(end - start, RestWidthSpring) }
            launch { trail.animateTo(trailTarget, trailSpec) }
            val watch = ReboundWatch(leadTarget, onRebound)
            lead.animateTo(leadTarget, leadSpec) { watch.frame(value, velocity) }
            watch.count
        }

    /**
     * Where the pill is drawn in a track [trackWidth] by [trackHeight], and how big. [slack] is the
     * track's own padding: the most the pill may bulge into, or press into at either end. [wallGive]
     * is how stiff the wall at either end is: an edge past it moves 1/[wallGive] as far, at first.
     */
    fun bounds(trackWidth: Int, trackHeight: Int, slack: Float, wallGive: Float = WallGive): IntRect {
        val frame = frame(trackWidth.toFloat(), trackHeight.toFloat(), slack, wallGive)
        return IntRect(frame.left.roundToInt(), frame.top.roundToInt(), frame.right.roundToInt(), frame.bottom.roundToInt())
    }

    /**
     * [bounds], unrounded: for whoever carries the pill on into a space of their own before drawing it.
     * [dentWidth] and [dentHeight] are the shares of its width and height a press all the way in takes
     * off: 0 for a caller that presses the pill in its own way.
     */
    fun frame(
        trackWidth: Float,
        trackHeight: Float,
        slack: Float,
        wallGive: Float = WallGive,
        dentWidth: Float = PokeShrinkWidth,
        dentHeight: Float = PokeShrinkHeight,
    ): Rect {
        val lean = tug.value
        val start = pressIntoWall(startEdge.value + min(lean, 0f), trackWidth, slack, wallGive)
        val end = pressIntoWall(endEdge.value + max(lean, 0f), trackWidth, slack, wallGive)
        val length = (end - start).coerceAtLeast(0f)
        // Stretched it thins, squeezed it bulges: about the same jelly, whatever shape it is in.
        val thickness = (length / restWidth.value.coerceAtLeast(1f)).pow(-StretchThinning)
            .coerceIn(MinThickness, MaxThickness)
        val dent = poke.value
        val width = length * (1f - dentWidth * dent)
        val height = min(trackHeight * thickness * (1f - dentHeight * dent), trackHeight + slack * 2f)
        val left = start + (length - width) / 2f
        val top = (trackHeight - height) / 2f
        return Rect(left, top, left + width, top + height)
    }
}

/**
 * Past either end of the track an edge gives way less and less, and never by more than [slack]: the
 * pill presses into the wall instead of sailing through it.
 */
private fun pressIntoWall(edge: Float, trackWidth: Float, slack: Float, wallGive: Float): Float = when {
    slack <= 0f -> edge.coerceIn(0f, trackWidth)
    edge < 0f -> -slack * (1f - exp(edge / (slack * wallGive)))
    edge > trackWidth -> trackWidth + slack * (1f - exp(-(edge - trackWidth) / (slack * wallGive)))
    else -> edge
}

/**
 * Something a finger presses in: 0 at rest, 1 all the way in, and below 0 for a moment as it springs
 * back out past where it started.
 */
@Stable
internal class Squish {
    private val depth = Animatable(0f)

    val value: Float get() = depth.value

    suspend fun pressIn(spec: AnimationSpec<Float>) {
        depth.animateTo(1f, spec)
    }

    /**
     * Lets go, and it springs back out with a wobble; [onRebound] hears each swing of it.
     *
     * A tap is often over before the press had time to show — inside a scrolling list the press is
     * not even reported until the finger lifts — so a release from not far enough in first goes the
     * rest of the way, quickly. A spring with nothing to spring back from would not be seen at all.
     */
    suspend fun release(onRebound: (count: Int, overshoot: Float) -> Unit = { _, _ -> }) {
        if (depth.value < QuickTapDepth) depth.animateTo(1f, QuickTapPress)
        val watch = ReboundWatch(target = 0f, onRebound = onRebound)
        depth.animateTo(0f, SpringBack) { watch.frame(value, velocity) }
    }

    /** The finger slid off: back out, without a show. */
    suspend fun cancel() {
        depth.animateTo(0f, QuietRelease)
    }
}

/** One tab's share of the motion: the finger on it, its content pressed in, and its mark. */
@Stable
internal class TabMotion(selected: Boolean) {
    val interactionSource = MutableInteractionSource()
    val squish = Squish()
    val markScale = Animatable(if (selected) SelectedIconScale else RestingIconScale)

    /** Degrees, clockwise. */
    val markTilt = Animatable(0f)

    /** How far a word alone is pushed along the row, in dp: positive to the right. */
    val wordShift = Animatable(0f)

    /**
     * The pill has just landed on this tab: the mark is knocked over, clockwise for a [knock] of 1,
     * and pops up past its size, and both spring back. A word alone is only nudged the way the pill
     * came, to the right for a [knock] of 1, and slides back.
     */
    suspend fun land(knock: Float) {
        coroutineScope {
            launch { markScale.animateTo(SelectedIconScale, MarkPop, initialVelocity = MarkPopVelocity) }
            launch { markTilt.animateTo(0f, MarkWobble, initialVelocity = knock * MarkKnockVelocity) }
            launch { wordShift.animateTo(0f, WordNudge, initialVelocity = knock * WordNudgeVelocity) }
        }
    }

    /** Where the mark comes to rest once the tab is lit or goes dark. The bounce on landing is [land]'s. */
    suspend fun settle(selected: Boolean, spec: AnimationSpec<Float>) {
        markScale.animateTo(if (selected) SelectedIconScale else RestingIconScale, spec)
    }
}

/** The mark as the motion has it: knocked over and popping on a landing, a little larger while lit. */
internal fun Modifier.jellyMark(motion: TabMotion): Modifier = graphicsLayer {
    scaleX = motion.markScale.value
    scaleY = motion.markScale.value
    rotationZ = motion.markTilt.value
}

/**
 * A word alone as the motion has it: nudged along the row on a landing, and nothing else. Knocked over
 * and popping as a mark is, a line of text reads as shaken rather than landed on.
 */
internal fun Modifier.jellyWord(motion: TabMotion): Modifier = graphicsLayer {
    translationX = motion.wordShift.value.dp.toPx()
}

/** A tab's content — mark and label as one — pressed in under the finger. */
internal fun Modifier.pressedIn(motion: TabMotion): Modifier = graphicsLayer {
    val scale = 1f - ContentPressDepth * motion.squish.value
    scaleX = scale
    scaleY = scale
}

/**
 * The quick edge: the one the pill is travelling towards. spring(0.5, 700) overshoots by about a
 * sixth, bounces once more where it can still be seen and settles in ≈300 ms — bouncier than
 * Material's expressive fast spatial spring (0.6) on purpose, because here the bounce is the point.
 */
internal val LeadingEdge = spring<Float>(dampingRatio = 0.5f, stiffness = 700f)

/**
 * The edge that follows. spring(0.72, 340) ≈ 420 ms, barely past its mark.
 */
internal val TrailingEdge = spring<Float>(dampingRatio = 0.72f, stiffness = 340f)

private val RestWidthSpring = spring<Float>(dampingRatio = 1f, stiffness = 340f)

/** How much thinner the pill gets as it stretches: thickness is the stretch to the power of minus this. */
private const val StretchThinning = 0.45f
private const val MinThickness = 0.76f
private const val MaxThickness = 1.1f

/** How stiff the wall at either end of the track is: an edge past it moves a quarter as far, at first. */
private const val WallGive = 4f

/** How far the pill leans towards a tab under the finger, before the finger lifts. */
private val TugDistance = 10.dp

/** Back from a lean that came to nothing, with a wobble. */
private val TugBack = spring<Float>(dampingRatio = 0.4f, stiffness = 600f)

/** A pill pressed all the way in: this much narrower, this much lower, corners this tight. */
private const val PokeShrinkWidth = 0.06f
private const val PokeShrinkHeight = 0.14f
private val PokeCorner = 12.dp

/** The share of its shadow a pill pressed all the way in loses. */
private const val PokeSink = 0.7f

/** A tab's icon and label pressed all the way in: this much smaller. */
private const val ContentPressDepth = 0.1f

/** A release from not at least this far in goes the rest of the way first. See [Squish.release]. */
private const val QuickTapDepth = 0.6f
private val QuickTapPress = tween<Float>(durationMillis = 70, easing = FastOutLinearInEasing)

/** Out again with a wobble: spring(0.35, 600) swings about a third past, then a tenth. */
private val SpringBack = spring<Float>(dampingRatio = 0.35f, stiffness = 600f)
private val QuietRelease = spring<Float>(dampingRatio = 1f, stiffness = 800f)

/** The landing's pop: from 1.08 to about 1.25 and back. */
private val MarkPop = spring<Float>(dampingRatio = 0.35f, stiffness = 500f)
private const val MarkPopVelocity = 6f

/** The landing's knock: about 12° over at the first swing, then back and forth to upright. */
private val MarkWobble = spring<Float>(dampingRatio = 0.3f, stiffness = 400f)
private const val MarkKnockVelocity = 360f

/** The nudge a word alone gets: spring(0.55, 500) from 130 dp/s goes about 3 dp over and barely back. */
private val WordNudge = spring<Float>(dampingRatio = 0.55f, stiffness = 500f)
private const val WordNudgeVelocity = 130f

/** The landing is felt in full from an overshoot this size: the pill crossing a whole tab or more. */
private val FullBounce = 20.dp

/** A rebound smaller than this cannot be seen, so it is not felt either. */
private val BounceFloor = 1.dp

/** The same two for a poke, in [Squish] depth. */
private const val PokeFullBounce = 0.4f
private const val PokeBounceFloor = 0.04f

/** A poke is a nudge, not a jump: felt at this share of a landing. */
private const val PokeFeel = 0.7f

/** A soft violet-grey shadow under the pill. */
private val IndicatorShadow = Color(0x38281E50)
private val IndicatorElevation = 3.dp
private val PillShape = RoundedCornerShape(50)

/** The bar's height once the labels stack under the icons. */
private val StackedBarHeight = 64.dp

private const val SelectedIconScale = 1.08f
private const val RestingIconScale = 0.94f
