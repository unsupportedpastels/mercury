import SwiftUI

/// Standalone M9 cron surface. Integration owns loading, errors, RPC lifetime,
/// and refreshing; this view exposes callbacks and makes no AppModel/navigation
/// assumptions.
struct CronPanel: View {
    let jobs: [CronJob]
    var isLoading = false
    var loadError: String? = nil
    var actionState = CronActionState()
    let onRefresh: () -> Void
    let onSetEnabled: (CronJob, Bool) -> Void
    let onRunNow: (CronJob) -> Void

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                header

                if let loadError {
                    Label(loadError, systemImage: "exclamationmark.triangle.fill")
                        .font(.footnote)
                        .foregroundStyle(.red)
                }

                if isLoading && jobs.isEmpty {
                    HStack { Spacer(); ProgressView("Loading cron jobs"); Spacer() }
                        .padding(.vertical, 24)
                } else if jobs.isEmpty {
                    ContentUnavailableView("No cron jobs", systemImage: "calendar.badge.clock")
                } else {
                    ForEach(jobs) { job in jobCard(job) }
                }
            }
            .padding()
            .frame(maxWidth: 720)
        }
        .navigationTitle("Cron Jobs")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var header: some View {
        HStack {
            Text("Scheduled work").font(.title2.bold())
            Spacer()
            Button(action: onRefresh) {
                Label("Refresh", systemImage: "arrow.clockwise")
            }
            .disabled(isLoading)
        }
    }

    @ViewBuilder
    private func jobCard(_ job: CronJob) -> some View {
        let pending = actionState.pendingAction(for: job.id)
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(job.name).font(.headline).lineLimit(2)
                Spacer()
                Text(job.displayStatus)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(job.requiresAttention ? Color.red : Color.secondary)
            }
            Text(job.schedule).font(.subheadline.monospaced()).foregroundStyle(.secondary)
            detail("Next run", job.nextRunAt)
            detail("Last run", job.lastRunAt)
            detail("Last outcome", job.lastStatus)
            detail("Delivery", job.lastDeliveryError)

            HStack(spacing: 10) {
                Button(job.enabled == false ? "Enable" : "Disable") {
                    onSetEnabled(job, job.enabled == false)
                }
                .buttonStyle(.bordered)
                .disabled(pending != nil)

                Button("Run now") { onRunNow(job) }
                    .buttonStyle(.borderedProminent)
                    .disabled(pending != nil)
                    .accessibilityLabel("Run \(job.name) now")

                if pending != nil { ProgressView().controlSize(.small) }
            }

            if pending == .runNow {
                Text("Requesting a run…")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let message = actionState.message(for: job.id) {
                Text(message).font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(14)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder
    private func detail(_ label: String, _ value: String?) -> some View {
        if let value, !value.isEmpty {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(label + ":").font(.caption.weight(.medium)).foregroundStyle(.secondary)
                Text(value).font(.caption).lineLimit(2)
            }
        }
    }
}
