package io.github.angelomarzocchi.lumen

import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density

/**
 * A material for [FloatingNavBar]'s island other than its flat colour: glass, a blur, a texture.
 *
 * The material draws the island's whole body, and the selected tab with it — as a light inside, a
 * stain, whatever it likes: the bar lays no plate of its own over a material. It may also give the
 * marks and labels a render effect, to engrave them in it.
 *
 * Everything is called from the draw and layer phases, every frame the island changes: allocate
 * nothing per call that you can keep.
 *
 * See the README for the recipe that puts the bar in `compose-glass`.
 */
@Stable
public interface IslandMaterial {
    /** Applied to the island where its own bounds are laid out: for a material that has to know where it is. */
    public val modifier: Modifier get() = Modifier

    /**
     * The island's body over [shape], in the island's own pixels: a capsule of [radius], which may
     * reach [overhang] past the island's bounds at either end while its plate pushes it out.
     */
    public fun DrawScope.drawIsland(shape: Rect, radius: Float, overhang: Float, lights: IslandLights)

    /** A render effect for what the island holds — its marks and labels — or null for none. */
    public fun contentEffect(density: Density, shape: Rect, radius: Float, lights: IslandLights): RenderEffect? = null
}

/**
 * The lights on an island this frame, in its own pixels: the finger's ([lamp], [lampOn] from 0 to 1)
 * and the selected tab's plate ([selection]), with the colours the bar would use for them.
 */
public class IslandLights internal constructor() {
    public var lamp: Offset = Offset.Zero
        internal set
    public var lampOn: Float = 0f
        internal set
    public var selection: Rect = Rect.Zero
        internal set

    /** The accent: what the selection is lit with, and what a mark turns under the finger on a light island. */
    public var accent: Color = Color.Unspecified
        internal set

    /** Whether the island is dark: light adds up on a dark island, and shows as colour on a light one. */
    public var dark: Boolean = false
        internal set
}
