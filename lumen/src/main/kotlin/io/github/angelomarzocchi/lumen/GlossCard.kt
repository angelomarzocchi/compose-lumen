package io.github.angelomarzocchi.lumen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The colours of a [GlossCard]: its face, a gradient from [top] through [middle] to [bottom]; the
 * [slab] that shows its thickness when it tilts; [content] for what is printed on it; and its two
 * shadows, the soft [shadow] that moves away from the light and the tight [contactShadow] under it.
 */
@Immutable
public data class GlossCardColors(
    val top: Color,
    val middle: Color,
    val bottom: Color,
    val slab: Color,
    val content: Color,
    val shadow: Color,
    val contactShadow: Color,
)

/** Defaults for [GlossCard]. */
public object GlossCardDefaults {
    /** A card in tones of [base]: the theme's primary colour by default. */
    @Composable
    @ReadOnlyComposable
    public fun colors(
        base: Color = MaterialTheme.colorScheme.primary,
        content: Color = Color.White,
    ): GlossCardColors = GlossCardColors(
        top = lerp(base, Color.White, 0.18f),
        middle = base,
        bottom = lerp(base, Color.Black, 0.18f),
        slab = lerp(base, Color.Black, 0.55f),
        content = content,
        shadow = lerp(base, Color.Black, 0.6f).copy(alpha = 0.65f),
        contactShadow = lerp(base, Color.Black, 0.6f).copy(alpha = 0.35f),
    )

    /** The violet card of the app these components come from. */
    public val Violet: GlossCardColors = GlossCardColors(
        top = Color(0xFF7A6DB0),
        middle = Color(0xFF625690),
        bottom = Color(0xFF51467F),
        slab = Color(0xFF2F2752),
        content = Color.White,
        shadow = Color(0xA6281E50),
        contactShadow = Color(0x59281E50),
    )
}

/**
 * A raised card that answers the finger with light.
 *
 * Put a finger on its face and it tilts towards it, in 3D: the gloss on its face slides under the
 * finger, the slab underneath shows the card's thickness on the side it tilts away from, and the
 * shadow moves away from the light. Lift, and it settles back flat with a small bounce. With
 * [tiltWithDevice] it also answers the phone turning in the hand — the turn, not the angle it is held
 * at — and gives way to the finger whenever one is on it.
 *
 * It does not take a scroll: inside a list, a finger that sets off up or down scrolls the list, and
 * the card settles back.
 *
 * @param shape the card's shape.
 * @param colors the face, the slab, the content and the shadows.
 * @param tiltOnTouch whether a finger tilts it.
 * @param tiltWithDevice whether the device's own turning tilts it, through the rotation sensor; off in
 *   battery saver.
 * @param onClick a tap on the face; null for a card that only tilts.
 */
@Composable
public fun GlossCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    colors: GlossCardColors = GlossCardDefaults.colors(),
    tiltOnTouch: Boolean = true,
    tiltWithDevice: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    GlossCard(remember { CardTilt() }, modifier, shape, colors, tiltOnTouch, tiltWithDevice, onClick, content)
}

/** [GlossCard], on a [tilt] its caller can read: for tests. */
@Composable
internal fun GlossCard(
    tilt: CardTilt,
    modifier: Modifier,
    shape: Shape,
    colors: GlossCardColors,
    tiltOnTouch: Boolean,
    tiltWithDevice: Boolean,
    onClick: (() -> Unit)?,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val click by rememberUpdatedState(onClick)
    tilt.gyro = rememberGyroTilt(enabled = tiltWithDevice, idle = { tilt.touched.value == 0f })

    Box(
        modifier
            .graphicsLayer {
                cameraDistance = CameraDistanceFactor * density
                rotationX = tilt.x
                rotationY = tilt.y
            }
            // The shadow moves away from the light: opposite to the tilt.
            .dropShadow(shape) {
                radius = 50.dp.toPx()
                spread = (-22).dp.toPx()
                offset = Offset(-tilt.y * 1.4f * density, (30f + tilt.x * 1.2f) * density)
                color = colors.shadow
            }
            .dropShadow(shape) {
                radius = 18.dp.toPx()
                spread = (-8).dp.toPx()
                offset = Offset(0f, 10.dp.toPx())
                color = colors.contactShadow
            }
            .then(
                if (onClick != null) {
                    Modifier.semantics {
                        role = Role.Button
                        onClick {
                            click?.invoke()
                            true
                        }
                    }
                } else {
                    Modifier
                },
            )
            .pointerInput(tiltOnTouch) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    fun follow(at: Offset) {
                        if (!tiltOnTouch || size.width == 0 || size.height == 0) return
                        val nx = (at.x / size.width - .5f).coerceIn(-.5f, .5f)
                        val ny = (at.y / size.height - .5f).coerceIn(-.5f, .5f)
                        scope.launch { tilt.rx.animateTo(-ny * MaxTiltX, LumenMotion.Follow) }
                        scope.launch { tilt.ry.animateTo(nx * MaxTiltY, LumenMotion.Follow) }
                        scope.launch { tilt.gx.animateTo(nx + .5f, LumenMotion.Follow) }
                        scope.launch { tilt.gy.animateTo(ny + .5f, LumenMotion.Follow) }
                    }
                    fun letGo() {
                        scope.launch { tilt.rx.animateTo(0f, LumenMotion.Settle) }
                        scope.launch { tilt.ry.animateTo(0f, LumenMotion.Settle) }
                        scope.launch { tilt.gx.animateTo(RestGlossX, LumenMotion.Settle) }
                        scope.launch { tilt.gy.animateTo(0f, LumenMotion.Settle) }
                        scope.launch { tilt.touched.animateTo(0f, TouchFade) }
                    }
                    if (tiltOnTouch) scope.launch { tilt.touched.animateTo(1f, TouchFade) }
                    follow(down.position)
                    var tap = true
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        // Something round the card took the gesture: a scroll, not a tilt or a tap.
                        if (change.isConsumed) {
                            tap = false
                            break
                        }
                        if (!change.pressed) {
                            if (tap && click != null) {
                                change.consume()
                                click?.invoke()
                            }
                            break
                        }
                        val travel = change.position - down.position
                        if (abs(travel.x) > slop || abs(travel.y) > slop) tap = false
                        follow(change.position)
                    }
                    letGo()
                }
            },
        propagateMinConstraints = false,
    ) {
        GlossCardSurface(
            shape = shape,
            colors = colors,
            slabShift = { Offset(-tilt.y * 0.45f, tilt.x * 0.45f) },
            glossCenter = { Offset(tilt.glossX, tilt.glossY) },
            glossAlpha = { .55f + .45f * tilt.touched.value },
        )
        CompositionLocalProvider(LocalContentColor provides colors.content) {
            content()
        }
    }
}

/**
 * The card's body, without what is printed on it: the slab that shows its thickness when it tilts,
 * the gradient face with the gloss that follows the finger, the edge light along its top and a darker
 * line along its bottom. It fills the box it is put in.
 *
 * [GlossCard] draws its own. This is for a card that moves in ways of its own — in a stack, opened into
 * a sheet — and tilts itself: it hands over where the tilt has the slab and the gloss this frame. The
 * lambdas are read in layer and draw blocks only.
 *
 * @param slabShift how far the slab shows past the face, in dp: `Offset(-tiltY, tiltX) * 0.45f` for a
 *   card tilted by `rotationX = tiltX`, `rotationY = tiltY`, and zero flat.
 * @param glossCenter the gloss's centre, as a fraction of the size: `Offset(0.3f, 0f)` at rest.
 * @param glossAlpha how bright the gloss is, 0 to 1: brighter while a finger is on the face.
 */
@Composable
public fun BoxScope.GlossCardSurface(
    shape: Shape,
    colors: GlossCardColors,
    slabShift: () -> Offset,
    glossCenter: () -> Offset,
    glossAlpha: () -> Float,
) {
    Box(
        Modifier
            .matchParentSize()
            .graphicsLayer {
                val shift = slabShift()
                translationX = shift.x * density
                translationY = (shift.y + 2f) * density
            }
            .clip(shape)
            .background(colors.slab),
    )
    Box(
        Modifier
            .matchParentSize()
            .clip(shape)
            .drawWithContent {
                // A 0×0 pass would hand RadialGradient a zero radius, which it rejects.
                if (size.minDimension <= 0f) return@drawWithContent
                drawRect(
                    Brush.linearGradient(
                        0f to colors.top,
                        0.45f to colors.middle,
                        1f to colors.bottom,
                        start = Offset(size.width * 0.37f, 0f),
                        end = Offset(size.width * 0.63f, size.height),
                    ),
                )
                val center = glossCenter()
                // Skia rejects a gradient whose centre is not finite, with an exception and not a
                // blank: the gloss is skipped instead.
                if (center.x.isFinite() && center.y.isFinite()) {
                    drawRect(
                        Brush.radialGradient(
                            0f to Color.White.copy(alpha = .34f * glossAlpha()),
                            1f to Color.Transparent,
                            center = Offset(center.x * size.width, center.y * size.height),
                            radius = size.maxDimension * .58f,
                        ),
                    )
                }
                // The edge light along the top: a reflection that catches the light across the middle
                // and dies out before the corners. Full width and hard-ended it would read as a seam.
                val edge = Color.White.copy(alpha = EdgeLightAlpha)
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        EdgeLightFade to edge,
                        1f - EdgeLightFade to edge,
                        1f to Color.Transparent,
                    ),
                    size = size.copy(height = 1.dp.toPx()),
                )
                drawRect(
                    Color.Black.copy(alpha = .16f),
                    topLeft = Offset(0f, size.height - 2.dp.toPx()),
                    size = size.copy(height = 2.dp.toPx()),
                )
                drawContent()
            },
    )
}

/**
 * How a card is tilted: [rx] and [ry] by the finger, [gyro] by the device at rest. What it wears is
 * their sum, with the device's fading out while a finger is on the face: the finger always wins.
 */
@Stable
internal class CardTilt {
    val rx = Animatable(0f)
    val ry = Animatable(0f)
    val gx = Animatable(RestGlossX)
    val gy = Animatable(0f)
    val touched = Animatable(0f)
    var gyro: GyroTilt? = null

    private val device: Float get() = 1f - touched.value
    val x: Float get() = rx.value + (gyro?.rotationX ?: 0f) * device
    val y: Float get() = ry.value + (gyro?.rotationY ?: 0f) * device

    /** The gloss follows the device's light the same way it follows the finger. */
    val glossX: Float get() = gx.value + (gyro?.rotationY ?: 0f) / 16f * device
    val glossY: Float get() = (gy.value - (gyro?.rotationX ?: 0f) / 14f * device).coerceAtLeast(0f)
}

/** Where the gloss rests with nothing on the card: high, and a little to the left. */
private const val RestGlossX = .30f

/** The most a finger tilts the card, in degrees, at its top or bottom edge and at its sides. */
private const val MaxTiltX = 14f
private const val MaxTiltY = 16f

private const val CameraDistanceFactor = 12f
private const val EdgeLightAlpha = .26f
private const val EdgeLightFade = .14f
private val TouchFade = tween<Float>(durationMillis = 300)
