import SwiftUI

/// Android SessionDetail parity: the context ring in the session toolbar and
/// the run status pill. These explain observed activity only; whether the
/// user may interrupt is decided by controller authority, never by these.
struct ContextRingButton: View {
    let percent: Double?
    let artifactCount: Int
    let action: () -> Void

    private var fraction: Double? { percent.map { min(max($0 / 100, 0), 1) } }

    private var ringColor: Color {
        guard let fraction else { return Color.secondaryContent }
        if fraction >= 0.9 { return Color.statusAlert }
        if fraction >= 0.75 { return Color.statusActive }
        return Color.accentPrimary
    }

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle()
                    .stroke(Color.surfaceHighest, lineWidth: 3)
                Circle()
                    .trim(from: 0, to: fraction ?? 0)
                    .stroke(ringColor, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                Text(percent.map { String(Int(min(max($0, 0), 99))) } ?? "–")
                    .font(.system(size: 9, weight: .medium))
                    .foregroundStyle(Color.primary)
            }
            .frame(width: 30, height: 30)
        }
        .accessibilityLabel("Open session details")
        .accessibilityValue(
            (percent.map { "Context \(Int($0)) percent used" } ?? "Context usage unknown")
                + ", " + (artifactCount == 1 ? "1 artifact" : "\(artifactCount) artifacts")
        )
    }
}

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
