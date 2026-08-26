import Foundation
import BackgroundTasks

enum BackgroundNotificationTask {
    static let identifier = "com.unsupportedpastels.mercury.reconcile"
}

struct BackgroundReconciliationScheduler {
    static func submitRefreshRequest(earliest seconds: TimeInterval = 15 * 60) {
        let request = BGAppRefreshTaskRequest(identifier: BackgroundNotificationTask.identifier)
        request.earliestBeginDate = earliestBeginDate(now: Date(), seconds: seconds)

        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // Background refresh is best-effort. The next lifecycle opportunity
            // can submit another request.
        }
    }

    static func register(
        _ perform: @escaping @Sendable (BGAppRefreshTask) -> Void
    ) {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: BackgroundNotificationTask.identifier,
            using: nil
        ) { task in
            guard let refreshTask = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            perform(refreshTask)
        }
    }

    static func earliestBeginDate(now: Date, seconds: TimeInterval) -> Date {
        now.addingTimeInterval(seconds)
    }
}
