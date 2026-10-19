package io.github.angelomarzocchi.lumen

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * The colours of a row of lit tabs or a floating navigation bar.
 *
 * @param island the raised surface the tabs sit in.
 * @param plate the selected tab's plate, and [onPlate] its icon and label.
 * @param content an unselected tab's icon and label.
 * @param lit an unselected tab's icon and label while the finger's light is on it.
 * @param light how strong the finger's light is on this island: see [FingerLight].
 */
@Immutable
public class LumenColors(
    public val island: Color,
    public val plate: Color,
    public val onPlate: Color,
    public val content: Color,
    public val lit: Color,
    public val light: FingerLight = FingerLight.forSurface(island),
) {
    public fun copy(
        island: Color = this.island,
        plate: Color = this.plate,
        onPlate: Color = this.onPlate,
        content: Color = this.content,
        lit: Color = this.lit,
        light: FingerLight = this.light,
    ): LumenColors = LumenColors(island, plate, onPlate, content, lit, light)

    override fun equals(other: Any?): Boolean =
        other is LumenColors && island == other.island && plate == other.plate && onPlate == other.onPlate &&
            content == other.content && lit == other.lit && light == other.light

    override fun hashCode(): Int {
        var result = island.hashCode()
        result = 31 * result + plate.hashCode()
        result = 31 * result + onPlate.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + lit.hashCode()
        result = 31 * result + light.hashCode()
        return result
    }
}

/**
 * How strong the light a finger carries is: on a dark island about a card's own gloss; on a light one a
 * good deal stronger, or a faint white would vanish into a surface that is nearly white already.
 *
 * @param spill the glow round the finger, at its centre.
 * @param core the lit tab, under the finger; [edge] the same tab at its far side.
 * @param onPlate the share of it that reaches the selected tab's plate: on a light island the plate is
 *   a pale tone of the accent, which the full light would wash out to white.
 */
@Immutable
public data class FingerLight(val spill: Float, val core: Float, val edge: Float, val onPlate: Float) {
    public companion object {
        /** For a light island. */
        public val Day: FingerLight = FingerLight(spill = 0.45f, core = 0.95f, edge = 0.6f, onPlate = 0.4f)

        /** For a dark island. */
        public val Night: FingerLight = FingerLight(spill = 0.1f, core = 0.16f, edge = 0.07f, onPlate = 1f)

        /** [Night] on a dark [surface], [Day] on a light one. */
        public fun forSurface(surface: Color): FingerLight = if (surface.luminance() < 0.5f) Night else Day
    }
}

/** Defaults read from the Material theme. */
public object LumenDefaults {
    /**
     * The island `surfaceContainerHigh`, the plate `primaryContainer` with `onPrimaryContainer` on it,
     * the tabs `onSurfaceVariant`, and `primary` for a tab the finger lights.
     */
    @Composable
    @ReadOnlyComposable
    public fun colors(
        island: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
        plate: Color = MaterialTheme.colorScheme.primaryContainer,
        onPlate: Color = MaterialTheme.colorScheme.onPrimaryContainer,
        content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        lit: Color = MaterialTheme.colorScheme.primary,
        light: FingerLight = FingerLight.forSurface(island),
    ): LumenColors = LumenColors(island, plate, onPlate, content, lit, light)
}

/**
 * The springs everything here moves on, so nothing depends on an experimental motion scheme: these are
 * Material 3 Expressive's own numbers.
 */
internal object LumenMotion {
    /** A press, or anything else that follows the finger down: fast, with a hint of give. */
    val FastSpatial: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.6f, stiffness = 800f)

    /** Colours: no bounce. */
    fun <T> effects(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1600f)

    /** The light following the finger: stiff and without bounce, so it keeps up. */
    val Follow: FiniteAnimationSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 900f)

    /** Back to rest once the finger lifts, with a small bounce. */
    val Settle: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.72f, stiffness = 380f)
}
