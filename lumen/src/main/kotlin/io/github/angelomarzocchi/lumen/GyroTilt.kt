package io.github.angelomarzocchi.lumen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/*
 * A card's idle tilt: the gyroscope, low-pass filtered (α = 0.1), clamped to ±6°, and off in battery
 * saver.
 *
 * What it follows is the phone *moving*, not the angle it is held at. A card tilted by the absolute
 * orientation would sit at its 6° limit the whole time somebody reads in bed or at a desk, which is
 * every time; so the rest pose drifts after the phone (about a second to catch up), and the card
 * answers the turn of the wrist and then settles flat again.
 */

/**
 * The filter, without Android in it: feed it the phone's pitch and roll in degrees at every sensor
 * sample, read back the card's tilt.
 */
public class GyroTiltFilter(
    private val alpha: Float = TiltAlpha,
    private val restAlpha: Float = RestAlpha,
    private val limit: Float = TiltLimitDegrees,
) {
    /** The card's rotationX and rotationY, in degrees. */
    public var rotationX: Float = 0f
        private set
    public var rotationY: Float = 0f
        private set

    private var restPitch = Float.NaN
    private var restRoll = Float.NaN

    /**
     * One sample. [idle] false (a finger on the card, the card moving for another reason) eases the
     * tilt back to flat instead of following: one thing moves at a time.
     */
    public fun update(pitchDegrees: Float, rollDegrees: Float, idle: Boolean) {
        // getOrientation can hand back NaN (an emulator's virtual sensor does): one such sample
        // would stick in the filter for good, and a NaN gloss centre crashes RadialGradient.
        if (!pitchDegrees.isFinite() || !rollDegrees.isFinite()) return
        if (restPitch.isNaN()) {
            restPitch = pitchDegrees
            restRoll = rollDegrees
        }
        restPitch += wrap(pitchDegrees - restPitch) * restAlpha
        restRoll += wrap(rollDegrees - restRoll) * restAlpha
        val targetX = if (idle) (-wrap(pitchDegrees - restPitch)).coerceIn(-limit, limit) else 0f
        val targetY = if (idle) wrap(rollDegrees - restRoll).coerceIn(-limit, limit) else 0f
        rotationX += (targetX - rotationX) * alpha
        rotationY += (targetY - rotationY) * alpha
    }

    /** Forget the rest pose: the next sample is the new "flat". */
    public fun reset() {
        restPitch = Float.NaN
        restRoll = Float.NaN
        rotationX = 0f
        rotationY = 0f
    }

    /** The shortest way round, so a roll crossing ±180° is a small step and not a full turn. */
    private fun wrap(degrees: Float): Float {
        var d = degrees % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    public companion object {
        public const val TiltAlpha: Float = .1f

        /** About a second, at the ~50 samples a second of SENSOR_DELAY_GAME, for the rest pose to follow. */
        public const val RestAlpha: Float = .02f
        public const val TiltLimitDegrees: Float = 6f
    }
}

/** The tilt the sensor asks for, in degrees: read it inside `graphicsLayer`, never in composition. */
@Stable
public class GyroTilt {
    public var rotationX: Float by mutableFloatStateOf(0f)
        internal set
    public var rotationY: Float by mutableFloatStateOf(0f)
        internal set
}

/**
 * Listens to `TYPE_GAME_ROTATION_VECTOR` while [enabled], the screen is resumed and battery saver is
 * off; otherwise the tilt stays flat and nothing is registered. [idle] is asked at every sample.
 */
@Composable
public fun rememberGyroTilt(enabled: Boolean, idle: () -> Boolean = { true }): GyroTilt {
    val tilt = remember { GyroTilt() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isIdle by rememberUpdatedState(idle)
    val powerSave = rememberPowerSaveMode()
    val active = enabled && !powerSave && !LocalInspectionMode.current

    DisposableEffect(active, lifecycleOwner) {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (!active || sensors == null || sensor == null) {
            tilt.rotationX = 0f
            tilt.rotationY = 0f
            return@DisposableEffect onDispose { }
        }
        val filter = GyroTiltFilter()
        val matrix = FloatArray(9)
        val angles = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                SensorManager.getOrientation(matrix, angles)
                filter.update(
                    pitchDegrees = Math.toDegrees(angles[1].toDouble()).toFloat(),
                    rollDegrees = Math.toDegrees(angles[2].toDouble()).toFloat(),
                    idle = isIdle(),
                )
                // Only a change the eye could see is written: the filter creeps towards its target
                // by ever smaller steps, and every write redraws the card.
                tilt.rotationX = settled(tilt.rotationX, filter.rotationX)
                tilt.rotationY = settled(tilt.rotationY, filter.rotationY)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        fun start() = sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        fun stop() {
            sensors.unregisterListener(listener)
            // Back from the background the phone is in another hand, another pose.
            filter.reset()
            tilt.rotationX = 0f
            tilt.rotationY = 0f
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE -> stop()
                else -> Unit
            }
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stop()
        }
    }
    return tilt
}

/**
 * [next], unless it is within [DeadBandDegrees] of [current]; and flat once it is that close to flat.
 * With the phone still the card stops redrawing, instead of chasing the filter's last hundredths.
 */
internal fun settled(current: Float, next: Float): Float = when {
    kotlin.math.abs(next) < DeadBandDegrees -> 0f
    kotlin.math.abs(next - current) < DeadBandDegrees -> current
    else -> next
}

private const val DeadBandDegrees = .02f

/** Whether this device has the sensor the tilt needs: offer a switch for it only if so. */
@Composable
public fun rememberHasTiltSensor(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
            ?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null
    }
}

/** Battery saver, kept current while the screen is on. */
@Composable
private fun rememberPowerSaveMode(): Boolean {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    var saving by remember { mutableStateOf(power?.isPowerSaveMode == true) }
    DisposableEffect(power) {
        if (power == null) return@DisposableEffect onDispose { }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                saving = power.isPowerSaveMode
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        saving = power.isPowerSaveMode
        onDispose { context.unregisterReceiver(receiver) }
    }
    return saving
}
