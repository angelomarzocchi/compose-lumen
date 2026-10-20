package io.github.angelomarzocchi.lumen

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A row of tabs as their marks alone, each in a [cell] square with the selected one's plate a disc
 * [PlateInset] inside it: the folded [FloatingNavBar]'s island, for a pair of views whose labelled
 * [LightTabs] have scrolled away with the page. The names are read out, not shown — a tab's
 * `description`, or its title.
 *
 * The same thing to touch as the tabs and the bar: the disc is their jelly plate on their calm springs,
 * the mark it lands on is knocked over, and a finger carries the light across the row and picks the tab
 * it lifts on.
 *
 * It draws no island of its own, so it can sit in one that holds more than it — a toolbar's, say. Put
 * it on [LumenColors.island]: the light is made for it. Nor does that island give way at the ends or
 * squash on a landing: it is not the disc's to move.
 *
 * @param tabs the tabs, each with an icon: the slot draws it at the size it likes, in the tint handed.
 * @param selectedId id of the selected tab, matching the `id` of one of [tabs].
 * @param onSelect invoked with the `id` of the tab that was picked.
 * @param cell the side of each tab's square.
 * @param colors the plate's and the marks' colours, and the light for the island underneath.
 * @param haptics whether the finger feels presses and landings.
 */
@Composable
public fun LightTabIcons(
    tabs: List<LightTab>,
    selectedId: Any,
    onSelect: (Any) -> Unit,
    modifier: Modifier = Modifier,
    cell: Dp = 48.dp,
    colors: LumenColors = LumenDefaults.colors(),
    haptics: Boolean = true,
) {
    if (tabs.isEmpty()) return
    val selectedIndex = tabs.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    val currentTabs by rememberUpdatedState(tabs)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val density = LocalDensity.current
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
    val slot = with(density) { cell.toPx() }
    val inset = with(density) { PlateInset.toPx() }
    val pokeInset = with(density) { PokeInset.toPx() }
    val width = slot * tabs.size

    // The disc's two edges, from the row's start: its cell less the inset either side.
    val targetStart = selectedIndex * slot + inset
    val targetEnd = (selectedIndex + 1) * slot - inset
    val pill = remember { JellyPill(start = targetStart, end = targetEnd, index = selectedIndex) }
    val motions = remember(tabs.size) { List(tabs.size) { TabMotion(selected = it == selectedIndex) } }
    val light = remember(tabs.size) { IslandLight(tabs.size) }
    val bounceHaptics = rememberBounceHaptics().takeIf { haptics }
    // Not the effects' own scopes: a bounce that has started plays out even if the tab that started
    // it is tapped again, or a mark could be left standing at an angle.
    val scope = rememberCoroutineScope()

    JellyPressEffects(
        pill = pill,
        tabMotions = motions,
        selectedIndex = { currentSelectedIndex },
        haptics = bounceHaptics,
        scope = scope,
    )
    LaunchedEffect(targetStart, targetEnd) {
        pill.settleOn(
            index = selectedIndex,
            start = targetStart,
            end = targetEnd,
            tabMotions = motions,
            haptics = bounceHaptics,
            scope = scope,
            density = density,
            mirrored = mirrored,
            lead = PlateLeadingEdge,
            trail = PlateTrailingEdge,
        )
    }

    /**
     * The disc this frame, from the row's top and its *start*: held inside the row, and pressed in by
     * the same few pixels all round so it stays round.
     */
    fun discFrame(): Rect {
        val bounds = pill.frame(
            trackWidth = width,
            trackHeight = slot - 2 * inset,
            slack = 0f,
            dentWidth = 0f,
            dentHeight = 0f,
        )
        val dent = pokeInset * pill.poke.value
        val start = bounds.left + dent
        val top = inset + bounds.top + dent
        return Rect(start, top, max(start, bounds.right - dent), max(top, inset + bounds.bottom - dent))
    }

    fun fromLeft(frame: Rect): Rect =
        if (mirrored) Rect(width - frame.right, frame.top, width - frame.left, frame.bottom) else frame

    /** Tab [index]'s disc at rest, from the row's top left: the shape a finger lights. */
    fun restDisc(index: Int): Rect = fromLeft(Rect(index * slot + inset, inset, (index + 1) * slot - inset, slot - inset))

    fun select(index: Int) {
        if (index != currentSelectedIndex) pill.felt = true
        currentOnSelect(currentTabs[index].id)
    }

    val look = colors.light
    Box(
        modifier
            .size(cell * tabs.size, cell)
            // Under the disc and the marks: the disc sits on the light, and the marks stay sharp.
            .drawBehind {
                drawIslandLight(
                    light = light,
                    look = look,
                    plate = ::restDisc,
                    reach = slot,
                    selection = fromLeft(discFrame()),
                    overhang = 0f,
                )
            }
            .slideToSelect(
                light = light,
                motions = motions,
                scope = scope,
                cellAt = { x -> ((if (mirrored) width - x else x) / slot).toInt().coerceIn(0, tabs.lastIndex) },
                onChosen = ::select,
                key = mirrored to density,
            )
            .selectableGroup(),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .layout { measurable, constraints ->
                    val frame = discFrame()
                    val left = frame.left.roundToInt()
                    val top = frame.top.roundToInt()
                    val placeable = measurable.measure(
                        Constraints.fixed(
                            (frame.right.roundToInt() - left).coerceAtLeast(0),
                            (frame.bottom.roundToInt() - top).coerceAtLeast(0),
                        ),
                    )
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.placeRelative(left, top) }
                }
                .jellyPillSurface(pill, colors.plate, squarePressed = false)
                // The light falls on the disc as well, more softly than on the island round it.
                .drawBehind {
                    drawPlateLight(light, look, slot, origin = fromLeft(discFrame()).topLeft, index = selectedIndex)
                },
        )
        Row {
            tabs.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                val motion = motions[index]
                // Lit, a mark off the disc takes the accent: the light is on it.
                val tint by animateColorAsState(
                    targetValue = when {
                        selected -> colors.onPlate
                        index == light.litIndex -> colors.lit
                        else -> colors.content
                    },
                    animationSpec = LumenMotion.effects(),
                    label = "lightTabIconTint",
                )
                // Where the mark comes to rest. The bounce on landing is the disc's to give.
                LaunchedEffect(selected) { motion.settle(selected, LumenMotion.FastSpatial) }
                Box(
                    modifier = Modifier
                        .size(cell)
                        .semantics(mergeDescendants = true) {
                            role = Role.Tab
                            this.selected = selected
                            contentDescription = tab.description ?: tab.title
                            onClick {
                                select(index)
                                true
                            }
                        }
                        .onFocusChanged { light.focus(index, it.isFocused, restDisc(index).center, scope) }
                        .onKeyEvent { event ->
                            val activates = event.type == KeyEventType.KeyUp && event.key in ActivationKeys
                            if (activates) select(index)
                            activates
                        }
                        .focusable()
                        .pressedIn(motion),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.jellyMark(motion)) {
                        tab.icon?.invoke(selected, tint)
                    }
                }
            }
        }
    }
}
