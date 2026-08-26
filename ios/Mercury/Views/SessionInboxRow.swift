import SwiftUI

struct SessionInboxRow: View {
    let session: SessionRow
    let ownerLabel: String
    let workspacePath: String?
    let indicator: SessionInboxIndicator

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            SessionStatusIndicator(indicator: indicator, title: displayTitle)
                .padding(.top, 6)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 8) {
                    Text(ownerLabel)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(Color.secondary)
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    if let lastActive = session.lastActive {
                        Text(lastActive, style: .relative)
                            .font(.caption)
                            .foregroundStyle(Color.secondary)
                            .lineLimit(1)
                            .accessibilityLabel("Last active time")
                    }
                }

                Text(displayTitle)
                    .font(.headline)
                    .foregroundStyle(Color.primary)
                    .lineLimit(1)

                if !session.preview.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Text(session.preview.trimmingCharacters(in: .whitespacesAndNewlines))
                        .font(.subheadline)
                        .foregroundStyle(Color.secondary)
                        .lineLimit(1)
                }

                Text(SessionInboxPolicy.metadata(model: session.model, messageCount: session.messageCount))
                    .font(.caption.weight(.medium))
                    .foregroundStyle(Color.secondary)
                    .lineLimit(1)
            }
        }
        .padding(.vertical, 5)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityDescription)
    }

    private var displayTitle: String {
        session.title.isEmpty ? "Untitled session" : session.title
    }

    private var accessibilityDescription: String {
        var parts = ["Session \(displayTitle)", ownerLabel]
        switch indicator {
        case .idle: break
        case .running: parts.append("running")
        case .completedUnread: parts.append("completed unread")
        }
        parts.append(SessionInboxPolicy.metadata(model: session.model, messageCount: session.messageCount))
        if session.model == nil, let workspacePath, !workspacePath.isEmpty { parts.append(workspacePath) }
        return parts.joined(separator: ", ")
    }
}

private struct SessionStatusIndicator: View {
    let indicator: SessionInboxIndicator
    let title: String
    @State private var pulse = false

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: size, height: size)
            .opacity(indicator == .running ? (pulse ? 0.35 : 1) : 1)
            .animation(
                indicator == .running
                    ? .easeInOut(duration: 0.9).repeatForever(autoreverses: true)
                    : .default,
                value: pulse
            )
            .onAppear { pulse = indicator == .running }
            .onChange(of: indicator) { pulse = indicator == .running }
            .accessibilityLabel(statusLabel)
    }

    private var color: Color {
        switch indicator {
        case .idle: Color.secondary.opacity(0.35)
        case .running: Color.statusHealthy
        case .completedUnread: Color.accentPrimary
        }
    }

    private var size: CGFloat {
        indicator == .idle ? 8 : 10
    }

    private var statusLabel: String {
        switch indicator {
        case .idle: "\(title) is idle"
        case .running: "\(title) is running"
        case .completedUnread: "\(title) completed; unread"
        }
    }
}
