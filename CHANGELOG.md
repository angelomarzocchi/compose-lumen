# Changelog

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
