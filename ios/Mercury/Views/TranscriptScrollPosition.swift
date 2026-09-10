import SwiftUI

/// Geometry policy for native transcript follow mode.
///
/// The tail marker must be inside the viewport (or within a small settled-
/// scroll tolerance). Merely having the final row instantiated is not enough:
/// a tall streaming row can remain visible while its actual end is offscreen.
enum TranscriptScrollPosition {
    static let endTolerance: CGFloat = 2

    static func isAtBottom(
        tailMaxY: CGFloat?,
        viewportHeight: CGFloat,
        tolerance: CGFloat = endTolerance
    ) -> Bool {
        guard let tailMaxY, viewportHeight > 0 else { return false }
        return tailMaxY >= -tolerance && tailMaxY <= viewportHeight + tolerance
    }
}

struct TranscriptTailPreferenceKey: PreferenceKey {
    static var defaultValue: CGFloat?

    static func reduce(value: inout CGFloat?, nextValue: () -> CGFloat?) {
        value = nextValue() ?? value
    }
}
