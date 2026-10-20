---
name: compose-lumen
description: How to use the Lumen library (io.github.angelomarzocchi.lumen) — touch-lit Jetpack Compose components where a light follows the finger instead of a ripple: FloatingNavBar (a floating bottom bar that folds on scroll, with a jelly plate), LightTabs (a lit segmented row of tabs), GlossCard (a card that tilts towards the finger with a gloss), BounceHaptics and IslandMaterial. Load it before adding a bottom navigation bar, a tab row or a tilting card with these, before wiring a FAB to the bar's fold, before putting the bar in glass, and when tabs pick the wrong item, the bar covers content, or labels disappear.
---

# Lumen

Package `io.github.angelomarzocchi.lumen`. Gradle:
`implementation("com.github.angelomarzocchi:compose-lumen:<version>")` (0.1.1 or later) from `https://jitpack.io`.
minSdk 24. Depends on Material 3 (colours and typography defaults).

Shared model: a component takes **every** touch itself — the finger slides from tab to tab carrying a
light, and **lifting picks the tab that is lit**; sliding more than 48dp off the island cancels. There is
no ripple. Haptics follow the springs (`haptics = false` to turn them off).

## FloatingNavBar

```kotlin
val barState = rememberFloatingNavBarState()
FloatingNavBar(
    items = listOf(FloatingNavItem("Home", rememberVectorPainter(Icons.Default.Home), testTag = "nav-home"), …),
    selectedIndex = section,
    onSelect = { index -> if (index == section) scrollToTop() else section = index },
    folded = isScrollingTowardsEnd,          // drive from your scroll state; animates on its own
    state = barState,
    modifier = Modifier.align(Alignment.BottomCenter),
)
```

Rules:
- **3–5 items.** Labels are measured: if the widest cannot fit at 10sp, labels are hidden for good, the
  bar stays folded-size, and each cell gets the label as `contentDescription`. Do not "fix" this by
  shortening the bar's width; shorten the labels or accept icons only.
- **Content passes under the bar.** Put the bar in a `Box` over the page (or `Scaffold(bottomBar = …)`),
  and give the *scrolling content* bottom padding (`contentPadding`) ≥ the bar's height, not its
  container — otherwise the page stops above the island and nothing scrolls under it.
- The bar handles system-bar insets itself; don't add `navigationBarsPadding()` to it.
- **FAB above the bar**: `Modifier.graphicsLayer { translationY = barState.drop }`. Read `drop` only
  in draw/layer blocks.
- `folded`: compute from scroll direction with a small threshold (≈8px) so a resting finger's jitter
  doesn't toggle it.
- `heldAt = index` shows a lit tab without a finger: for previews/screenshots only.
- `barState.tabInWindow(i)` gives the plate's rect in window coordinates (for a section transition that
  grows out of the tab).

## LightTabs

```kotlin
LightTabs(
    tabs = listOf(lightTab("a", "Day", painterA), lightTab("b", "Week", painterB), LightTab("c", "Month")),
    selectedId = selected,            // matches a tab's id
    onSelect = { selected = it },
    fillWidth = true,                 // equal shares of the given width; false = as wide as the tabs
)
```

- Ids are `Any` and compared with `==`: use stable values.
- `LightTab.icon` is a slot `(selected, tint) -> Unit`; draw at 24dp with the given tint.
- `LightTab.description` replaces what TalkBack reads for the tab.
- It never wraps labels; it falls back to labels under icons, then hides them (icons only) — give it the
  width it needs instead of forcing a height.
- It's meant for inside a page: a vertical drag that starts on it scrolls the page.
- `LightTabIcons(tabs, selectedId, onSelect)`: the same tabs as icons only (48dp cells, a disc plate),
  with no island of its own — put it on one coloured `LumenColors.island`. Every tab needs an `icon`.

## GlossCard

```kotlin
GlossCard(
    modifier = Modifier.fillMaxWidth().height(160.dp),
    shape = RoundedCornerShape(24.dp),
    colors = GlossCardDefaults.colors(base = accent),   // or GlossCardDefaults.Violet
    tiltOnTouch = true,
    tiltWithDevice = false,                             // true: gyroscope, off in battery saver
    onClick = { … },                                    // null: tilt only
) { /* content; LocalContentColor = colors.content */ }
```

It casts its own shadows: don't wrap it in `Card`/`Surface` with elevation, and leave room round it
(~24dp below) so the shadow isn't clipped by the parent.

`BoxScope.GlossCardSurface(shape, colors, slabShift, glossCenter, glossAlpha)` is the body alone, for a
card with its own motion and gestures: the caller tilts the box and casts the shadows.

## Colours

`LumenDefaults.colors(island, plate, onPlate, content, lit, light)` defaults to the Material scheme
(`surfaceContainerHigh`, `primaryContainer`, `onPrimaryContainer`, `onSurfaceVariant`, `primary`).
`FingerLight.forSurface(island)` picks `Day` (strong) or `Night` (subtle) light; pass your own
`FingerLight` to tune it.

## IslandMaterial (custom island, e.g. glass)

Implement `IslandMaterial`: `DrawScope.drawIsland(shape, radius, overhang, lights)` draws the whole
island **including the selected plate** (the bar lays no plate over a material), optional
`contentEffect(...)` returns a `RenderEffect` for the marks, and `modifier` is applied where the island
is laid out. `IslandLights` carries `lamp`, `lampOn`, `selection` (the plate's rect this frame),
`accent` and `dark`. Called every frame from draw: no allocations. The compose-glass README has the
complete `GlassIsland` adapter.

## BounceHaptics

`val h = rememberBounceHaptics()`; `h.press()` on finger down; drive `h.bounce(strength, landing)` from
a spring with `ReboundWatch(target) { count, overshoot -> h.bounce(bounceStrength(overshoot, full, floor), count == 1) }`
fed `watch.frame(value, velocity)` inside `animateTo { }`. Respects the system touch-feedback setting.
The library declares `android.permission.VIBRATE` (merged into the app); use version 0.1.1 or later —
0.1.0 crashed with a `SecurityException` on the first touch in apps that did not declare it themselves.

## Testing

Pass `haptics = false` in Robolectric. Tabs and nav cells expose `Role.Tab` and `selected`; nav cells
take `FloatingNavItem.testTag`. For label-fitting tests use `@GraphicsMode(GraphicsMode.Mode.NATIVE)`:
legacy Robolectric graphics does not measure text realistically. A slide is
`performTouchInput { down(center); repeat(10) { moveBy(Offset(step, 0f)) }; up() }`.

## Troubleshooting

| Symptom | Cause |
|---|---|
| Tap does nothing | The finger moved >48dp off the island before lifting, or another gesture consumed it. |
| Page content hidden behind the bar | Bottom padding applied to the container instead of the scrolling content. |
| Labels never show | They don't fit at 10sp in the plate's width (narrow screen, large font scale, long words). |
| FAB doesn't follow the fold | `drop` read in composition, or not read at all; read it in `graphicsLayer`. |
| Card shadow clipped | Parent clips or has no room below the card. |
