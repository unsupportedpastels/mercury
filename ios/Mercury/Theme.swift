import SwiftUI

// MARK: - AMOLED-first dark theme
//
// Every color in Mercury is defined here exactly once. No hardcoded hex
// anywhere else in the app — views reach for these statics (or the system
// .primary/.secondary text colors) only.

extension Color {
    /// Pure #000000. The app background on AMOLED hardware: true pixels-off black.
    static let amoledBlack = Color(red: 0, green: 0, blue: 0)

    /// Raised surface steps above AMOLED black, subtle and monotonic.
    static let surfaceLow = Color(red: 0x0A / 255.0, green: 0x0A / 255.0, blue: 0x0A / 255.0)   // #0A0A0A
    static let surfaceMid = Color(red: 0x14 / 255.0, green: 0x14 / 255.0, blue: 0x14 / 255.0)   // #141414
    static let surfaceHigh = Color(red: 0x1E / 255.0, green: 0x1E / 255.0, blue: 0x1E / 255.0)  // #1E1E1E

    /// Accent: system indigo keeps the native iOS feel; mint is reserved for
    /// "connected/healthy" states so the accent never fights status semantics.
    static let accentPrimary = Color.indigo
    static let statusHealthy = Color.mint
    static let statusAlert = Color.red

    // Android composer parity. These are deliberately scoped semantic roles
    // rather than replacements for Mercury's native iOS accent: the chat bar
    // should match the authoritative Android client without recoloring the
    // rest of the app.
    static let composerPrimary = Color(
        red: 0x80 / 255.0,
        green: 0xD5 / 255.0,
        blue: 0xCF / 255.0
    ) // Android dark primary #80D5CF
    static let composerOnPrimary = Color(
        red: 0x00 / 255.0,
        green: 0x37 / 255.0,
        blue: 0x35 / 255.0
    ) // Android dark onPrimary #003735
    static let composerSurface = Color(
        red: 0x1C / 255.0,
        green: 0x1C / 255.0,
        blue: 0x1C / 255.0
    ) // Android dark surfaceContainer #1C1C1C
    static let composerSecondaryContent = Color(
        red: 0xC5 / 255.0,
        green: 0xC5 / 255.0,
        blue: 0xC5 / 255.0
    ) // Android dark onSurfaceVariant #C5C5C5
    static let composerActive = Color(
        red: 0xF2 / 255.0,
        green: 0xC6 / 255.0,
        blue: 0x4D / 255.0
    ) // Android semantic active #F2C64D
    static let composerOnActive = Color(
        red: 0x24 / 255.0,
        green: 0x1A / 255.0,
        blue: 0x00 / 255.0
    ) // Android semantic onActive #241A00

    /// Hairlines/borders between raised surfaces.
    static let separatorSubtle = Color.primary.opacity(0.12)
}

extension ShapeStyle where Self == Color {
    /// `some ShapeStyle` ergonomics: `.background(.amoledBlack)` etc.
    static var amoledBlack: Color { .amoledBlack }
    static var surfaceLow: Color { .surfaceLow }
    static var surfaceMid: Color { .surfaceMid }
    static var surfaceHigh: Color { .surfaceHigh }
}

extension View {
    /// Applies the AMOLED background edge-to-edge beneath safe-area content.
    func amoledScreen() -> some View {
        background(Color.amoledBlack.ignoresSafeArea(edges: .all))
    }
}

/// Android-parity new-session floating action button: the 48dp rounded-square
/// `Surface` from `SessionListScreen`'s `floatingActionButton` slot (amber
/// `semanticColors.active` #F2C64D, dark `onActive` content, shapes.small),
/// anchored bottom-trailing. The SAME control appears on Home and on the
/// project sessions screen so session creation looks identical everywhere.
struct NewTaskFloatingButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "plus")
                .font(.system(size: 22, weight: .medium))
                .foregroundStyle(Color.composerOnActive)
                .frame(width: 48, height: 48)
                .background(Color.composerActive, in: RoundedRectangle(cornerRadius: 8))
        }
        .accessibilityLabel("New session")
        .padding(.trailing, 28)
        .padding(.bottom, 16)
    }
}
