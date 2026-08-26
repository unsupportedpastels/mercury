import Foundation

/// The privacy-safe lifecycle state rendered by Mercury's run activity.
public enum MercuryRunActivityStatus: String, Codable, Hashable, Sendable {
    case starting
    case thinking
    case responding
    case usingTool
    case waitingForApproval
    case waitingForClarification
    case waitingForSecureInput
    case reconnecting
    case complete
    case failed
    case cancelled
    case statusUnavailable

    public var isFinal: Bool {
        switch self {
        case .complete, .failed, .cancelled, .statusUnavailable:
            return true
        default:
            return false
        }
    }

    public var isWaitingForInput: Bool {
        switch self {
        case .waitingForApproval, .waitingForClarification, .waitingForSecureInput:
            return true
        default:
            return false
        }
    }
}

/// The payload rendered by the widget and sent to ActivityKit.
///
/// Every field is deliberately bounded or generic at the reducer boundary;
/// no prompt, command, argument, path, secret, or provider error belongs here.
public struct MercuryRunActivityContentState: Codable, Hashable, Sendable {
    public let status: MercuryRunActivityStatus
    public let activityLine: String
    public let responseExcerpt: String
    public let updatedAt: Date
    public let isStale: Bool
    public let isFinal: Bool

    public init(
        status: MercuryRunActivityStatus,
        activityLine: String,
        responseExcerpt: String,
        updatedAt: Date,
        isStale: Bool,
        isFinal: Bool
    ) {
        self.status = status
        self.activityLine = activityLine
        self.responseExcerpt = responseExcerpt
        self.updatedAt = updatedAt
        self.isStale = isStale
        self.isFinal = isFinal
    }
}

/// Identity and initial state needed to start one local activity.
public struct RunActivitySeed: Codable, Equatable, Hashable, Sendable {
    public let serverID: UUID
    public let profile: String
    public let durableSessionID: String
    public let sessionTitle: String
    public let startedAt: Date
    public let baselineMessageCount: Int
    public let initialState: MercuryRunActivityContentState

    public init(
        serverID: UUID,
        profile: String,
        durableSessionID: String,
        sessionTitle: String,
        startedAt: Date,
        baselineMessageCount: Int,
        initialState: MercuryRunActivityContentState
    ) {
        self.serverID = serverID
        self.profile = profile
        self.durableSessionID = durableSessionID
        self.sessionTitle = sessionTitle
        self.startedAt = startedAt
        self.baselineMessageCount = baselineMessageCount
        self.initialState = initialState
    }
}

public enum RunActivityDismissal: Equatable, Sendable {
    case afterCompletion
    case afterFailure

    public var interval: TimeInterval {
        switch self {
        case .afterCompletion:
            return 5 * 60
        case .afterFailure:
            return 30
        }
    }
}

public enum RunActivityPolicy {
    public static func dismissal(for status: MercuryRunActivityStatus) -> RunActivityDismissal? {
        switch status {
        case .complete:
            return .afterCompletion
        case .failed, .cancelled, .statusUnavailable:
            return .afterFailure
        default:
            return nil
        }
    }

    public static func displayName(for status: MercuryRunActivityStatus) -> String {
        switch status {
        case .starting:
            return "Starting"
        case .thinking:
            return "Thinking"
        case .responding:
            return "Responding"
        case .usingTool:
            return "Working"
        case .waitingForApproval:
            return "Needs approval"
        case .waitingForClarification:
            return "Needs your input"
        case .waitingForSecureInput:
            return "Needs secure input"
        case .reconnecting:
            return "Reconnecting"
        case .complete:
            return "Finished"
        case .failed:
            return "Failed"
        case .cancelled:
            return "Cancelled"
        case .statusUnavailable:
            return "Status unknown"
        }
    }
}
