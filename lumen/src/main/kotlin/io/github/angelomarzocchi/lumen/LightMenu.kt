package io.github.angelomarzocchi.lumen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** One line of a [LightMenu]: an [LightMenuItem] to choose, a [LightMenuHeader] or a [LightMenuDivider]. */
public sealed interface LightMenuEntry

/**
 * Something to do from a [LightMenu]: its [label], after its [icon] if it has one, drawn in the colour
 * the menu hands it.
 *
 * @param trailing a short word at the far end, in the accent: where the thing is now ("Travel").
 * @param checked a dot of the accent at the far end: the choice already made, in a list of choices.
 * @param destructive in [LightMenuColors.destructive]: deleting, leaving.
 * @param enabled dimmed and doing nothing when false.
 */
@Immutable
public class LightMenuItem(
    public val label: String,
    public val onClick: () -> Unit,
    public val icon: Painter? = null,
    public val trailing: String? = null,
    public val checked: Boolean = false,
    public val destructive: Boolean = false,
    public val enabled: Boolean = true,
    public val testTag: String? = null,
) : LightMenuEntry

/** What the menu is about, at its top: a [title], and a quieter [subtitle] at the far end. Not touchable. */
@Immutable
public class LightMenuHeader(public val title: String, public val subtitle: String? = null) : LightMenuEntry

/** A hairline between two groups of items. */
public data object LightMenuDivider : LightMenuEntry

/**
 * The colours of a [LightMenu].
 *
 * @param island the menu's body, when it has no material.
 * @param content an item's icon and label; [lit] the same under the finger.
 * @param accent an item's [LightMenuItem.trailing] word and its [LightMenuItem.checked] dot.
 * @param destructive a [LightMenuItem.destructive] item, lit or not.
 * @param secondary the header's title; its subtitle is this at a lower alpha.
 * @param divider the hairline of a [LightMenuDivider].
 * @param light how strong the finger's light is: see [FingerLight].
 */
@Immutable
public class LightMenuColors(
    public val island: Color,
    public val content: Color,
    public val lit: Color,
    public val accent: Color,
    public val destructive: Color,
    public val secondary: Color,
    public val divider: Color,
    public val light: FingerLight = FingerLight.forSurface(island),
) {
    override fun equals(other: Any?): Boolean =
        other is LightMenuColors && island == other.island && content == other.content && lit == other.lit &&
            accent == other.accent && destructive == other.destructive && secondary == other.secondary &&
            divider == other.divider && light == other.light

    override fun hashCode(): Int {
        var result = island.hashCode()
        for (part in listOf(content, lit, accent, destructive, secondary, divider)) result = 31 * result + part.hashCode()
        return 31 * result + light.hashCode()
    }
}

/** Defaults read from the Material theme. */
public object LightMenuDefaults {
    /**
     * The island `surfaceContainerHigh`, the items `onSurface`, `primary` under the finger and for the
     * accent, `error` for destructive items, the header `onSurfaceVariant`, the hairline `outlineVariant`.
     */
    @Composable
    public fun colors(
        island: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
        content: Color = MaterialTheme.colorScheme.onSurface,
        lit: Color = MaterialTheme.colorScheme.primary,
        accent: Color = MaterialTheme.colorScheme.primary,
        destructive: Color = MaterialTheme.colorScheme.error,
        secondary: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        divider: Color = MaterialTheme.colorScheme.outlineVariant,
        light: FingerLight = FingerLight.forSurface(island),
    ): LightMenuColors = LightMenuColors(island, content, lit, accent, destructive, secondary, divider, light)
}

/**
 * Lets the finger that opened a [LightMenu] — by holding something down — go on into it without lifting:
 * slid onto an item it lights it, and lifted there it does it, as iOS's context menus do.
 *
 * Put [lightMenuHandOff] on what opens the menu, and pass the same instance to the menu. One instance can
 * serve many openers (every card of a list) and the one menu they open.
 */
@Stable
public class LightMenuHandOff {
    internal var menu: MenuTouch? = null

    internal fun follow(atInWindow: Offset, uptimeMillis: Long) {
        menu?.followInWindow(atInWindow, uptimeMillis)
    }

    internal fun lift() {
        menu?.end(chosen = true)
    }

    internal fun cancel() {
        menu?.end(chosen = false)
    }
}

/** Remembers a [LightMenuHandOff]. */
@Composable
public fun rememberLightMenuHandOff(): LightMenuHandOff = remember { LightMenuHandOff() }

/**
 * On what a long press opens a [LightMenu] from: the finger, still down when the menu appears, goes on
 * into it ([LightMenuHandOff]). It only watches the finger, in the last pass: clicks, long clicks and
 * scrolls work as they did.
 */
public fun Modifier.lightMenuHandOff(handOff: LightMenuHandOff): Modifier = this then HandOffElement(handOff)

/**
 * A menu of actions, the vertical sibling of [LightActions]: put down, the finger lights the item under
 * it; slid up and down the menu the light goes with it, a tick at every item, and lifting does the item
 * that is lit. Slid off the side the light goes out, and lifting then does nothing.
 *
 * The menu is soft, and held by the finger: it leans towards where the finger is on it — its top
 * stretched up with the finger above the middle, its bottom down below it, more the nearer the edge —
 * and stays so for as long as the finger stays; past its top or bottom it goes on stretching after the
 * finger, less and less. Only when the finger lifts does it spring back, with a wobble the finger feels.
 * The items move with it, the nearest to the pulled edge the most, as if hung from the other one.
 *
 * It is as wide as [modifier] makes it, and as tall as its entries. Open it from a long press with
 * [handOff] and the same finger can choose without lifting.
 *
 * @param entries what it shows, top to bottom.
 * @param colors the menu's colours ([LightMenuColors.island] is unused with a [material]).
 * @param labelStyle the items' labels.
 * @param detailStyle a trailing word and the header's title (in medium weight).
 * @param captionStyle the header's subtitle.
 * @param itemHeight each item's height.
 * @param iconSize the side of each item's icon.
 * @param cornerRadius the corners of the menu; the lit item is a capsule inside them.
 * @param material draws the body instead of its flat colour — glass, say: see [IslandMaterial]. Its
 *   `selection` is always empty, and its `tabs` are the items.
 * @param handOff the finger that opened the menu, when a long press did: see [LightMenuHandOff].
 * @param haptics whether the finger feels the items and the spring back.
 */
@Composable
public fun LightMenu(
    entries: List<LightMenuEntry>,
    modifier: Modifier = Modifier,
    colors: LightMenuColors = LightMenuDefaults.colors(),
    labelStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    detailStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    captionStyle: TextStyle = MaterialTheme.typography.labelSmall,
    itemHeight: Dp = 44.dp,
    iconSize: Dp = 18.dp,
    cornerRadius: Dp = 22.dp,
    material: IslandMaterial? = null,
    handOff: LightMenuHandOff? = null,
    haptics: Boolean = true,
) {
    val items = remember(entries) { entries.filterIsInstance<LightMenuItem>() }
    val currentItems by rememberUpdatedState(items)
    val scope = rememberCoroutineScope()
    val bounceHaptics = rememberBounceHaptics().takeIf { haptics }
    val density = LocalDensity.current
    val touch = remember(items.size) { MenuTouch(items.size) }
    touch.scope = scope
    touch.haptics = bounceHaptics
    touch.density = density
    touch.enabled = { currentItems.getOrNull(it)?.enabled == true }
    touch.act = { index -> currentItems.getOrNull(index)?.takeIf { it.enabled }?.onClick?.invoke() }

    if (handOff != null) {
        DisposableEffect(handOff, touch) {
            handOff.menu = touch
            onDispose { if (handOff.menu === touch) handOff.menu = null }
        }
    }

    val dark = colors.island.luminance() < 0.5f
    val lights = remember { IslandLights() }
    val radius = with(density) { cornerRadius.toPx() }
    val clip = remember { Path() }
    val flatShape = remember(cornerRadius) { androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius) }

    fun lightsOn(): IslandLights = lights.apply {
        lamp = Offset(touch.light.x.value, touch.light.y.value)
        lampOn = touch.light.on.value
        selection = Rect.Zero
        if (tabLit.size != touch.light.lit.size) tabLit = FloatArray(touch.light.lit.size)
        tabs = List(touch.light.lit.size) { touch.litPlate(it) }
        for (i in tabLit.indices) tabLit[i] = touch.light.lit[i].value
        accent = colors.lit
        this.dark = dark
    }

    Box(
        modifier
            .then(material?.modifier ?: Modifier)
            .onPlaced { touch.coordinates = it }
            .graphicsLayer {
                if (material == null) {
                    shadowElevation = IslandShadow.toPx()
                    shape = flatShape
                }
            }
            .drawBehind {
                val body = touch.body(size.width, size.height)
                if (material != null) {
                    with(material) { drawIsland(body, radius, abs(touch.stretch.value), lightsOn()) }
                    return@drawBehind
                }
                clip.reset()
                clip.addRoundRect(RoundRect(body, CornerRadius(radius)))
                clipPath(clip) {
                    drawRect(colors.island, topLeft = body.topLeft, size = body.size)
                    drawIslandLight(
                        light = touch.light,
                        look = colors.light,
                        plate = touch::litPlate,
                        reach = min(size.width, itemHeight.toPx() * LightReachRows),
                        selection = Rect.Zero,
                        overhang = 0f,
                    )
                }
            }
            .pointerInput(touch) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    touch.start(down.position, down.uptimeMillis)
                    var lifted = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            lifted = !change.isConsumed
                            change.consume()
                            break
                        }
                        if (change.positionChange() != Offset.Zero) touch.follow(change.position, change.uptimeMillis)
                        change.consume()
                    }
                    touch.end(chosen = lifted)
                }
            },
    ) {
        Column(
            Modifier
                .graphicsLayer {
                    if (material != null) {
                        val body = touch.body(size.width, size.height)
                        renderEffect = material.contentEffect(this, body, radius, lightsOn())
                    }
                }
                .padding(MenuInset),
        ) {
            var itemIndex = 0
            entries.forEachIndexed { entryIndex, entry ->
                val hung = Modifier
                    .onPlaced { touch.place(entryIndex, it) }
                    .graphicsLayer { translationY = touch.shift(entryIndex) }
                when (entry) {
                    is LightMenuHeader -> MenuHeader(entry, colors, detailStyle, captionStyle, hung)
                    LightMenuDivider -> Box(hung.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp).height(1.dp).background(colors.divider))
                    is LightMenuItem -> {
                        val index = itemIndex++
                        touch.rowEntry[index] = entryIndex
                        MenuItem(entry, index, entryIndex, touch, colors, labelStyle, detailStyle, itemHeight, iconSize, hung)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuHeader(header: LightMenuHeader, colors: LightMenuColors, titleStyle: TextStyle, captionStyle: TextStyle, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            header.title,
            style = titleStyle.copy(fontWeight = FontWeight.Medium),
            color = colors.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (header.subtitle != null) {
            Text(header.subtitle, style = captionStyle, color = colors.secondary.copy(alpha = colors.secondary.alpha * SubtitleAlpha), maxLines = 1)
        }
    }
}

@Composable
private fun MenuItem(
    item: LightMenuItem,
    index: Int,
    entryIndex: Int,
    touch: MenuTouch,
    colors: LightMenuColors,
    labelStyle: TextStyle,
    detailStyle: TextStyle,
    itemHeight: Dp,
    iconSize: Dp,
    modifier: Modifier,
) {
    val base = if (item.destructive) colors.destructive else colors.content
    val tint by animateColorAsState(
        targetValue = when {
            !item.enabled -> base.copy(alpha = base.alpha * DisabledAlpha)
            item.destructive -> base
            index == touch.light.litIndex -> colors.lit
            else -> base
        },
        animationSpec = LumenMotion.effects(),
        label = "lightMenuTint",
    )
    Row(
        modifier
            .fillMaxWidth()
            .height(itemHeight)
            .then(item.testTag?.let { Modifier.testTag(it) } ?: Modifier)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = listOfNotNull(item.label, item.trailing).joinToString(", ")
                if (item.checked) selected = true
                if (!item.enabled) disabled()
                onClick {
                    if (item.enabled) item.onClick()
                    true
                }
            }
            .onFocusChanged { touch.focus(index, it.isFocused) }
            .onKeyEvent { event ->
                val activates = event.type == KeyEventType.KeyUp && event.key in ActivationKeys
                if (activates && item.enabled) item.onClick()
                activates
            }
            .focusable(enabled = item.enabled)
            .graphicsLayer {
                val scale = 1f - RowPressDepth * touch.squish[index].value
                scaleX = scale
                scaleY = scale
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (item.icon != null) Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
        Text(item.label, style = labelStyle, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (item.trailing != null) {
            Text(item.trailing, style = detailStyle, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        if (item.checked) Canvas(Modifier.size(CheckDot)) { drawCircle(colors.accent) }
    }
}

/**
 * What a finger does to the menu, its own or handed off: which item it lights, how the menu stretches,
 * and what lifting does. Positions are the menu's own pixels; the rows', from where they are laid out.
 */
@Stable
internal class MenuTouch(count: Int) {
    val light = IslandLight(count)
    val squish = List(count) { Squish() }

    /** Signed, in pixels: below 0 the top is pulled up, above 0 the bottom is pulled down. */
    val stretch = Animatable(0f)

    lateinit var scope: CoroutineScope
    var haptics: BounceHaptics? = null
    lateinit var density: androidx.compose.ui.unit.Density
    var enabled: (Int) -> Boolean = { false }
    var act: (Int) -> Unit = {}
    var coordinates: LayoutCoordinates? = null

    // Where every entry is laid out, before the stretch carries it, and which entry each item is.
    private val entries = HashMap<Int, LayoutCoordinates>()
    val rowEntry = IntArray(count)

    private var active = false
    private var entered = false
    private var index = -1

    fun place(entry: Int, coordinates: LayoutCoordinates) {
        entries[entry] = coordinates
    }

    /** Entry [entry] at rest, in the menu's pixels, or null before it is laid out. */
    private fun entryRect(entry: Int): Rect? {
        val menu = coordinates?.takeIf { it.isAttached } ?: return null
        val it = entries[entry]?.takeIf { it.isAttached } ?: return null
        return menu.localBoundingBoxOf(it, clipBounds = false)
    }

    /** How far entry [entry] is carried by the stretch: all of it at the pulled edge, none at the other. */
    fun shift(entry: Int): Float {
        val s = stretch.value
        val height = coordinates?.size?.height ?: 0
        if (s == 0f || height <= 0) return 0f
        val center = (entryRect(entry) ?: return 0f).center.y / height
        return s * (if (s < 0f) 1f - center else center)
    }

    /** The body this frame, in a [width] × [height] menu: longer on the pulled side, a little narrower. */
    fun body(width: Float, height: Float): Rect {
        val s = stretch.value
        val narrow = abs(s) * Narrowing
        return Rect(narrow, min(s, 0f), width - narrow, height + max(s, 0f))
    }

    /** Item [index]'s lit capsule this frame: its row, carried by the stretch. */
    fun litPlate(index: Int): Rect {
        val entry = rowEntry.getOrElse(index) { return Rect.Zero }
        return (entryRect(entry) ?: return Rect.Zero).translate(0f, shift(entry))
    }

    private fun rowAt(at: Offset): Int {
        val size = coordinates?.size ?: return -1
        val margin = with(density) { LetGoMargin.toPx() }
        if (at.x < -margin || at.x > size.width + margin) return -1
        for (i in rowEntry.indices) {
            val row = entryRect(rowEntry[i]) ?: continue
            if (at.y >= row.top && at.y < row.bottom) return i
        }
        return -1
    }

    /** A finger of its own lands on the menu; a handed-off one is [start]ed wherever it is when the menu opens. */
    fun start(at: Offset, time: Long) {
        active = true
        entered = isInside(at)
        light.moveTo(at, scope, glide = light.on.value > 0f)
        hover(at)
        pull(at)
    }

    fun followInWindow(atInWindow: Offset, time: Long) {
        val menu = coordinates?.takeIf { it.isAttached } ?: return
        val at = menu.windowToLocal(atInWindow)
        if (active) follow(at, time) else start(at, time)
    }

    fun follow(at: Offset, time: Long) {
        // A handed-off finger pulls the menu only once it has been on it: it starts on what opened it.
        if (!entered) entered = isInside(at)
        light.moveTo(at, scope, glide = true)
        hover(at)
        pull(at)
    }

    // Into another item: it lights and sinks, the one before lets go quietly, and the finger feels it.
    private fun hover(at: Offset) {
        val next = rowAt(at)
        if (next == index) return
        if (index >= 0) squish[index].let { left -> scope.launch { left.cancel() } }
        index = next
        light.light(next, scope)
        if (next >= 0 && enabled(next)) {
            haptics?.press()
            scope.launch { squish[next].pressIn(LumenMotion.FastSpatial) }
        }
    }

    private fun isInside(at: Offset): Boolean {
        val size = coordinates?.size ?: return false
        return at.x >= 0f && at.y >= 0f && at.x <= size.width && at.y <= size.height
    }

    // The menu leans towards where the finger is, and stays leant until it lifts: towards the top above the
    // middle, towards the bottom below it, and past an edge further still, less and less.
    private fun pull(at: Offset) {
        if (!entered) return
        val size = coordinates?.size ?: return
        val margin = with(density) { LetGoMargin.toPx() }
        val target = if (at.x < -margin || at.x > size.width + margin) {
            0f
        } else {
            menuStretch(at.y, size.height.toFloat(), with(density) { InsideStretch.toPx() }, with(density) { MaxStretch.toPx() })
        }
        if (target == stretch.targetValue && stretch.isRunning) return
        scope.launch { stretch.animateTo(target, StretchFollow) }
    }

    fun end(chosen: Boolean) {
        if (!active) return
        active = false
        val picked = if (chosen) index else -1
        if (index >= 0) {
            val squish = squish[index]
            scope.launch {
                if (picked >= 0 && enabled(picked)) squish.release() else squish.cancel()
            }
        }
        index = -1
        light.light(-1, scope)
        val from = stretch.value
        if (from != 0f) {
            scope.launch {
                val full = with(density) { MaxStretch.toPx() }
                val watch = ReboundWatch(0f) { count, overshoot ->
                    haptics?.bounce(bounceStrength(overshoot, full * ReleaseFullBounce, full * ReleaseBounceFloor), landing = count == 1)
                }
                stretch.animateTo(0f, StretchRelease) { watch.frame(value, velocity) }
            }
        }
        if (picked >= 0 && enabled(picked)) act(picked)
    }

    fun focus(index: Int, focused: Boolean) {
        light.focus(index, focused, litPlate(index).center, scope)
    }
}

private data class HandOffElement(val handOff: LightMenuHandOff) : ModifierNodeElement<HandOffNode>() {
    override fun create(): HandOffNode = HandOffNode(handOff)

    override fun update(node: HandOffNode) {
        node.handOff = handOff
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "lightMenuHandOff"
    }
}

/** Watches the finger on what opens the menu, without taking it, and hands where it goes to the menu. */
private class HandOffNode(var handOff: LightMenuHandOff) : Modifier.Node(), PointerInputModifierNode, LayoutAwareModifierNode {
    private var coordinates: LayoutCoordinates? = null
    private var finger: PointerId? = null

    override fun onPlaced(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Final) return
        val tracked = finger
        val change = if (tracked != null) {
            pointerEvent.changes.firstOrNull { it.id == tracked }
        } else {
            pointerEvent.changes.firstOrNull { it.changedToDownIgnoreConsumed() }?.also { finger = it.id }
        } ?: return
        val at = coordinates?.takeIf { it.isAttached }?.localToWindow(change.position) ?: return
        if (change.pressed) {
            handOff.follow(at, change.uptimeMillis)
        } else {
            finger = null
            handOff.lift()
        }
    }

    override fun onCancelPointerInput() {
        if (finger != null) handOff.cancel()
        finger = null
    }
}

/** Between the menu's edge and its rows, all the way round: what makes a lit row concentric with the menu. */
private val MenuInset = 6.dp

private val CheckDot = 7.dp
private const val SubtitleAlpha = 0.6f
private const val DisabledAlpha = 0.38f

/** A row under the finger sinks this much: a wide row reads a tab's whole press as far too much. */
private const val RowPressDepth = 0.035f

/** The finger's light reaches about this many rows round it. */
private const val LightReachRows = 2.5f

/** How far off the side the finger may wander and still be on the menu, as on the islands. */
private val LetGoMargin = 48.dp

/**
 * How far the menu leans towards a finger on it: [InsideStretch] with the finger at its top or bottom edge,
 * none at its middle, in proportion in between; past an edge it goes on after the finger towards
 * [MaxStretch], less and less, resisting 1/[StretchStiffness] of the way at first.
 */
private val InsideStretch = 6.dp
private val MaxStretch = 16.dp
private const val StretchStiffness = 2f

/**
 * The stretch, signed (below 0 the top is pulled up), for a finger at [y] on a menu [height] tall: [inside]
 * at either edge, growing in proportion from none at the middle, then on from there towards [most] past the
 * edge, giving 1/[stiffness] of the finger's way at first and less and less.
 */
internal fun menuStretch(y: Float, height: Float, inside: Float, most: Float, stiffness: Float = StretchStiffness): Float {
    if (height <= 0f) return 0f
    val half = height / 2f
    val off = y - half
    if (abs(off) <= half) return inside * off / half
    val room = max(most - inside, 0f)
    val beyond = abs(off) - half
    val give = if (room > 0f) inside + room * (1f - exp(-beyond / (room * stiffness))) else inside
    return if (off < 0f) -give else give
}

/** The body narrows by this share of its stretch on each side: it keeps some of its volume. */
private const val Narrowing = 0.12f

/** The stretch after the finger: quick and without bounce, so it reads as the menu held, not thrown. */
private val StretchFollow = spring<Float>(dampingRatio = 1f, stiffness = 900f)

/** Let go: back with a wobble, spring(0.45, 380), felt as the bar's selected tab is poked. */
private val StretchRelease = spring<Float>(dampingRatio = 0.45f, stiffness = 380f)
private const val ReleaseFullBounce = 0.5f
private const val ReleaseBounceFloor = 0.06f
