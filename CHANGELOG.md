# Changelog

## Unreleased

- `LightTabs`: `LightTab.caption`, a second, quieter line under a word-only tab's label, in the new `captionStyle`.

## 1.0.0

- `LightActions`: a row of actions in the bar's island, lit by the finger, with no selection: lifting on an
  action does it. Disabled actions are dimmed and do nothing. In glass through `material`, as the bar; the
  material's `selection` is empty.
- `LightTabs`: a tap on the selected tab reaches `onSelect` too, as on `FloatingNavBar` and `LightTabIcons`. An app
  that shows something else in the selected tab (a zoom the finger has moved away from, say) can take it back.

## 0.3.0

- `LightTabs(material = …)`: a row of tabs in glass, or any `IslandMaterial`, as `FloatingNavBar` already
  could. The material draws the island and the selected tab's plate, as a light inside it.
- `LightTabs(inScrollingPage = false)`: for a row that sits over something that does not scroll — a
  viewfinder — the press is felt the moment the finger lands instead of waiting to tell it from a scroll.

## 0.2.0

- `LightTabIcons`: a row of tabs as their marks alone, the folded bar's discs, for a pair of views whose
  labelled tabs have scrolled away. It draws no island of its own.
- `GlossCardSurface`: the card's body without the card, for a card that moves and tilts in ways of its
  own.

## 0.1.1

- `BounceHaptics` no longer crashes an app that does not declare `VIBRATE`: the library now declares the
  permission in its own manifest, and falls back to the view's haptic feedback if the permission is
  stripped anyway.

## 0.1.0

- First release: `FloatingNavBar`, `LightTabs`, `GlossCard`, `BounceHaptics`, `GyroTilt`, `IslandMaterial`.
