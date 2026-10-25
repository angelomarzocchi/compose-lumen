package io.github.angelomarzocchi.lumen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

public data class LightTab(
    val id: Any,
    val title: String,
    /**
     * What TalkBack says for the tab, when the label alone would say it badly — "Streak · 17" read
     * out is "Streak, dot, 17". Null reads the label.
     */
    val description: String? = null,
    /**
     * The tab's mark, drawn at 24dp in the colour it is handed. Null for a tab that is its word alone —
     * Story | Post.
     *
     * A slot rather than a painter because some marks are composables in their own right — one that
     * animates — and `selected` is what lets one of them move while the others stay put.
     */
    val icon: (@Composable (selected: Boolean, tint: Color) -> Unit)? = null,
)

/** The plain case: one icon, the same whether the tab is selected or not. */
public fun lightTab(id: Any, title: String, icon: Painter, description: String? = null): LightTab =
    LightTab(id = id, title = title, description = description) { _, tint ->
        Icon(
            painter = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(TabIconSize),
        )
    }

/**
 * Where a tab's label goes, in the order the bar is willing to give ground.
 *
 * The label is what varies: it is translated, and it grows with the user's font scale while the
 * icon beside it never does. Left to wrap it takes the whole bar with it — three tabs of Italian
 * copy at a 1.3 font scale on a 320 dp screen turns a 48 dp row into a 130 dp one.
 */
private enum class TabLabelPlacement { Beside, Below, Hidden }

/**
 * How the bar arranges itself once it knows how much width it has to spend.
 *
 * @param placement where each tab's label goes.
 * @param horizontalPadding the room inside a tab, either side of what it holds.
 * @param minTabWidth the width handed to every tab, so all of them come out the same size — `0.dp`
 *   when there is not enough room for that and each tab has to fall back to its own width.
 */
private data class TabBarLayout(
    val placement: TabLabelPlacement,
    val horizontalPadding: Dp,
    val minTabWidth: Dp,
)

/**
 * A row of tabs in an island, lit by the finger: the same island and the same touch as
 * [FloatingNavBar], for a row of tabs inside a page — a segmented control, a pair of views.
 *
 * **The island and its plate.** A raised capsule with a soft shadow, cut into tabs edge to edge; the
 * selected tab is a plate the size of the tab, [PlateInset] in from the island all the way round, so
 * plate and island are concentric.
 *
 * **The plate is a soft thing, and moves like one** ([JellyPill]). Its two edges travel on their own:
 * the edge on the side it is heading for goes first, passes its mark a little and comes back, and the
 * other follows ([PlateLeadingEdge]). The mark it lands on is
 * knocked over and pops; the island round it keeps its padding, so a plate landing on an end tab pushes
 * that end of the island out, and every landing squashes the island down from the top and lets it
 * spring back up.
 *
 * **A finger carries a light across it** ([slideToSelect]): put down, it lights the tab under it, and
 * slid along the row it lights each tab it crosses, the plate leaning that way; lifting picks the tab
 * that is lit. The tab under the finger has its mark and label pressed in, and a press on the plate's
 * own tab pushes the plate in, round as ever.
 *
 * **And the finger feels all of it** ([BounceHaptics]): the press as a soft tick, the landing as a
 * click, and every rebound after it as a fainter tick, each as strong as the bounce it goes with.
 * Only a tap is felt: a selection changed from anywhere else moves the plate in silence.
 *
 * The bar measures its labels before laying anything out and picks the roomiest arrangement that
 * actually fits the width it was given, giving ground a step at a time — the label beside the icon
 * before under it, every tab the width of the widest before each at its own, and the padding before
 * the label itself, which goes last of all and only to the screen reader. See [tabBarLayout] for the
 * order in full. Labels never wrap, so the bar's height is bounded by one line of text at any font
 * scale.
 *
 * The island may reach [MaxGive] past its own bounds at either end, and its shadow below them: the
 * parent leaves it that much room, as a page's margins do.
 *
 * @param tabs the tabs to display.
 * @param selectedId id of the selected tab, matching the `id` of one of [tabs].
 * @param onSelect invoked with the `id` of the tab that was picked, the selected one too (a tap on it can,
 *   say, take what it shows back to where it starts), as on [FloatingNavBar].
 * @param height the bar's minimum height. It grows past this when the labels stack under the icons.
 * @param fillWidth the tabs share the whole width the bar is given, equally. Off, the bar is as wide
 *   as its tabs and sits where it is put.
 * @param colors the island's, the plate's and the tabs' colours.
 * @param labelStyle the labels' style.
 * @param haptics whether the finger feels presses and landings.
 * @param material draws the island instead of its flat colour — glass, say: see [IslandMaterial]. It
 *   draws the selected tab's plate too, as a light inside it, with [LumenColors.lit] as its accent.
 * @param inScrollingPage whether the row sits in a page that scrolls: a finger that sets off up or down
 *   is then the page's, and the press waits until the finger is plainly on the tabs. Off — a row over a
 *   viewfinder, say — the press is felt the moment the finger lands.
 * @param modifier applied to the bar.
 */
@Composable
public fun LightTabs(
    tabs: List<LightTab>,
    selectedId: Any,
    onSelect: (Any) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    fillWidth: Boolean = false,
    colors: LumenColors = LumenDefaults.colors(),
    labelStyle: TextStyle = MaterialTheme.typography.labelLarge,
    haptics: Boolean = true,
    material: IslandMaterial? = null,
    inScrollingPage: Boolean = true,
) {
    val tabItems = tabs
    if (tabItems.isEmpty()) return

    val itemSizes = remember { mutableStateMapOf<Int, IntSize>() }
    // What each label actually came out to once it had been laid out, by title. The measuring pass
    // the arrangement is chosen from is only ever an estimate — a downloadable font arrives after
    // the first frame, and the fallback face it has to measure in is a few per cent out, which in a row fitted to the pixel is the difference between a label and a label
    // with an ellipsis in it. So the bar corrects itself from what the labels really measured.
    val laidOutLabelWidths = remember { mutableStateMapOf<String, Int>() }

    val selectedIndex = tabItems.indexOfFirst { it.id == selectedId }
        .coerceIn(0, tabItems.size - 1)
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    val currentTabItems by rememberUpdatedState(tabItems)
    val currentOnTabSelected by rememberUpdatedState(onSelect)

    val pill = remember { JellyPill() }
    val tabMotions = remember(tabItems.size) { List(tabItems.size) { TabMotion(selected = it == selectedIndex) } }
    val light = remember(tabItems.size) { IslandLight(tabItems.size) }
    // How far a landing has squashed the island, in pixels its top has come down.
    val squash = remember { Animatable(0f) }
    val bounceHaptics = rememberBounceHaptics().takeIf { haptics }
    // Not the effects' own scopes: a bounce that has started plays out even if the tab that started
    // it is tapped again, or a mark could be left standing at an angle.
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Read while drawing and touching, never while composing.
    var islandSize by remember { mutableStateOf(IntSize.Zero) }

    JellyPressEffects(
        pill = pill,
        tabMotions = tabMotions,
        selectedIndex = { currentSelectedIndex },
        haptics = bounceHaptics,
        scope = scope,
    )

    val inset = with(density) { PlateInset.toPx() }
    val give = with(density) { MaxGive.toPx() }
    val pokeInset = with(density) { PokeInset.toPx() }

    /** Where tab [index] starts, from the row's start: the tabs tile it edge to edge. */
    fun startOf(index: Int): Int = (0 until index).sumOf { itemSizes[it]?.width ?: 0 }

    /**
     * The plate as the pill has it this frame, in a track [trackWidth] by [trackHeight] — the island
     * less its inset — from the track's top and its *start*: its ends allowed [give] past the row, and
     * thickened by a squeeze it stops short of the island's top and bottom by half the inset. Pressed in
     * by the same few pixels all round, so it stays concentric.
     */
    fun plateFrame(trackWidth: Float, trackHeight: Float): Rect {
        val bounds = pill.frame(
            trackWidth = trackWidth,
            trackHeight = trackHeight,
            slack = give,
            wallGive = ContainerGive,
            dentWidth = 0f,
            dentHeight = 0f,
        )
        val dent = pokeInset * pill.poke.value
        val thickness = (min(bounds.height, trackHeight + inset) - 2 * dent).coerceAtLeast(0f)
        val start = bounds.left + dent
        val top = (trackHeight - thickness) / 2f
        return Rect(start, top, max(start, bounds.right - dent), top + thickness)
    }

    /** The plate this frame on an island [island] big, from the island's top left. */
    fun plateInIsland(island: Size): Rect {
        val frame = plateFrame(island.width - 2 * inset, island.height - 2 * inset).translate(inset, inset)
        return if (mirrored) Rect(island.width - frame.right, frame.top, island.width - frame.left, frame.bottom) else frame
    }

    /** Tab [index]'s plate at rest on an island [width] wide, from its top left: the shape a finger lights. */
    fun restPlate(index: Int, width: Float): Rect {
        val size = itemSizes[index] ?: return Rect.Zero
        val start = inset + startOf(index)
        val left = if (mirrored) width - start - size.width else start
        return Rect(left, inset, left + size.width, inset + size.height)
    }

    /** The tab at [x] from the island's left edge. The two at the ends reach as far out as a finger goes. */
    fun cellAt(x: Float): Int {
        val fromStart = (if (mirrored) islandSize.width - x else x) - inset
        var edge = 0
        for (index in currentTabItems.indices) {
            edge += itemSizes[index]?.width ?: 0
            if (fromStart < edge) return index
        }
        return currentTabItems.lastIndex
    }

    fun select(index: Int) {
        if (index != currentSelectedIndex) pill.felt = true
        currentOnTabSelected(currentTabItems[index].id)
    }

    /** How far the plate pushes the island out this frame, at its left and its right, on an island [size] big. */
    fun pushes(size: Size): Pair<Float, Float> {
        val trackWidth = size.width - 2 * inset
        val frame = plateFrame(trackWidth, size.height - 2 * inset)
        val pushStart = max(0f, -frame.left)
        val pushEnd = max(0f, frame.right - trackWidth)
        return if (mirrored) pushEnd to pushStart else pushStart to pushEnd
    }

    val dark = colors.island.luminance() < 0.5f
    val lights = remember { IslandLights() }

    // The material's lights this frame, in the island's pixels: the lamp is the finger's light, and the
    // selection is the plate, which a material draws itself.
    fun lightsOn(size: Size): IslandLights = lights.apply {
        lamp = Offset(light.x.value, light.y.value)
        lampOn = light.on.value
        selection = plateInIsland(size)
        if (tabLit.size != light.lit.size) tabLit = FloatArray(light.lit.size)
        this.tabs = List(light.lit.size) { restPlate(it, size.width) }
        for (i in tabLit.indices) tabLit[i] = light.lit[i].value
        accent = colors.lit
        this.dark = dark
    }

    /** The island's outline this frame, its ends pushed out where the plate presses on them. */
    fun islandShape(size: Size): Rect {
        val (left, right) = pushes(size)
        return Rect(-left, 0f, size.width + right, size.height)
    }

    BoxWithConstraints(modifier = modifier) {
        val layout = tabBarLayout(
            tabItems = tabItems,
            labelStyle = labelStyle,
            laidOutLabelWidths = laidOutLabelWidths,
            availableWidthPx = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE,
            fillWidth = fillWidth && constraints.hasBoundedWidth,
        )
        val barHeight = if (layout.placement == TabLabelPlacement.Below) StackedBarHeight else height

        // Where the selected tab's two edges are, in the row's own pixels.
        val targetStart = startOf(selectedIndex)
        val targetEnd = targetStart + (itemSizes[selectedIndex]?.width ?: 0)
        LaunchedEffect(targetStart, targetEnd) {
            pill.settleOn(
                index = selectedIndex,
                start = targetStart.toFloat(),
                end = targetEnd.toFloat(),
                tabMotions = tabMotions,
                haptics = bounceHaptics,
                scope = scope,
                density = density,
                mirrored = mirrored,
                lead = PlateLeadingEdge,
                trail = PlateTrailingEdge,
                onLanding = { strength ->
                    // Down from the top, as far as the landing was hard, then back up past its height.
                    val depth = SquashShare * islandSize.height * strength
                    if (depth > 0f) {
                        scope.launch {
                            squash.animateTo(depth, SquashIn)
                            squash.animateTo(0f, SquashOut)
                        }
                    }
                },
            )
        }

        val look = colors.light
        Box(
            modifier = Modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .heightIn(min = barHeight)
                .onSizeChanged { islandSize = it }
                .then(material?.modifier ?: Modifier)
                // The island round the plate: its ends pushed out where the plate presses on them, and
                // the whole of it squashed by a landing. Drawn, not laid out — the page and the touches
                // keep the island's own size, and nothing relayouts for a bounce.
                .graphicsLayer {
                    val width = size.width
                    val (left, right) = pushes(size)
                    // Squashed it widens, by no more than the room the ends have left: past either end
                    // the island never reaches further than its ends may give.
                    val crush = if (size.height > 0f) squash.value / size.height else 0f
                    if (width > 0f) {
                        val reach = width / 2f + give
                        scaleX = min(1f + crush, reach / (width / 2f + max(left, right)))
                    }
                    scaleY = 1f - crush
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    shape = PushedCapsule(left = left, right = right)
                    shadowElevation = IslandShadow.toPx()
                    clip = true
                }
                // Under the plate and the marks: the plate sits on the light, and the marks stay sharp.
                .drawBehind {
                    if (material != null) {
                        val shape = islandShape(size)
                        with(material) { drawIsland(shape, shape.height / 2f, give, lightsOn(size)) }
                        return@drawBehind
                    }
                    // Past the island's own bounds by as much as its ends can give: the shape clips it.
                    drawRect(
                        color = colors.island,
                        topLeft = Offset(-give, 0f),
                        size = Size(size.width + 2 * give, size.height),
                    )
                    drawIslandLight(
                        light = light,
                        look = look,
                        plate = { restPlate(it, size.width) },
                        reach = lightReach(size, tabItems.size),
                        selection = plateInIsland(size),
                        overhang = give,
                    )
                }
                // A material may engrave the marks and the labels in itself.
                .then(
                    if (material != null) {
                        Modifier.graphicsLayer {
                            val shape = islandShape(size)
                            renderEffect = material.contentEffect(this, shape, shape.height / 2f, lightsOn(size))
                        }
                    } else {
                        Modifier
                    },
                )
                .slideToSelect(
                    light = light,
                    motions = tabMotions,
                    scope = scope,
                    cellAt = ::cellAt,
                    onChosen = ::select,
                    // A row of tabs usually sits in a page, and the page may scroll under the finger.
                    yieldsToScroll = inScrollingPage,
                    key = mirrored to density,
                )
                .selectableGroup()
                .padding(PlateInset),
        ) {
            // On a material the plate is the material's to draw: nothing to lay on it.
            if (pill.placed && material == null) {
                Box(
                    modifier = Modifier
                        // The track's own size, whatever the pill is doing, so the bar never grows to
                        // fit a bounce. Everything the pill does is read here and at draw time rather
                        // than in composition: a flight is a relayout of one box a frame, not a
                        // recomposition of the bar.
                        .matchParentSize()
                        .layout { measurable, constraints ->
                            val frame = plateFrame(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                            val left = frame.left.roundToInt()
                            val top = frame.top.roundToInt()
                            // From rounded edges, as the tabs are, so a plate at rest covers its tab exactly.
                            val placeable = measurable.measure(
                                Constraints.fixed(
                                    (frame.right.roundToInt() - left).coerceAtLeast(0),
                                    (frame.bottom.roundToInt() - top).coerceAtLeast(0),
                                ),
                            )
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                placeable.placeRelative(left, top)
                            }
                        }
                        // Round under a press too, or it would no longer be concentric with the island.
                        .jellyPillSurface(pill, colors.plate, squarePressed = false)
                        // The light falls on the plate as well, more softly than on the island round it.
                        .drawBehind {
                            val island = islandSize.toSize()
                            drawPlateLight(
                                light = light,
                                look = look,
                                reach = lightReach(island, tabItems.size),
                                origin = plateInIsland(island).topLeft,
                                index = selectedIndex,
                            )
                        },
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                // Intrinsics rather than the tallest tab winning by accident: the row takes the
                // height its roomiest tab asks for and every tab is stretched to it, so the tabs and
                // the plate behind them are one rectangle repeated.
                modifier = Modifier
                    .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                    .heightIn(min = barHeight - PlateInset * 2)
                    .height(IntrinsicSize.Max),
            ) {
                tabItems.forEachIndexed { index, tab ->
                    val isSelected = index == selectedIndex
                    // One colour for the mark and its label. Lit, a tab off the plate takes the app's
                    // colour: the light is on it. Handed on as a state, read where it is drawn: a fade
                    // is a redraw of a label a frame, not a recomposition of the row.
                    val contentColor = animateColorAsState(
                        targetValue = when {
                            isSelected -> colors.onPlate
                            index == light.litIndex -> colors.lit
                            else -> colors.content
                        },
                        animationSpec = LumenMotion.effects(),
                        label = "tabContentColor",
                    )

                    Tab(
                        tab = tab,
                        isSelected = isSelected,
                        motion = tabMotions[index],
                        layout = layout,
                        contentColor = contentColor,
                        labelStyle = labelStyle,
                        onClick = { select(index) },
                        // Reached from the keyboard, a tab is lit as if a finger were on it.
                        onFocus = { focused ->
                            light.focus(index, focused, restPlate(index, islandSize.width.toFloat()).center, scope)
                        },
                        onLabelWidth = { width ->
                            if (laidOutLabelWidths[tab.title] != width) {
                                laidOutLabelWidths[tab.title] = width
                            }
                        },
                        modifier = Modifier
                            .then(if (fillWidth) Modifier.weight(1f) else Modifier)
                            .onSizeChanged { size -> itemSizes[index] = size },
                    )
                }
            }
        }
    }
}

/**
 * How far the finger's light reaches on an island [size] holding [count] tabs: a tab's width, as on the
 * navigation bar, but no more than twice the island's height — on two tabs across a phone a tab's width
 * would light the whole island at once.
 */
private fun lightReach(size: Size, count: Int): Float =
    min(size.width / count.coerceAtLeast(1), size.height * 2f)

/**
 * One tab: its mark and label, pressed in under the finger. The touches are the island's
 * ([slideToSelect]); what [motion] hears, the island tells it. The tab answers the screen reader and the
 * keyboard.
 */
@Composable
private fun Tab(
    tab: LightTab,
    isSelected: Boolean,
    motion: TabMotion,
    layout: TabBarLayout,
    contentColor: State<Color>,
    labelStyle: TextStyle,
    onClick: () -> Unit,
    onFocus: (Boolean) -> Unit,
    onLabelWidth: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = tab.icon
    // With no label on screen there is only the description, or the label, to say.
    val hidden = layout.placement == TabLabelPlacement.Hidden && icon != null
    // `widthIn` rather than `width` so a label the measuring pass was a pixel out on widens its own
    // tab instead of being cut short.
    val target = modifier
        .widthIn(min = layout.minTabWidth)
        .fillMaxHeight()
        .semantics(mergeDescendants = true) {
            role = Role.Tab
            selected = isSelected
            onClick {
                onClick()
                true
            }
            // Said in place of the label it sits beside; with no label on screen it is all there is.
            if (hidden) {
                contentDescription = tab.description ?: tab.title
            } else {
                tab.description?.let { contentDescription = it }
            }
        }
        .onFocusChanged { onFocus(it.isFocused) }
        .onKeyEvent { event ->
            val activates = event.type == KeyEventType.KeyUp && event.key in ActivationKeys
            if (activates) onClick()
            activates
        }
        .focusable()
    // Icon and label pressed in as one, inside the padding: the plate behind them keeps its size.
    val content = Modifier
        .padding(horizontal = layout.horizontalPadding, vertical = TabVerticalPadding)
        .pressedIn(motion)

    // Where the mark comes to rest. The bounce on landing is the pill's to give (TabMotion.land).
    val settleSpec = LumenMotion.FastSpatial
    LaunchedEffect(isSelected) {
        motion.settle(isSelected, settleSpec)
    }
    val mark = @Composable {
        if (icon != null) {
            Box(Modifier.jellyMark(motion)) {
                icon(isSelected, contentColor.value)
            }
        }
    }

    val label = @Composable {
        BasicText(
            text = tab.title,
            style = labelStyle,
            color = { contentColor.value },
            // The bar has already decided this fits on one line. The ellipsis is only there for
            // the first frame, before the label has reported what it really came out to.
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            // The width the text wanted, whatever it was given: what the next arrangement is
            // decided on. It does not depend on the placement, so reporting it cannot set the bar
            // oscillating between two of them.
            onTextLayout = { onLabelWidth(ceil(it.multiParagraph.maxIntrinsicWidth).toInt()) },
            // A word alone is knocked over and pops as a mark would: the landing has to show on it.
            modifier = if (icon == null) Modifier.jellyMark(motion) else Modifier,
        )
    }

    when {
        // No label on screen, so the tab is its mark.
        hidden -> Box(
            contentAlignment = Alignment.Center,
            modifier = target.then(content),
        ) {
            mark()
        }

        // The arrangements centre what they hold rather than pack it: a tab is usually wider and
        // taller than its icon and label, which belong in the middle of the plate.
        layout.placement == TabLabelPlacement.Below -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TabStackedIconLabelGap, Alignment.CenterVertically),
            modifier = target.then(content),
        ) {
            mark()
            label()
        }

        else -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TabIconLabelGap, Alignment.CenterHorizontally),
            modifier = target.then(content),
        ) {
            mark()
            label()
        }
    }
}

/**
 * The roomiest arrangement that fits [availableWidthPx], from what the labels came out to last time
 * they were drawn ([laidOutLabelWidths]) or, until then, from measuring them as they will be — one
 * line, at the current font scale and locale.
 *
 * Measuring beats guessing at a `maxWidth` threshold here: the labels are translated, and one word
 * can cost twice another in the same row.
 *
 * A placement is asked several times before the next one is tried, each time with a little less
 * comfort: tabs all one width, then all one width with the padding pulled in, then each at its own
 * width, then with the padding pulled in too. Spending the last few dp on decoration and then hiding
 * a label for want of them would be a poor trade. Tabs that are their word alone have no mark to fall
 * back on: they stay beside, with the padding pulled in.
 */
@Composable
private fun tabBarLayout(
    tabItems: List<LightTab>,
    labelStyle: TextStyle,
    laidOutLabelWidths: Map<String, Int>,
    availableWidthPx: Int,
    fillWidth: Boolean = false,
): TabBarLayout {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // Nothing to fit into: a parent willing to let the bar be as wide as it likes.
    if (availableWidthPx == Int.MAX_VALUE) {
        return TabBarLayout(TabLabelPlacement.Beside, TabHorizontalPadding, 0.dp)
    }

    val labelWidths = tabItems.map { tab ->
        laidOutLabelWidths[tab.title] ?: textMeasurer.measure(
            text = tab.title,
            style = labelStyle,
            maxLines = 1,
            softWrap = false,
        ).size.width
    }
    val marked = tabItems.all { it.icon != null }
    val fallback = if (marked) {
        TabBarLayout(TabLabelPlacement.Hidden, TabHorizontalPadding, 0.dp)
    } else {
        TabBarLayout(TabLabelPlacement.Beside, TabTightPadding, 0.dp)
    }

    return with(density) {
        // The island's own inset is not available to the tabs.
        val available = availableWidthPx - (PlateInset * 2).roundToPx()
        val iconPx = TabIconSize.roundToPx()

        val besideWidths = { padding: Dp ->
            tabItems.mapIndexed { index, tab ->
                val mark = if (tab.icon != null) iconPx + TabIconLabelGap.roundToPx() else 0
                (padding * 2).roundToPx() + mark + labelWidths[index]
            }
        }
        val belowWidths = { padding: Dp ->
            tabItems.mapIndexed { index, tab ->
                (padding * 2).roundToPx() + maxOf(if (tab.icon != null) iconPx else 0, labelWidths[index])
            }
        }

        fun fit(
            placement: TabLabelPlacement,
            tabWidths: (Dp) -> List<Int>,
            padding: Dp,
            tightPadding: Dp,
        ): TabBarLayout? {
            val roomy = tabWidths(padding)
            val snug = tabWidths(tightPadding)
            return when {
                roomy.max() * roomy.size <= available ->
                    TabBarLayout(placement, padding, roomy.max().toDp())
                // Worth pulling the padding in for: it only ever binds on the widest tab, and the
                // room it gives up there is what buys the other tabs their matching width.
                snug.max() * snug.size <= available ->
                    TabBarLayout(placement, tightPadding, snug.max().toDp())
                roomy.sum() <= available -> TabBarLayout(placement, padding, 0.dp)
                snug.sum() <= available -> TabBarLayout(placement, tightPadding, 0.dp)
                else -> null
            }
        }

        // Tabs that share the width equally get the same share whatever their labels: the question
        // is only whether the widest label fits in a share, beside the icon or under it.
        if (fillWidth) {
            val share = available / tabItems.size
            return@with when {
                besideWidths(TabHorizontalPadding).max() <= share ->
                    TabBarLayout(TabLabelPlacement.Beside, TabHorizontalPadding, 0.dp)
                besideWidths(TabTightPadding).max() <= share ->
                    TabBarLayout(TabLabelPlacement.Beside, TabTightPadding, 0.dp)
                marked && belowWidths(TabHorizontalPadding).max() <= share ->
                    TabBarLayout(TabLabelPlacement.Below, TabHorizontalPadding, 0.dp)
                marked && belowWidths(TabTightPadding).max() <= share ->
                    TabBarLayout(TabLabelPlacement.Below, TabTightPadding, 0.dp)
                else -> fallback
            }
        }

        fit(TabLabelPlacement.Beside, besideWidths, TabHorizontalPadding, TabTightPadding)
            ?: (if (marked) fit(TabLabelPlacement.Below, belowWidths, TabHorizontalPadding, TabTightPadding) else null)
            // Icons alone are one size already, so there is nothing left to even out.
            ?: fallback
    }
}

private val TabIconSize = 24.dp
private val TabVerticalPadding = 8.dp
private val TabIconLabelGap = 8.dp
private val TabStackedIconLabelGap = 4.dp

/** The bar's height once the labels stack under the icons. */
private val StackedBarHeight = 64.dp

/** What presses a tab the keyboard is on, as they would press a button. */
internal val ActivationKeys = setOf(Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.DirectionCenter)

/**
 * What a tab is padded by either side, and what it will settle for when the width is short. The
 * same pair whether the label sits beside the icon or under it: a stacked tab is the one whose
 * label lands closest to its neighbour's, so it is the last one that should be padded less.
 */
private val TabHorizontalPadding = 12.dp
private val TabTightPadding = 8.dp
