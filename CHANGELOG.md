# Changelog

## 0.1.1

- `BounceHaptics` no longer crashes an app that does not declare `VIBRATE`: the library now declares the
  permission in its own manifest, and falls back to the view's haptic feedback if the permission is
  stripped anyway.

## 0.1.0

- First release: `FloatingNavBar`, `LightTabs`, `GlossCard`, `BounceHaptics`, `GyroTilt`, `IslandMaterial`.
