import SwiftUI
import MercuryRunActivityKit

struct MercuryRunActivityLockScreenView: View {
    let attributes: MercuryRunActivityAttributes
    let state: MercuryRunActivityContentState

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 5) {
                    Text("Mercury")
                        .font(.caption2.weight(.semibold))
                        .textCase(.uppercase)
                        .foregroundStyle(.secondary)

                    StatusDot(status: state.status, isStale: state.isStale)

                    Text(RunActivityPolicy.displayName(for: state.status))
                        .font(.caption)
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                }

                Text(attributes.sessionTitle)
                    .font(.headline)
                    .foregroundStyle(.primary)
                    .lineLimit(1)

                RunActivityDetailsView(state: state)
            }

            Spacer(minLength: 4)

            RunActivityTimingView(
                startedAt: attributes.startedAt,
                updatedAt: state.updatedAt,
                isStale: state.isStale,
                isFinal: state.isFinal
            )
            .multilineTextAlignment(.trailing)
        }
        .foregroundStyle(.primary)
    }
}

struct RunActivityDetailsView: View {
    let state: MercuryRunActivityContentState

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(state.activityLine)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(1)

            if !state.responseExcerpt.isEmpty {
                Text(state.responseExcerpt)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .privacySensitive()
            }
        }
    }
}

struct RunActivityTimingView: View {
    let startedAt: Date
    let updatedAt: Date
    let isStale: Bool
    let isFinal: Bool

    @ViewBuilder
    var body: some View {
        if isStale && !isFinal {
            VStack(alignment: .trailing, spacing: 1) {
                Text("Reconnecting — last update")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)

                RelativeTimeLabel(date: updatedAt)
            }
        } else if !isFinal {
            Text(startedAt, style: .timer)
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }
}

struct RelativeTimeLabel: View {
    let date: Date

    var body: some View {
        Text(date, style: .relative)
            .font(.caption2)
            .foregroundStyle(.secondary)
            .lineLimit(1)
    }
}

struct StatusDot: View {
    let status: MercuryRunActivityStatus
    let isStale: Bool

    init(status: MercuryRunActivityStatus, isStale: Bool = false) {
        self.status = status
        self.isStale = isStale
    }

    var body: some View {
        Circle()
            .fill(statusColor(for: status, isStale: isStale))
            .frame(width: 8, height: 8)
            .accessibilityElement()
            .accessibilityLabel("Status: \(RunActivityPolicy.displayName(for: status))")
    }
}

struct CompactStatusView: View {
    let status: MercuryRunActivityStatus
    let isStale: Bool

    init(status: MercuryRunActivityStatus, isStale: Bool = false) {
        self.status = status
        self.isStale = isStale
    }

    var body: some View {
        Image(systemName: compactStatusSymbol(for: status))
            .font(.caption.weight(.semibold))
            .foregroundStyle(statusColor(for: status, isStale: isStale))
            .accessibilityElement()
            .accessibilityLabel("Status: \(RunActivityPolicy.displayName(for: status))")
    }
}

func statusColor(for status: MercuryRunActivityStatus, isStale: Bool = false) -> Color {
    if isStale && !status.isFinal {
        return .yellow
    }

    switch status {
    case .complete:
        return .mint
    case .failed:
        return .red
    case .cancelled, .statusUnavailable:
        return .secondary
    case .waitingForApproval, .waitingForClarification, .waitingForSecureInput:
        return .orange
    case .reconnecting:
        return .yellow
    case .starting, .thinking, .responding, .usingTool:
        return .mint
    }
}

func compactStatusSymbol(for status: MercuryRunActivityStatus) -> String {
    switch status {
    case .complete:
        return "checkmark"
    case .failed:
        return "xmark"
    case .cancelled:
        return "minus"
    case .statusUnavailable:
        return "questionmark"
    case .waitingForApproval, .waitingForClarification, .waitingForSecureInput:
        return "hourglass"
    case .reconnecting:
        return "arrow.triangle.2.circlepath"
    case .starting, .thinking, .responding, .usingTool:
        return "ellipsis"
    }
}
