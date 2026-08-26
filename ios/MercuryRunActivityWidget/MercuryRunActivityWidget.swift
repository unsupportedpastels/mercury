#if canImport(ActivityKit)
import ActivityKit
import WidgetKit
import SwiftUI
import MercuryRunActivityKit
import Foundation

@main
struct MercuryRunActivityWidgetBundle: WidgetBundle {
    var body: some Widget {
        MercuryRunActivityLiveActivity()
    }
}

struct MercuryRunActivityLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: MercuryRunActivityAttributes.self) { context in
            MercuryRunActivityLockScreenView(
                attributes: context.attributes,
                state: context.state
            )
            .widgetURL(mercurySessionDeepLink(for: context.attributes))
            .activityBackgroundTint(Color.black.opacity(0.85))
            .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            let attributes = context.attributes
            let state = context.state

            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    HStack(spacing: 5) {
                        StatusDot(status: state.status, isStale: state.isStale)
                        Text(RunActivityPolicy.displayName(for: state.status))
                            .font(.caption)
                            .lineLimit(1)
                    }
                }

                DynamicIslandExpandedRegion(.trailing) {
                    RunActivityTimingView(
                        startedAt: attributes.startedAt,
                        updatedAt: state.updatedAt,
                        isStale: state.isStale,
                        isFinal: state.isFinal
                    )
                    .multilineTextAlignment(.trailing)
                }

                DynamicIslandExpandedRegion(.center) {
                    Text(attributes.sessionTitle)
                        .font(.headline)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity)
                }

                DynamicIslandExpandedRegion(.bottom) {
                    RunActivityDetailsView(state: state)
                }
            } compactLeading: {
                StatusDot(status: state.status, isStale: state.isStale)
            } compactTrailing: {
                CompactStatusView(status: state.status, isStale: state.isStale)
            } minimal: {
                StatusDot(status: state.status, isStale: state.isStale)
            }
            .widgetURL(mercurySessionDeepLink(for: attributes))
        }
    }
}

private func mercurySessionDeepLink(for attributes: MercuryRunActivityAttributes) -> URL? {
    var components = URLComponents()
    components.scheme = "mercury"
    components.host = "session"
    components.queryItems = [
        URLQueryItem(name: "id", value: attributes.durableSessionID),
        URLQueryItem(name: "server", value: attributes.serverID.uuidString),
        URLQueryItem(name: "profile", value: attributes.profile)
    ]
    return components.url
}
#endif
