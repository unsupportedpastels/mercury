import SwiftUI

/// Observed run activity. Interrupt authority is handled by the composer.
struct RunStatusPill: View {
    let text: String

    var body: some View {
        HStack(spacing: 8) {
            ProgressView()
                .controlSize(.small)
                .tint(Color.onAccentContainer)
            Text(text)
                .font(.caption)
                .lineLimit(1)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .foregroundStyle(Color.onAccentContainer)
        .background(Color.accentContainer, in: Capsule())
        .accessibilityLabel("Current status: \(text)")
    }
}
