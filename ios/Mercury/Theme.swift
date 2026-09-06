import SwiftUI
import UIKit

// MARK: - Mercury design tokens
//
// Every color in Mercury is defined here exactly once, as a light/dark pair
// derived from the Android Material 3 scheme in
// app/src/main/java/.../theme/Theme.kt (the source of truth; see the local
// design-tokens spec). No hardcoded hex anywhere else in the app — views
// reach for these roles (or the system .primary/.secondary text colors) only.
// Dark is AMOLED-first: a pure #000000 canvas with neutral gray tiers.

private extension Color {
    /// One adaptive role from its light and dark hex values.
    static func token(light: UInt32, dark: UInt32) -> Color {
        Color(UIColor { traits in
            traits.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light)
        })
    }
}

private extension UIColor {
    convenience init(hex: UInt32) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255.0,
            green: CGFloat((hex >> 8) & 0xFF) / 255.0,
            blue: CGFloat(hex & 0xFF) / 255.0,
            alpha: 1
        )
    }
}

extension Color {
    // MARK: Canvas and surfaces (Material background / surfaceContainer*)

    /// App background: Material `background`. Pure black on AMOLED hardware.
    static let canvas = Color.token(light: 0xFAFCFB, dark: 0x000000)
    /// Material `surfaceContainerLowest`.
    static let surfaceLowest = Color.token(light: 0xFFFFFF, dark: 0x0A0A0A)
    /// Material `surfaceContainerLow`: the first raised step above the canvas.
    static let surfaceLow = Color.token(light: 0xF4F7F5, dark: 0x141414)
    /// Material `surfaceContainer`: cards, the composer, sheets.
    static let surfaceMid = Color.token(light: 0xECF2EF, dark: 0x1C1C1C)
    /// Material `surfaceContainerHigh`.
    static let surfaceHigh = Color.token(light: 0xE6ECE9, dark: 0x232323)
    /// Material `surfaceContainerHighest`.
    static let surfaceHighest = Color.token(light: 0xE0E5E2, dark: 0x2B2B2B)
    /// Material `onSurfaceVariant`: secondary content on surfaces.
    static let secondaryContent = Color.token(light: 0x3F4947, dark: 0xC5C5C5)
    /// Material `outline`.
    static let outline = Color.token(light: 0x6F7977, dark: 0x8A8A8A)
    /// Material `outlineVariant`: hairlines and borders between surfaces.
    static let separatorSubtle = Color.token(light: 0xBEC9C6, dark: 0x3A3A3A)

    // MARK: Accent (Hermes teal, Material primary)

    static let accentPrimary = Color.token(light: 0x1B6969, dark: 0x9BD0CF)
    static let onAccentPrimary = Color.token(light: 0xE0FFFE, dark: 0x0C4848)
    static let accentContainer = Color.token(light: 0xA8EFEE, dark: 0x255A5A)
    static let onAccentContainer = Color.token(light: 0x005C5C, dark: 0xB7EDEC)

    // MARK: Status (Android semantic roles)

    /// A running turn, the new-session control: Android `active`.
    static let statusActive = Color.token(light: 0xC68A16, dark: 0xF2C64D)
    static let onStatusActive = Color.token(light: 0x241A00, dark: 0x241A00)
    /// Connected/healthy and completed runs share Android `completed`.
    static let statusHealthy = Color.token(light: 0x2D6A43, dark: 0x8ED6A5)
    static let onStatusHealthy = Color.token(light: 0xFFFFFF, dark: 0x0C3A1E)
    /// Material `error`.
    static let statusAlert = Color.token(light: 0xBA1A1A, dark: 0xFFB4AB)
    static let onStatusAlert = Color.token(light: 0xFFFFFF, dark: 0x690005)
    static let alertContainer = Color.token(light: 0xFFDAD6, dark: 0x93000A)
    static let onAlertContainer = Color.token(light: 0x410002, dark: 0xFFDAD6)
}

extension ShapeStyle where Self == Color {
    /// `some ShapeStyle` ergonomics: `.background(.canvas)` etc.
    static var canvas: Color { .canvas }
    static var surfaceLowest: Color { .surfaceLowest }
    static var surfaceLow: Color { .surfaceLow }
    static var surfaceMid: Color { .surfaceMid }
    static var surfaceHigh: Color { .surfaceHigh }
    static var surfaceHighest: Color { .surfaceHighest }
}

extension View {
    /// Applies the canvas background edge-to-edge beneath safe-area content.
    func amoledScreen() -> some View {
        background(Color.canvas.ignoresSafeArea(edges: .all))
    }
}

/// Android-parity new-session floating action button: the 48dp rounded-square
/// `Surface` from `SessionListScreen`'s `floatingActionButton` slot (amber
/// `semanticColors.active`, dark `onActive` content, shapes.small),
/// anchored bottom-trailing. The SAME control appears on Home and on the
/// project sessions screen so session creation looks identical everywhere.
struct NewTaskFloatingButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "plus")
                .font(.system(size: 22, weight: .medium))
                .foregroundStyle(Color.onStatusActive)
                .frame(width: 48, height: 48)
                .background(Color.statusActive, in: RoundedRectangle(cornerRadius: 8))
        }
        .accessibilityLabel("New session")
        .padding(.trailing, 28)
        .padding(.bottom, 16)
    }
}
