package io.github.angelomarzocchi.lumen.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import io.github.angelomarzocchi.lumen.FloatingNavBar
import io.github.angelomarzocchi.lumen.FloatingNavItem
import io.github.angelomarzocchi.lumen.GlossCard
import io.github.angelomarzocchi.lumen.GlossCardDefaults
import io.github.angelomarzocchi.lumen.LightTab
import io.github.angelomarzocchi.lumen.LightTabs
import io.github.angelomarzocchi.lumen.lightTab
import io.github.angelomarzocchi.lumen.rememberFloatingNavBarState
import io.github.angelomarzocchi.lumen.rememberHasTiltSensor
import kotlin.math.abs

/**
 * Every Lumen component on one page.
 *
 * Put a finger on the bar at the bottom and slide it: a light goes with it, lighting each tab it crosses,
 * and lifting picks the one that is lit. Scroll and the bar folds, the FAB riding down with it. Press a
 * card off-centre: it tilts towards the finger and its gloss follows. Turn on "Tilt with the phone".
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                LumenDemo()
            }
        }
    }
}

@Composable
private fun LumenDemo() {
    val list = rememberLazyListState()
    val barState = rememberFloatingNavBarState()
    var section by remember { mutableIntStateOf(0) }
    // The bar folds while the page moves towards its end and opens as it comes back.
    var folded by remember { mutableStateOf(false) }
    LaunchedEffect(list) {
        var last = 0
        snapshotFlow { list.firstVisibleItemIndex * 10_000 + list.firstVisibleItemScrollOffset }.collect { now ->
            if (abs(now - last) > 8) {
                folded = now > last && now > 0
                last = now
            }
        }
    }
    val items = listOf(
        FloatingNavItem("Home", rememberVectorPainter(Icons.Default.Home), testTag = "home"),
        FloatingNavItem("Search", rememberVectorPainter(Icons.Default.Search), testTag = "search"),
        FloatingNavItem("Saved", rememberVectorPainter(Icons.Default.Favorite), testTag = "saved"),
        FloatingNavItem("You", rememberVectorPainter(Icons.Default.Person), testTag = "you"),
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            // The page passes under the bar: the padding is the list's, not its container's.
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Column(Modifier.statusBarsPadding().padding(top = 24.dp)) {
                    Text("Lumen", style = MaterialTheme.typography.displaySmall)
                    Text(
                        "Components that answer the finger with light.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { TabsSection() }
            item { CardsSection() }
            items(12) { index -> Filler(index) }
        }

        FloatingActionButton(
            onClick = {},
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 108.dp)
                // Down with the bar as it folds and as a landing squashes it.
                .graphicsLayer { translationY = barState.drop },
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add")
        }

        FloatingNavBar(
            items = items,
            selectedIndex = section,
            onSelect = { section = it },
            folded = folded,
            state = barState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun TabsSection() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("LightTabs", style = MaterialTheme.typography.titleLarge)
        var view by remember { mutableStateOf<Any>("list") }
        LightTabs(
            tabs = listOf(
                lightTab("list", "List", rememberVectorPainter(Icons.AutoMirrored.Filled.List)),
                lightTab("calendar", "Calendar", rememberVectorPainter(Icons.Default.DateRange)),
                lightTab("starred", "Starred", rememberVectorPainter(Icons.Default.Star)),
            ),
            selectedId = view,
            onSelect = { view = it },
            fillWidth = true,
        )
        var format by remember { mutableStateOf<Any>("story") }
        LightTabs(
            tabs = listOf(LightTab("story", "Story"), LightTab("post", "Post")),
            selectedId = format,
            onSelect = { format = it },
        )
    }
}

@Composable
private fun CardsSection() {
    var withPhone by remember { mutableStateOf(false) }
    val hasSensor = rememberHasTiltSensor()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("GlossCard", style = MaterialTheme.typography.titleLarge)
        if (hasSensor) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tilt with the phone", Modifier.weight(1f))
                Switch(checked = withPhone, onCheckedChange = { withPhone = it })
            }
        }
        GlossCard(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            colors = GlossCardDefaults.Violet,
            tiltWithDevice = withPhone,
            onClick = {},
        ) {
            CardText("Run 5 km", "STEP 2 OF 5 · TODAY")
        }
        GlossCard(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            colors = GlossCardDefaults.colors(base = Color(0xFF1E88E5)),
            tiltWithDevice = withPhone,
        ) {
            CardText("Read 20 pages", "HABIT · 12 DAYS")
        }
    }
}

@Composable
private fun CardText(title: String, eyebrow: String) {
    Column(Modifier.padding(20.dp)) {
        Text(eyebrow, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
    }
}

/** Something to scroll past, so the bar has a reason to fold. */
@Composable
private fun Filler(index: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium),
        )
        Column {
            Text("Row ${index + 1}", style = MaterialTheme.typography.titleMedium)
            Text("Scroll down: the bar folds. Scroll up: it opens.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
