package io.github.angelomarzocchi.lumen.demo

import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import io.github.angelomarzocchi.lumen.FloatingNavBar
import io.github.angelomarzocchi.lumen.FloatingNavItem
import io.github.angelomarzocchi.lumen.rememberFloatingNavBarState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The floating navigation bar, played by itself for a screen recording: a finger (the white disc)
 * taps, slides along the bar carrying the light, slides off it, and the page scrolls so the bar folds.
 *
 * The touches are real touch events dispatched into the window, so the bar answers exactly as it does
 * under a finger. Start it with `adb shell am start -n io.github.angelomarzocchi.lumen.demo/.ShowcaseActivity`.
 */
class ShowcaseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF5B4BA8), primaryContainer = Color(0xFFE3DDFF))) {
                Showcase()
            }
        }
    }
}

/** The finger as the viewer sees it: where, and whether it is down. */
private class Finger {
    var at by mutableStateOf(Offset.Zero)
    var down by mutableStateOf(false)
}

@Composable
private fun Showcase() {
    val list = rememberLazyListState()
    val barState = rememberFloatingNavBarState()
    var section by remember { mutableIntStateOf(0) }
    var folded by remember { mutableStateOf(false) }
    val finger = remember { Finger() }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current

    LaunchedEffect(list) {
        var last = 0
        snapshotFlow { list.firstVisibleItemIndex * 10_000 + list.firstVisibleItemScrollOffset }.collect { now ->
            if (abs(now - last) > 8) {
                folded = now > last && now > 0
                last = now
            }
        }
    }

    LaunchedEffect(Unit) {
        val touch = Touch(view, finger)
        delay(1500)
        while (true) {
            fun tab(i: Int): Offset = barState.tabInWindow(i)?.center ?: Offset.Zero

            // A press on the selected tab: the plate is pushed in and springs back.
            touch.tap(tab(0), holdMillis = 350)
            delay(900)

            // Slide along the bar: the light goes with the finger, lighting each tab it crosses.
            touch.down(tab(0))
            touch.slide(tab(0), tab(3), millis = 1500)
            delay(250)
            touch.slide(tab(3), tab(2), millis = 600)
            delay(200)
            touch.up()
            delay(1300)

            // Quick taps: the plate stretches across and lands with a bounce.
            touch.tap(tab(0))
            delay(1000)
            touch.tap(tab(3))
            delay(1000)
            touch.tap(tab(1))
            delay(1200)

            // Off the island the light goes out, and lifting picks nothing.
            touch.down(tab(1))
            touch.slide(tab(1), tab(2), millis = 400)
            val off = tab(2) + Offset(0f, with(density) { -200.dp.toPx() })
            touch.slide(tab(2), off, millis = 700)
            delay(300)
            touch.up()
            delay(1200)

            // Scroll the page: the bar folds, the FAB rides down with it.
            val start = Offset(view.width * 0.5f, view.height * 0.62f)
            touch.down(start)
            touch.slide(start, start + Offset(0f, -view.height * 0.35f), millis = 700)
            touch.up()
            delay(1600)
            // A tap on the folded bar: back to the top of the section, and the bar opens.
            touch.tap(tab(3))
            delay(2200)
            touch.tap(tab(0))
            delay(1800)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Page(list)

        FloatingActionButton(
            onClick = {},
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 108.dp)
                .graphicsLayer { translationY = barState.drop },
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add")
        }

        FloatingNavBar(
            items = listOf(
                FloatingNavItem("Home", rememberVectorPainter(Icons.Default.Home)),
                FloatingNavItem("Search", rememberVectorPainter(Icons.Default.Search)),
                FloatingNavItem("Saved", rememberVectorPainter(Icons.Default.Favorite)),
                FloatingNavItem("You", rememberVectorPainter(Icons.Default.Person)),
            ),
            selectedIndex = section,
            onSelect = {
                section = it
                scope.launch { list.animateScrollToItem(0) }
            },
            folded = folded,
            state = barState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // The finger.
        Canvas(Modifier.fillMaxSize()) {
            if (!finger.down) return@Canvas
            drawCircle(Color.Black.copy(alpha = 0.18f), radius = 30.dp.toPx(), center = finger.at)
            drawCircle(Color.White.copy(alpha = 0.9f), radius = 30.dp.toPx(), center = finger.at, style = Stroke(width = 3.dp.toPx()))
        }
    }
}

@Composable
private fun Page(list: LazyListState) {
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.statusBarsPadding().padding(top = 28.dp, bottom = 8.dp)) {
                Text("Lumen", style = MaterialTheme.typography.displaySmall)
                Text(
                    "FloatingNavBar — a light under the finger, a jelly plate, and a bar that folds as you read.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(24) { index ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier
                        .size(56.dp)
                        .background(Palette[index % Palette.size], MaterialTheme.shapes.large),
                )
                Column(Modifier.fillMaxWidth()) {
                    Text("Item ${index + 1}", style = MaterialTheme.typography.titleMedium)
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth(0.7f).height(10.dp).background(Color(0x14000000), MaterialTheme.shapes.small))
                }
            }
        }
    }
}

private val Palette = listOf(Color(0xFFB9AEF0), Color(0xFFFFC9A8), Color(0xFFA8E0D4), Color(0xFFF3B6C9), Color(0xFFBFD7FF))

/** Real touch events, dispatched into the window, with the finger shown where they land. */
private class Touch(private val view: View, private val finger: Finger) {
    private var downTime = 0L
    private var at = Offset.Zero

    private fun send(action: Int, position: Offset) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, position.x, position.y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        view.rootView.dispatchTouchEvent(event)
        event.recycle()
        at = position
        finger.at = position
    }

    fun down(position: Offset) {
        downTime = SystemClock.uptimeMillis()
        finger.down = true
        send(MotionEvent.ACTION_DOWN, position)
    }

    suspend fun slide(from: Offset, to: Offset, millis: Long) {
        val steps = (millis / 16).toInt().coerceAtLeast(1)
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            // Eased, as a finger is.
            val e = t * t * (3f - 2f * t)
            send(MotionEvent.ACTION_MOVE, from + (to - from) * e)
            delay(16)
        }
    }

    fun up() {
        send(MotionEvent.ACTION_UP, at)
        finger.down = false
    }

    suspend fun tap(position: Offset, holdMillis: Long = 90) {
        down(position)
        delay(holdMillis)
        up()
    }
}
