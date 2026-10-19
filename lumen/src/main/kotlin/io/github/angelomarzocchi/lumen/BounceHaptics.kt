package io.github.angelomarzocchi.lumen

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Haptics that follow a spring, so the finger feels what the eye sees: something soft pressed in, a
 * landing, and every rebound after it fainter than the last.
 *
 * One click for the whole gesture cannot do that. A bounce dying away is a strength that falls, and
 * the only haptics whose strength can be set touch by touch are [VibrationEffect.Composition]'s
 * primitives (API 30). Where the device has them that is what plays. Where it does not — older
 * Android, or a vibrator that cannot play primitives — the view's own feedback stands in, and only
 * for the touches strong enough to be worth one: a row of identical clicks reads as a buzz, not a
 * bounce.
 *
 * Every vibration is a touch vibration, so the user's touch-feedback setting turns it down or off
 * exactly as it does the keyboard's.
 */
@Stable
public class BounceHaptics internal constructor(private val view: View) {

    private val vibrator: Vibrator? = if (view.isInEditMode) null else view.context.defaultVibrator()

    private val primitives: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator?.areAllPrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_TICK,
        ) == true

    private val lowTick: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && primitives &&
            vibrator?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK) == true

    private var lastPulseAt = 0L

    /** A finger landing on something soft: the faintest thing this class plays. */
    public fun press() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lowTick ->
                play(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, PressStrength)
            // A tick is sharper than a low tick, so it plays softer to sit at the same weight.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && primitives ->
                play(VibrationEffect.Composition.PRIMITIVE_TICK, PressStrength / 2f)
            else -> fallback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /**
     * One rebound, [strength] from 0 to 1 — see [bounceStrength] for turning an overshoot into one.
     * The [landing] is the first and hardest contact and plays as a click; every rebound after it is
     * a tick.
     */
    public fun bounce(strength: Float, landing: Boolean) {
        if (strength <= 0f) return
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && primitives -> play(
                if (landing) VibrationEffect.Composition.PRIMITIVE_CLICK else VibrationEffect.Composition.PRIMITIVE_TICK,
                strength,
            )
            landing -> fallback(HapticFeedbackConstants.VIRTUAL_KEY)
            strength >= FallbackTickStrength -> fallback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun play(primitive: Int, strength: Float) {
        val vibrator = vibrator ?: return
        if (!touchFeedbackOn() || !due()) return
        val effect = VibrationEffect.startComposition()
            .addPrimitive(primitive, strength.coerceIn(0f, 1f))
            .compose()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, TouchAudioAttributes)
        }
    }

    // The view checks the user's setting itself.
    private fun fallback(feedback: Int) {
        if (view.isInEditMode || !due()) return
        view.performHapticFeedback(feedback)
    }

    /**
     * Pulses closer than [MinPulseGapMillis] blur into one: the later one is dropped rather than
     * smearing the first.
     */
    private fun due(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastPulseAt < MinPulseGapMillis) return false
        lastPulseAt = now
        return true
    }

    // The vibrator does not know about the view's flag, nor — before Android 13 put touch
    // vibrations under the touch-feedback intensity — about the system's switch.
    @Suppress("DEPRECATION") // HAPTIC_FEEDBACK_ENABLED: still the switch read before Android 13
    private fun touchFeedbackOn(): Boolean =
        view.isHapticFeedbackEnabled &&
            Settings.System.getInt(view.context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
}

@Composable
public fun rememberBounceHaptics(): BounceHaptics {
    val view = LocalView.current
    return remember(view) { BounceHaptics(view) }
}

/**
 * How hard a rebound [overshoot] past its target is felt: nothing below [floor] — too small to see,
 * so too small to feel — full strength from [full] on, and a square root in between, because the
 * hand reads a faint tick as much more than its share of a click.
 */
public fun bounceStrength(overshoot: Float, full: Float, floor: Float): Float =
    if (overshoot < floor) 0f else sqrt((overshoot / full).coerceIn(0f, 1f))

/**
 * Spots the moments a spring turns round past its [target] — the peaks of its overshoot, where it
 * pushes back hardest — and reports each one with how far past the target it got. Fed one frame at a
 * time from an `animateTo` block.
 *
 * A turn before the target was first reached is not a rebound but an interrupted move reversing out
 * of where it was going, and is not reported.
 */
public class ReboundWatch(
    private val target: Float,
    private val onRebound: (count: Int, overshoot: Float) -> Unit,
) {
    /** The rebounds reported so far. */
    public var count: Int = 0
        private set

    private var side = 0f
    private var reached = false
    private var heading = 0f

    public fun frame(value: Float, velocity: Float) {
        val nowSide = sign(value - target)
        if (nowSide == 0f || (side != 0f && nowSide != side)) reached = true
        if (nowSide != 0f) side = nowSide

        val nowHeading = sign(velocity)
        if (reached && heading != 0f && nowHeading != 0f && nowHeading != heading) {
            count++
            onRebound(count, abs(value - target))
        }
        if (nowHeading != 0f) heading = nowHeading
    }
}

private fun Context.defaultVibrator(): Vibrator? {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        getSystemService(Vibrator::class.java)
    }
    return vibrator?.takeIf { it.hasVibrator() }
}

/** How a touch vibration was labelled before [VibrationAttributes]: the usage the system maps to touch. */
private val TouchAudioAttributes: AudioAttributes by lazy(LazyThreadSafetyMode.NONE) {
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
}

private const val PressStrength = 0.7f

/** Without primitives every tick is the same tick: only a rebound this strong earns one. */
private const val FallbackTickStrength = 0.4f

private const val MinPulseGapMillis = 35L
