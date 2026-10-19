package io.github.angelomarzocchi.lumen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One destination of a [FloatingNavBar]: its [label], its [icon], drawn at 24dp in the colour the bar
 * hands it, and an optional [testTag] for its cell.
 */
@Immutable
public class FloatingNavItem(
    public val label: String,
    public val icon: Painter,
    public val testTag: String? = null,
)

/**
 * How far the bar has folded: 0 open, with its labels, 1 folded into a compact island.
 *
 * Hoisted because what sits above the bar — a FAB — can ride on it. The bar folds from the top down,
 * its bottom where it was, and a FAB above it comes down by as much ([drop]) so the gap between the two
 * never changes.
 */
@Stable
public class FloatingNavBarState internal constructor(folded: Boolean) {
    internal val fold = Animatable(if (folded) 1f else 0f)

    /** How far it has folded, 0 to 1. Read it while drawing, not composing. */
    public val foldFraction: Float get() = fold.value

    /** How much shorter the folded island is than the open one: nothing, when the labels never show. */
    internal var foldDepth by mutableFloatStateOf(0f)

    /**
     * How far a landing has squashed the island, in pixels its top has come down — below 0 for the
     * moment it springs back up past its height. Its bottom stays where it is.
     */
    internal val squash = Animatable(0f)

    /**
     * How far the top of the bar has come down, in pixels: folded and squashed. Read it while drawing,
     * not composing — `Modifier.graphicsLayer { translationY = state.drop }` on a FAB above the bar.
     */
    public val drop: Float get() = fold.value * foldDepth + squash.value

    // Where the island is laid out, and how it cuts itself into tabs: what [tabInWindow] reads.
    internal var island: LayoutCoordinates? = null
    internal var plate: ((index: Int, fold: Float) -> Rect)? = null

    /**
     * Tab [index]'s plate as the island has it this frame, in window pixels: the shape a section opened
     * from the bar can grow out of. Null while the bar is not on screen.
     */
    public fun tabInWindow(index: Int): Rect? {
        val coordinates = island?.takeIf { it.isAttached } ?: return null
        val plate = plate?.invoke(index, fold.value) ?: return null
        return Rect(coordinates.localToWindow(plate.topLeft), coordinates.localToWindow(plate.bottomRight))
    }
}

/** The bar's state, open unless [folded]. */
@Composable
public fun rememberFloatingNavBarState(folded: Boolean = false): FloatingNavBarState =
    remember { FloatingNavBarState(folded) }

/**
 * A bottom navigation bar that floats over the page as an island, lit by the finger.
 *
 * **It folds as the page is read.** Pass [folded] true while the content scrolls towards its end, and
 * the bar folds; false, and it opens. Folding, the labels go first — out of sight before the island has
 * lost half its width — and the island narrows into a row of 48dp discs, its bottom staying where it
 * is. Opening, the island makes its room first and the labels grow out from under the marks once there
 * is room for them.
 *
 * **The selected tab is a plate the size of the tab**: the island draws its own padding inside itself
 * and is cut into cells, mark and label on the plate — the same shape a finger lights, so pressed and
 * selected are one shape. Folded, each plate is the 40dp disc of a 48dp cell.
 *
 * **Switching is a jelly's motion.** The plate stretches across, lands with a bounce and knocks the
 * mark over, and the finger feels the landing ([BounceHaptics]). The island holds the plate the way a
 * jelly holds what is in it: it keeps its padding round the plate on every side, so a plate landing on
 * an end tab pushes that end of the island out, and every landing squashes the island down from the
 * top and lets it spring back up. Neither may take the island more than 8dp past where it sits, nor
 * nearer than 4dp to the screen's edge: both give less and less as they near the limit, so no spring
 * can overshoot it. A FAB above can ride the squash as it rides the fold ([FloatingNavBarState.drop]).
 *
 * **A finger carries a light across it.** Put down, the finger lights the tab under it; slid along the
 * bar, the light goes with it, lighting each tab it crosses — the plate leaning that way, a tick under
 * the finger at every one — and lifting picks the tab that is lit. Slid off the island the light goes
 * out, and lifting then picks nothing.
 *
 * The labels keep inside the plate's round ends, not just inside their cell. Where they cannot fit even
 * at 10sp — a narrow screen, a large font scale — they go to the screen reader only and the bar stays
 * folded: open and folded would then differ by nothing but empty room.
 *
 * The bar pads itself for the system bars, and is always the open bar's height whatever the island is
 * doing: a `Scaffold` that pads the page by it never relayouts the page as the bar folds. Let the page
 * scroll under it — give the scrolling content the padding, not its container.
 *
 * @param items the destinations, three to five.
 * @param selectedIndex the selected destination.
 * @param onSelect hears the destination picked, the selected one too (a tap on it usually scrolls its
 *   page back to the top).
 * @param folded whether the bar should be folded.
 * @param colors the island's, the plate's and the tabs' colours.
 * @param labelStyle the labels' style, before they shrink to fit.
 * @param material draws the island instead of its flat colour — glass, say: see [IslandMaterial].
 * @param haptics whether the finger feels presses and landings.
 * @param heldAt opens with a finger held on this tab, its light on: for previews and screenshots, which
 *   cannot hold one.
 */
@Composable
public fun FloatingNavBar(
    items: List<FloatingNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    folded: Boolean = false,
    state: FloatingNavBarState = rememberFloatingNavBarState(),
    colors: LumenColors = LumenDefaults.colors(),
    labelStyle: TextStyle = MaterialTheme.typography.labelMedium,
    material: IslandMaterial? = null,
    haptics: Boolean = true,
    heldAt: Int? = null,
) {
    require(items.isNotEmpty()) { "FloatingNavBar needs at least one item" }
    LaunchedEffect(state, folded) {
        state.fold.animateTo(
            targetValue = if (folded) 1f else 0f,
            animationSpec = tween(
                durationMillis = if (folded) FoldMillis else UnfoldMillis,
                easing = FastOutSlowInEasing,
            ),
        )
    }

    val labels = items.map { it.label }

    // Always the open bar's height, whatever the island is doing: a Scaffold pads the page by this,
    // and a padding that moved with every frame of a fold would relayout the page under the finger.
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(start = IslandEdge, end = IslandEdge, top = ShadowRoom, bottom = IslandEdge),
    ) {
        val geometry = rememberNavIslandGeometry(constraints.maxWidth, labels, labelStyle)
        val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
        SideEffect {
            state.foldDepth = geometry.openHeight - geometry.foldedHeight
            state.plate = { index, fold -> geometry.plate(index, fold, mirrored) }
        }
        val slotHeight = with(LocalDensity.current) { geometry.openHeight.toDp() }
        Box(
            Modifier
                .fillMaxWidth()
                .height(slotHeight),
            contentAlignment = Alignment.BottomCenter,
        ) {
            NavIsland(
                items = items,
                selectedIndex = selectedIndex.coerceIn(0, items.lastIndex),
                onSelect = onSelect,
                labelStyle = labelStyle,
                geometry = geometry,
                fold = { state.fold.value },
                squash = state.squash,
                heldAt = heldAt,
                colors = colors,
                material = material,
                haptics = haptics,
                onPlaced = { state.island = it },
            )
        }
    }
}

/**
 * The island and what is in it, sized and laid out a frame at a time as it folds.
 *
 * The island takes every touch itself, rather than each tab its own: a tab's click would end the moment
 * the finger left it, and the finger has to be free to slide from one tab to the next. The tabs answer
 * the screen reader and the keyboard.
 */
@Composable
private fun NavIsland(
    items: List<FloatingNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    labelStyle: TextStyle,
    geometry: NavIslandGeometry,
    fold: () -> Float,
    squash: Animatable<Float, AnimationVector1D>,
    heldAt: Int?,
    colors: LumenColors,
    material: IslandMaterial?,
    haptics: Boolean,
    onPlaced: (LayoutCoordinates) -> Unit,
) {
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentGeometry by rememberUpdatedState(geometry)
    val currentFold by rememberUpdatedState(fold)

    // The plate's track is the open bar's row of cells, in its own pixels: a whole cell each.
    val openCell = geometry.openCell
    val targetStart = selectedIndex * openCell
    val targetEnd = targetStart + openCell

    val pill = remember { JellyPill(start = targetStart, end = targetEnd, index = selectedIndex) }
    val motions = remember(items.size) { List(items.size) { TabMotion(selected = it == selectedIndex) } }
    val bounceHaptics = rememberBounceHaptics().takeIf { haptics }
    // Not the effects' own scopes: a bounce that has started plays out even if the tab that started
    // it is tapped again, or a mark could be left standing at an angle.
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl

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
            onLanding = { strength ->
                // Down from the top, as far as the landing was hard, then back up past its height.
                val depth = SquashShare * currentGeometry.height(currentFold()) * strength
                if (depth > 0f) {
                    scope.launch {
                        squash.animateTo(depth, SquashIn)
                        squash.animateTo(0f, SquashOut)
                    }
                }
            },
        )
    }

    val light = remember(items.size) {
        val held = heldAt?.takeIf { it in items.indices } ?: -1
        IslandLight(
            count = items.size,
            held = held,
            at = if (held >= 0) geometry.cellCenter(held, fold(), mirrored) else Offset.Zero,
        )
    }

    fun select(index: Int) {
        if (index != currentSelectedIndex) pill.felt = true
        // The lit tab too: tapping it usually takes the user back to the top of the section.
        currentOnSelect(index)
    }

    val dark = colors.island.luminance() < 0.5f
    val look = colors.light
    val lights = remember { IslandLights() }

    // The material's lights this frame, in the island's pixels: the lamp is the finger's light, and the
    // selection is the plate, which a material draws itself.
    fun lightsAt(f: Float): IslandLights = lights.apply {
        lamp = Offset(light.x.value, light.y.value)
        lampOn = light.on.value
        selection = geometry.fromLeft(geometry.plateFrame(pill, f), f, mirrored)
        accent = colors.lit
        this.dark = dark
    }

    // The island's shape this frame, its ends pushed out where the plate presses on them.
    fun islandShape(f: Float): Rect {
        val give = geometry.wallGive(geometry.plateFrame(pill, f), f)
        val left = if (mirrored) give.end else give.start
        val right = if (mirrored) give.start else give.end
        return Rect(-left, 0f, geometry.width(f) + right, geometry.height(f))
    }

    Layout(
        modifier = Modifier
            // As big as the island is this frame: what is not island is not the bar's to touch.
            .layout { measurable, _ ->
                val f = fold()
                val width = geometry.width(f).roundToInt()
                val height = geometry.height(f).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height) { placeable.place(0, 0) }
            }
            // Before the squash's layer: where the tabs sit, not where a bounce has them this frame.
            .onPlaced(onPlaced)
            .then(material?.modifier ?: Modifier)
            // The island round the plate: its ends pushed out where the plate presses on them, and the
            // whole of it squashed by a landing. Drawn, not laid out — the page and the touches keep
            // the island's own size, and nothing relayouts for a bounce.
            .graphicsLayer {
                val f = fold()
                val width = geometry.width(f)
                val height = geometry.height(f)
                val outline = islandShape(f)
                val left = -outline.left
                val right = outline.right - width
                // Squashed it widens, by no more than the room the ends have left: past either end
                // the island never reaches further than its ends may give.
                val crush = squash.value / height
                val reach = width / 2f + geometry.give
                scaleX = min(1f + crush, reach / (width / 2f + max(left, right)))
                scaleY = 1f - crush
                transformOrigin = TransformOrigin(0.5f, 1f)
                shape = PushedCapsule(left = left, right = right)
                shadowElevation = IslandShadow.toPx()
                clip = true
            }
            // Under the plate and the marks: the plate sits on the light, and the marks stay sharp.
            .drawBehind {
                val f = fold()
                if (material != null) {
                    val shape = islandShape(f)
                    with(material) { drawIsland(shape, shape.height / 2f, geometry.give, lightsAt(f)) }
                    return@drawBehind
                }
                // Past the island's own bounds by as much as its ends can give: the shape clips it.
                val give = geometry.give
                drawRect(
                    color = colors.island,
                    topLeft = Offset(-give, 0f),
                    size = Size(size.width + 2 * give, size.height),
                )
                val selection = geometry.fromLeft(geometry.plateFrame(pill, f), f, mirrored)
                drawIslandLight(
                    light = light,
                    look = look,
                    plate = { geometry.plate(it, f, mirrored) },
                    reach = geometry.cell(f),
                    selection = selection,
                    overhang = geometry.give,
                )
            }
            // A material may engrave the marks and the labels in itself.
            .then(
                if (material != null) {
                    Modifier.graphicsLayer {
                        val f = fold()
                        val shape = islandShape(f)
                        renderEffect = material.contentEffect(this, shape, shape.height / 2f, lightsAt(f))
                    }
                } else {
                    Modifier
                },
            )
            // Solid, like any surface: nothing that lands on the island reaches the page underneath.
            .slideToSelect(
                light = light,
                motions = motions,
                scope = scope,
                cellAt = { x -> currentGeometry.cellAt(x, currentFold(), mirrored) },
                onChosen = ::select,
                key = mirrored,
            )
            .selectableGroup(),
        content = {
            // On a material the plate is the material's to draw: nothing to lay on it.
            if (material != null) {
                Box(Modifier)
            } else {
                Box(
                    Modifier
                        .layout { measurable, constraints ->
                            val frame = geometry.plateFrame(pill, fold())
                            val left = frame.left.roundToInt()
                            val top = frame.top.roundToInt()
                            // From rounded edges, as the cells are, so a plate at rest covers its cell exactly.
                            val placeable = measurable.measure(
                                Constraints.fixed(
                                    (frame.right.roundToInt() - left).coerceAtLeast(0),
                                    (frame.bottom.roundToInt() - top).coerceAtLeast(0),
                                ),
                            )
                            layout(constraints.maxWidth, constraints.maxHeight) { placeable.placeRelative(left, top) }
                        }
                        // Round under a press too, or it would no longer be concentric with the island.
                        .jellyPillSurface(pill, colors.plate, squarePressed = false)
                        // The light falls on the plate as well, more softly than on the island round it.
                        .drawBehind {
                            val f = fold()
                            val frame = geometry.fromLeft(geometry.plateFrame(pill, f), f, mirrored)
                            drawPlateLight(light, look, geometry.cell(f), origin = frame.topLeft, index = selectedIndex)
                        },
                )
            }
            items.forEachIndexed { index, item ->
                NavCell(
                    item = item,
                    labelStyle = labelStyle,
                    selected = index == selectedIndex,
                    lit = index == light.litIndex,
                    motion = motions[index],
                    geometry = geometry,
                    fold = fold,
                    colors = colors,
                    onClick = { select(index) },
                    // Reached from the keyboard, a tab is lit as if a finger were on it.
                    onFocus = { focused ->
                        light.focus(index, focused, geometry.cellCenter(index, fold(), mirrored), scope)
                    },
                )
            }
        },
    ) { measurables, constraints ->
        val f = fold()
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val padding = geometry.padding(f)
        val cell = geometry.cell(f)
        val indicator = measurables.first().measure(Constraints.fixed(width, height))
        // Each cell from rounded edges rather than a rounded width, so the row adds up to the island.
        val cells = measurables.drop(1).mapIndexed { index, measurable ->
            val left = (padding + index * cell).roundToInt()
            val right = (padding + (index + 1) * cell).roundToInt()
            left to measurable.measure(Constraints.fixed(right - left, height))
        }
        layout(width, height) {
            indicator.placeRelative(0, 0)
            cells.forEach { (left, placeable) -> placeable.placeRelative(left, 0) }
        }
    }
}

/**
 * One destination: the mark sits on its band, and the label hangs under it while the bar is open —
 * both on the plate, when the tab is the selected one. The touches are the island's ([NavIsland]);
 * what [motion] hears, the island tells it.
 */
@Composable
private fun NavCell(
    item: FloatingNavItem,
    labelStyle: TextStyle,
    selected: Boolean,
    lit: Boolean,
    motion: TabMotion,
    geometry: NavIslandGeometry,
    fold: () -> Float,
    colors: LumenColors,
    onClick: () -> Unit,
    onFocus: (Boolean) -> Unit,
) {
    // Lit, a tab takes the accent: the light is on it. On the plate, the plate's ink.
    val tint by animateColorAsState(
        targetValue = when {
            selected -> colors.onPlate
            lit -> colors.lit
            else -> colors.content
        },
        animationSpec = LumenMotion.effects(),
        label = "navTint",
    )
    // Where the mark comes to rest. The bounce on landing is the pill's to give (TabMotion.land).
    LaunchedEffect(selected) { motion.settle(selected, LumenMotion.FastSpatial) }

    Layout(
        modifier = Modifier
            .then(item.testTag?.let { Modifier.testTag(it) } ?: Modifier)
            .semantics(mergeDescendants = true) {
                role = Role.Tab
                this.selected = selected
                onClick {
                    onClick()
                    true
                }
                // No label on screen ever: the cell says it to the screen reader. While the bar is
                // merely folded the label is still there, only invisible, and is read out from there.
                if (!geometry.labelsShown) contentDescription = item.label
            }
            .onFocusChanged { onFocus(it.isFocused) }
            .onKeyEvent { event ->
                val activates = event.type == KeyEventType.KeyUp && event.key in ActivationKeys
                if (activates) onClick()
                activates
            }
            .focusable()
            .pressedIn(motion),
        content = {
            Icon(
                painter = item.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .jellyMark(motion)
                    .size(MarkSize),
            )
            if (geometry.labelsShown) {
                Text(
                    text = item.label,
                    style = labelStyle,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Smaller before cut: the bar has already checked that every label fits at the
                    // floor, so the ellipsis is only ever there for a font that has not landed yet.
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = LabelMinSize,
                        maxFontSize = labelStyle.fontSize,
                    ),
                    modifier = Modifier.graphicsLayer {
                        val shown = labelShown(fold())
                        alpha = shown
                        val scale = lerp(LabelFoldedScale, 1f, shown)
                        scaleX = scale
                        scaleY = scale
                        // It grows out from under the mark, and goes back in there.
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    },
                )
            }
        },
    ) { measurables, constraints ->
        val mark = measurables[0].measure(Constraints())
        // Measured against the open cell, folded or not: the label never reflows as the island
        // narrows, it only fades.
        val text = measurables.getOrNull(1)?.measure(Constraints(maxWidth = geometry.labelRoom))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val f = fold()
            val centerX = constraints.maxWidth / 2f
            val markCenter = geometry.markCenter(f)
            mark.placeRelative((centerX - mark.width / 2f).roundToInt(), (markCenter - mark.height / 2f).roundToInt())
            text?.let {
                val top = markCenter + geometry.markBand(f) / 2f + geometry.labelGap +
                    (geometry.labelLine - it.height) / 2f
                it.placeRelative((centerX - it.width / 2f).roundToInt(), top.roundToInt())
            }
        }
    }
}

/**
 * The island's two shapes, open and folded, in pixels, and every size between them as a function of
 * the fold.
 */
@Immutable
internal class NavIslandGeometry(
    private val count: Int,
    val labelsShown: Boolean,
    private val openWidth: Float,
    private val foldedWidth: Float,
    val openHeight: Float,
    val foldedHeight: Float,
    private val openPadding: Float,
    /** Between the island's edge and its plates, all the way round, open or folded. */
    private val inset: Float,
    /** How far in, all round, a press on the selected tab pushes its plate. */
    private val pokeInset: Float,
    /** The band the mark sits in while the bar is open: the label hangs under it. */
    private val openMarkBand: Float,
    private val openMarkCenter: Float,
    val labelGap: Float,
    val labelLine: Float,
    val labelRoom: Int,
    /**
     * How far either end of the island may be pushed out by its plate, or widened by a squash: up to
     * [MaxGive], and never nearer than [MinScreenMargin] to the edge of the screen.
     */
    val give: Float,
) {
    fun width(fold: Float) = lerp(openWidth, foldedWidth, fold)
    fun height(fold: Float) = lerp(openHeight, foldedHeight, fold)

    /** Folded, the cells tile the island edge to edge, 48dp each. */
    fun padding(fold: Float) = lerp(openPadding, 0f, fold)
    fun cell(fold: Float) = (width(fold) - 2 * padding(fold)) / count

    /**
     * How far a plate keeps from either side of its cell: nothing open, where the plates are the cells,
     * and folded the island's padding, moved into the cell — so the end plates keep [inset] from the
     * island's ends all the way through the fold, and folded each one is the 40dp disc of its 48dp cell.
     */
    private fun plateSide(fold: Float) = inset - padding(fold)
    fun plateHeight(fold: Float) = height(fold) - 2 * inset

    /** The band the mark sits in, from the island's top: the label hangs under it. */
    fun markBand(fold: Float) = lerp(openMarkBand, plateHeight(1f), fold)

    /** The line the marks sit on, from the island's top. */
    fun markCenter(fold: Float) = lerp(openMarkCenter, foldedHeight / 2f, fold)

    val openCell: Float get() = cell(0f)

    /**
     * Tab [index]'s plate at rest, from the island's top left: the island drawn its own padding inside
     * itself and cut into cells. The shape a finger lights, and the one the selection settles into.
     */
    fun plate(index: Int, fold: Float, mirrored: Boolean): Rect {
        val side = plateSide(fold)
        val fromStart = padding(fold) + index * cell(fold) + side
        val across = cell(fold) - 2 * side
        val left = if (mirrored) width(fold) - fromStart - across else fromStart
        return Rect(left, inset, left + across, height(fold) - inset)
    }

    /**
     * The selected tab's plate as [pill] has it this frame, from the island's top and its *start*: the
     * flight is kept in the open bar's pixels — a whole cell to a tab, its ends allowed [give] past the
     * row — and carried into this frame's island. Thickened by a squeeze it stops short of the island's
     * top and bottom, by half the padding.
     */
    fun plateFrame(pill: JellyPill, fold: Float): Rect {
        val bounds = pill.frame(
            trackWidth = openCell * count,
            trackHeight = plateHeight(0f),
            slack = give,
            wallGive = ContainerGive,
            // Pressed in by the same few pixels all round, below: a share of a plate this wide would
            // take far more off its width than off its height, and it would stop being concentric.
            dentWidth = 0f,
            dentHeight = 0f,
        )
        val along = cell(fold) / openCell
        val side = plateSide(fold)
        val rest = plateHeight(fold)
        val dent = pokeInset * pill.poke.value
        val thickness = min(bounds.height * rest / plateHeight(0f), rest + inset) - 2 * dent
        val start = padding(fold) + bounds.left * along + side + dent
        val end = padding(fold) + bounds.right * along - side - dent
        val top = (height(fold) - thickness) / 2f
        return Rect(start, top, max(start, end), top + max(thickness, 0f))
    }

    /** A rect measured from the island's start edge, [frame], measured from its left one instead. */
    fun fromLeft(frame: Rect, fold: Float, mirrored: Boolean): Rect =
        if (mirrored) Rect(width(fold) - frame.right, frame.top, width(fold) - frame.left, frame.bottom) else frame

    /** How far the plate in [frame] is pushing the island's two ends out, to keep its padding round it. */
    fun wallGive(frame: Rect, fold: Float) = EndsPushed(
        start = max(0f, inset - frame.left),
        end = max(0f, frame.right + inset - width(fold)),
    )

    /** The tab at [x] from the island's left edge. The two at the ends reach as far out as a finger goes. */
    fun cellAt(x: Float, fold: Float, mirrored: Boolean): Int {
        val fromStart = if (mirrored) width(fold) - x else x
        return ((fromStart - padding(fold)) / cell(fold)).toInt().coerceIn(0, count - 1)
    }

    /** Where a tab is lit from when no finger is on it: the middle of its mark, from the island's top left. */
    fun cellCenter(index: Int, fold: Float, mirrored: Boolean): Offset {
        val fromStart = padding(fold) + (index + 0.5f) * cell(fold)
        return Offset(if (mirrored) width(fold) - fromStart else fromStart, markCenter(fold))
    }
}

/** How far the island's two ends have been pushed out, in pixels: at its start and at its end. */
internal class EndsPushed(val start: Float, val end: Float)

@Composable
private fun rememberNavIslandGeometry(
    maxWidth: Int,
    labels: List<String>,
    labelStyle: TextStyle,
): NavIslandGeometry {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    return remember(maxWidth, labels, labelStyle, density) {
        with(density) {
            val count = labels.size
            val foldedCell = FoldedCell.toPx()
            val foldedWidth = foldedCell * count
            val openWidth = min(maxWidth.toFloat(), MaxOpenWidth.toPx()).coerceAtLeast(foldedWidth)
            val openPadding = OpenPadding.toPx()
            val openCell = (openWidth - 2 * openPadding) / count
            val inset = PlateInset.toPx()
            val foldedHeight = foldedCell
            val labelLine = (if (labelStyle.lineHeight.isSpecified) labelStyle.lineHeight else labelStyle.fontSize * 1.4f).toPx()
            val labelGap = LabelGap.toPx()
            val markBand = OpenMarkBand.toPx()
            val stack = markBand + labelGap + labelLine
            val openHeight = max(MinOpenHeight.toPx(), stack + 2 * OpenVerticalPadding.toPx())
            val openMarkCenter = (openHeight - stack) / 2f + markBand / 2f

            // The label sits low on a capsule plate, where its round ends have already curved in: the
            // room it has is the plate's width at the label's baseline, not the cell's. A label as wide
            // as the cell would run out over the curve on any 360dp phone.
            val sample = textMeasurer.measure(labels.first(), labelStyle, maxLines = 1, softWrap = false)
            val baseline = openMarkCenter + markBand / 2f + labelGap +
                (labelLine - sample.size.height) / 2f + sample.firstBaseline
            val radius = (openHeight - 2 * inset) / 2f
            val fromMiddle = (baseline - openHeight / 2f).coerceIn(0f, radius)
            val curve = radius - sqrt(radius * radius - fromMiddle * fromMiddle)
            val labelRoom = (openCell - 2 * (curve + LabelInset.toPx())).toInt().coerceAtLeast(0)
            // Measured at the floor the labels may shrink to, one line: if the widest of them does
            // not fit that room there, none of them is shown.
            val floorStyle = labelStyle.copy(fontSize = LabelMinSize)
            val widest = labels.maxOf {
                textMeasurer.measure(it, floorStyle, maxLines = 1, softWrap = false).size.width
            }
            // What the island has between its ends and the screen's, open — its widest, so the
            // tightest — less the margin it must keep.
            fun giveFor(islandWidth: Float) =
                ((maxWidth - islandWidth) / 2f + IslandEdge.toPx() - MinScreenMargin.toPx()).coerceIn(0f, MaxGive.toPx())

            if (widest > labelRoom) {
                NavIslandGeometry(
                    count = count,
                    labelsShown = false,
                    openWidth = foldedWidth,
                    foldedWidth = foldedWidth,
                    openHeight = foldedHeight,
                    foldedHeight = foldedHeight,
                    openPadding = 0f,
                    inset = inset,
                    pokeInset = PokeInset.toPx(),
                    openMarkBand = foldedHeight - 2 * inset,
                    openMarkCenter = foldedHeight / 2f,
                    labelGap = labelGap,
                    labelLine = labelLine,
                    labelRoom = labelRoom,
                    give = giveFor(foldedWidth),
                )
            } else {
                NavIslandGeometry(
                    count = count,
                    labelsShown = true,
                    openWidth = openWidth,
                    foldedWidth = foldedWidth,
                    openHeight = openHeight,
                    foldedHeight = foldedHeight,
                    openPadding = openPadding,
                    inset = inset,
                    pokeInset = PokeInset.toPx(),
                    openMarkBand = markBand,
                    // The mark and the label as one block, centred in the island and on the plate.
                    openMarkCenter = openMarkCenter,
                    labelGap = labelGap,
                    labelLine = labelLine,
                    labelRoom = labelRoom,
                    give = giveFor(openWidth),
                )
            }
        }
    }
}

/**
 * How much of the label is showing at a given fold: all of it open, none of it once the fold is
 * [LabelGoneAt] of the way — eased at both ends, so it neither pops in nor cuts out.
 */
private fun labelShown(fold: Float): Float {
    val t = (fold / LabelGoneAt).coerceIn(0f, 1f)
    return 1f - t * t * (3f - 2f * t)
}

/** Folding is quicker than opening: what is going away should not be watched leaving. */
private const val FoldMillis = 180
private const val UnfoldMillis = 220

/** The island keeps this far from the edges of the screen. */
private val IslandEdge = 12.dp

/** Room above the island for its shadow, inside the bar: the bar may be clipped while it slides in and out. */
private val ShadowRoom = 8.dp

/** Open, the island fills the width up to this, and no wider: past it the tabs read as a strip. */
private val MaxOpenWidth = 420.dp

/**
 * Taller than Material's 64dp so the label sits further from where the plate's round ends curve in,
 * and loses less of its width to them.
 */
private val MinOpenHeight = 72.dp
private val OpenVerticalPadding = 10.dp

/** Open, the island's padding is the plates' inset; folded, the room either side of a 40dp disc. */
private val OpenPadding = PlateInset

/** The band the mark sits in on the open bar — Material's 32dp indicator height — with the label under it. */
private val OpenMarkBand = 32.dp

/** Folded, every cell is 48dp, holding a 40dp disc. */
private val FoldedCell = 48.dp
private val MarkSize = 24.dp

private val LabelGap = 4.dp
private val LabelInset = 2.dp
private val LabelMinSize = 10.sp

/** The share of the fold by which the labels have gone; past it the island narrows on its own. */
private const val LabelGoneAt = 0.45f
private const val LabelFoldedScale = 0.8f

/** However hard it is pushed, the island keeps at least this far from the screen's edge. */
private val MinScreenMargin = 4.dp
