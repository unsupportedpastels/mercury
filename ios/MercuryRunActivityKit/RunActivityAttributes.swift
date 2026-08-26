import Foundation

#if canImport(ActivityKit)
import ActivityKit

/// Shared ActivityKit attributes for Mercury's local run activity.
///
/// The pure content-state model lives outside this conditional so the policy
/// and reducer can compile in Foundation-only targets and tests.
public struct MercuryRunActivityAttributes: ActivityAttributes {
    public typealias ContentState = MercuryRunActivityContentState

    public let serverID: UUID
    public let profile: String
    public let durableSessionID: String
    public let sessionTitle: String
    public let startedAt: Date
    public let baselineMessageCount: Int

    public init(
        serverID: UUID,
        profile: String,
        durableSessionID: String,
        sessionTitle: String,
        startedAt: Date,
        baselineMessageCount: Int
    ) {
        self.serverID = serverID
        self.profile = profile
        self.durableSessionID = durableSessionID
        self.sessionTitle = sessionTitle
        self.startedAt = startedAt
        self.baselineMessageCount = baselineMessageCount
    }
}
#endif
