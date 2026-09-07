# Hybrid C — Nous × Hermes visual contract

![Hybrid C — Nous × Hermes](hybrid-c-nous-hermes.png)

This image is the locked visual north star for both native clients: Jetpack Compose / Material 3 on Android and SwiftUI on iOS. The two apps share destinations, hierarchy, content ordering, and the palette below (Android `theme/Theme.kt` is the source of truth; `ios/Mercury/Theme.swift` mirrors it as light/dark token pairs) and keep their chrome native.

## Information architecture

- Project → durable session → active run remain distinct concepts.
- Compact windows show one destination at a time: Home → Project → Session workspace.
- Suitable expanded windows show project/session navigation beside the session workspace.
- Supporting Plan/Changes/Terminal surfaces appear only when authoritative Hermes data and available width justify them; never reserve an empty fictional pane.

## Semantic color roles

- Teal: navigation, selection, links, filters, and timeline structure.
- Gold: New task, Send, Stop, and the current active operation.
- Green: completed/successful operations only.
- Graphite/off-white: ordinary surfaces and content.
- Material error roles: errors and destructive failures.

## Guardrails

- No purple, gradients, glow, glassmorphism, chat bubbles, oversized cards, or decorative AI styling.
- Do not invent percentages, future-step totals, context/token telemetry, plans, diffs, durations, or terminal state.
- Reasoning is shown only when Hermes emits it.
- Interruption is labeled **Stop**, never Pause.
- Preserve Material 3 accessibility, touch targets, adaptive layout, edge-to-edge behavior, and large-text support on Android; preserve Dynamic Type, safe areas, and system appearance on iOS. Do not make one platform imitate the other's controls.

## App icon

`mercury-icon.svg` is the source of record for the app icon on both platforms: the winged Mercury helmet glyph on a `#18191e` tile (`mercury-helmet-mockup.jpg` is the reference it was traced from). Every raster is rendered from it, never hand-edited:

- Android: `app/src/main/res/drawable-nodpi/mercury_launcher_art.png` (the glyph alone on transparency, inset inside the adaptive-icon safe zone by `drawable/ic_launcher_foreground.xml`), the `#18191e` background layer, the Android 13+ monochrome silhouette, and the legacy `mipmap-*` rasters.
- iOS: `ios/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon1024.png`, the single 1024 universal icon Xcode scales.
- Play Store: `playstore/ic_launcher-playstore-512.png`, the same render downscaled (see `playstore/README.md` for the regeneration recipe).
