package io.github.angelomarzocchi.lumen

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * One action of [LightActions]: its [label] under its [icon], drawn at the bar's mark size in the colour
 * the bar hands it. A disabled action is dimmed, and a finger lifted on it does nothing.
 */
@Immutable
public class LightAction(
    public val label: String,
    public val icon: Painter,
    public val onClick: () -> Unit,
    public val enabled: Boolean = true,
    public val testTag: String? = null,
)

/**
 * A row of actions in an island: [FloatingNavBar]'s island and its light, with no selection — nothing
 * here is a place the user is in, every cell is something to do.
 *
 * The same thing to touch as the bar: put down, the finger lights the action under it; slid along the
 * island the light goes with it, the action under it sinking and a tick at every one; lifting does the
 * action that is lit, and the action springs back out with a wobble. Slid off the island the light goes
 * out, and lifting then does nothing.
 *
 * It is as big as [modifier] makes it, a capsule cut into equal cells; the labels shrink to 10sp before
 * they are cut. It casts the bar's shadow only without a [material]: a material is the island's look,
 * shadow and all.
 *
 * @param actions the actions, two to five.
 * @param colors the island's and the marks' colours ([LumenColors.plate] is unused: there is no plate).
 * @param labelStyle the labels' style, before they shrink to fit.
 * @param markSize the side of each action's icon.
 * @param material draws the island instead of its flat colour — glass, say: see [IslandMaterial]. Its
 *   `selection` is always empty.
 * @param haptics whether the finger feels presses and the spring back.
 */
@Composable
public fun LightActions(
    actions: List<LightAction>,
    modifier: Modifier = Modifier,
    colors: LumenColors = LumenDefaults.colors(),
    labelStyle: TextStyle = MaterialTheme.typography.labelMedium,
    markSize: Dp = 24.dp,
    material: IslandMaterial? = null,
    haptics: Boolean = true,
) {
    if (actions.isEmpty()) return
    val currentActions by rememberUpdatedState(actions)
    val motions = remember(actions.size) { List(actions.size) { TabMotion(selected = false) } }
    val light = remember(actions.size) { IslandLight(actions.size) }
    val bounceHaptics = rememberBounceHaptics().takeIf { haptics }
    // Not the effects' own scopes: a spring back that has started plays out even if the cell is pressed again.
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
    val inset = with(density) { PlateInset.toPx() }

    // What the finger does to a cell: it sinks, and springs back out with a wobble the finger feels.
    motions.forEachIndexed { index, motion ->
        LaunchedEffect(motion) {
            motion.interactionSource.interactions.collect { interaction ->
                if (currentActions.getOrNull(index)?.enabled != true) return@collect
                when (interaction) {
                    is PressInteraction.Press -> {
                        bounceHaptics?.press()
                        scope.launch { motion.squish.pressIn(LumenMotion.FastSpatial) }
                    }
                    is PressInteraction.Release -> scope.launch {
                        motion.squish.release { count, overshoot ->
                            bounceHaptics?.bounce(ReleaseFeel * bounceStrength(overshoot, ReleaseFullBounce, ReleaseBounceFloor), landing = count == 1)
                        }
                    }
                    is PressInteraction.Cancel -> scope.launch { motion.squish.cancel() }
                }
            }
        }
    }

    fun act(index: Int) {
        val action = currentActions.getOrNull(index) ?: return
        if (action.enabled) action.onClick()
    }

    val dark = colors.island.luminance() < 0.5f
    val look = colors.light
    val lights = remember { IslandLights() }

    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val cell = (width - 2 * inset) / actions.size

        fun fromLeft(index: Int): Int = if (mirrored) actions.lastIndex - index else index

        /** Action [index]'s cell at rest, from the island's top left: the capsule a finger lights. */
        fun restPlate(index: Int): Rect {
            val left = inset + fromLeft(index) * cell
            return Rect(left, inset, left + cell, height - inset)
        }

        fun lightsOn(size: Size): IslandLights = lights.apply {
            lamp = Offset(light.x.value, light.y.value)
            lampOn = light.on.value
            selection = Rect.Zero
            if (tabLit.size != light.lit.size) tabLit = FloatArray(light.lit.size)
            this.tabs = List(light.lit.size) { restPlate(it) }
            for (i in tabLit.indices) tabLit[i] = light.lit[i].value
            accent = colors.lit
            this.dark = dark
        }

        Row(
            Modifier
                .size(maxWidth, maxHeight)
                .then(material?.modifier ?: Modifier)
                .graphicsLayer {
                    shape = PushedCapsule(0f, 0f)
                    clip = true
                    if (material == null) shadowElevation = IslandShadow.toPx()
                }
                .drawBehind {
                    if (material != null) {
                        with(material) { drawIsland(Rect(Offset.Zero, size), size.height / 2f, 0f, lightsOn(size)) }
                        return@drawBehind
                    }
                    drawRect(colors.island)
                    drawIslandLight(light = light, look = look, plate = ::restPlate, reach = cell, selection = Rect.Zero, overhang = 0f)
                }
                .then(
                    if (material != null) {
                        Modifier.graphicsLayer {
                            val shape = Rect(Offset.Zero, size)
                            renderEffect = material.contentEffect(this, shape, size.height / 2f, lightsOn(size))
                        }
                    } else {
                        Modifier
                    },
                )
                .slideToSelect(
                    light = light,
                    motions = motions,
                    scope = scope,
                    cellAt = { x -> fromLeft((((x - inset) / cell).toInt()).coerceIn(0, actions.lastIndex)) },
                    onChosen = ::act,
                    key = Triple(mirrored, width, height),
                ),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEachIndexed { index, action ->
                val tint by animateColorAsState(
                    targetValue = when {
                        !action.enabled -> colors.content.copy(alpha = colors.content.alpha * DisabledAlpha)
                        index == light.litIndex -> colors.lit
                        else -> colors.content
                    },
                    animationSpec = LumenMotion.effects(),
                    label = "lightActionTint",
                )
                Column(
                    Modifier
                        .width(with(density) { cell.toDp() })
                        .fillMaxHeight()
                        .then(action.testTag?.let { Modifier.testTag(it) } ?: Modifier)
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            contentDescription = action.label
                            if (!action.enabled) disabled()
                            onClick {
                                act(index)
                                true
                            }
                        }
                        .onFocusChanged { light.focus(index, it.isFocused, restPlate(index).center, scope) }
                        .onKeyEvent { event ->
                            val activates = event.type == KeyEventType.KeyUp && event.key in ActivationKeys
                            if (activates) act(index)
                            activates
                        }
                        .focusable(enabled = action.enabled)
                        .pressedIn(motions[index]),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(LabelGap, Alignment.CenterVertically),
                ) {
                    Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(markSize))
                    Box(Modifier.width(with(density) { (cell - 2 * inset).toDp() }), contentAlignment = Alignment.Center) {
                        Text(
                            text = action.label,
                            style = labelStyle,
                            color = tint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            autoSize = TextAutoSize.StepBased(minFontSize = LabelMinSize, maxFontSize = if (labelStyle.fontSize.isSpecified) labelStyle.fontSize else LabelMinSize),
                        )
                    }
                }
            }
        }
    }
}

/** A disabled action's marks and label, as a share of their colour. */
private const val DisabledAlpha = 0.38f

private val LabelGap = 4.dp
private val LabelMinSize = 10.sp

/** How the spring back after an action is felt: as a poke on the bar's selected tab. */
private const val ReleaseFullBounce = 0.4f
private const val ReleaseBounceFloor = 0.04f
private const val ReleaseFeel = 0.7f
