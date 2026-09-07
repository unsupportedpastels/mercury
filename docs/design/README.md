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
