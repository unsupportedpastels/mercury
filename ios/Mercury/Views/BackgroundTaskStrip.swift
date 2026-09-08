import SwiftUI

struct BackgroundTaskStrip: View {
    let tasks: BackgroundTasks
    var nowOverride: Int64? = nil
    @State private var expanded = false
    @State private var dismissed: Set<String> = []

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let now = nowOverride ?? Int64(context.date.timeIntervalSince1970 * 1000)
            // A dismissal hides only the exact observed evidence while it is
            // still unavailable. A later event, runtime rebind, or fresh row
            // has a different evidence key and is shown again.
            let visible = tasks.rows.filter {
                !($0.isDismissible(now: now) && dismissed.contains($0.dismissalKey))
            }
            if !visible.isEmpty {
                let presentation = tasks.presentation(rows: visible, now: now)
                let secondaryLabel = tasks.secondaryLabel(rows: visible, now: now)
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(presentation.headline)
                                .font(.subheadline.weight(.semibold))
                                .lineLimit(2)
                            Text(secondaryLabel)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(2)
                        }
                        Spacer(minLength: 4)
                        Button(expanded ? "Hide details" : "Details") { expanded.toggle() }
                            .font(.subheadline).frame(minHeight: 44)
                    }
                    if expanded {
                        ScrollView {
                            VStack(alignment: .leading, spacing: 10) {
                                ForEach(visible) { row in
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(row.goal).font(.subheadline.weight(.semibold))
                                        Text(row.label(now: now)).font(.caption)
                                        Text(row.timeLabel(now: now))
                                            .font(.caption).foregroundStyle(.secondary)
                                        if let action = row.action {
                                            Text(action).font(.caption).foregroundStyle(.secondary)
                                        }
                                    }.frame(maxWidth: .infinity, alignment: .leading)
                                }
                                Text("Only observed child events are shown. Silence does not mean finished or needs input.")
                                    .font(.caption).foregroundStyle(.secondary)
                                let unavailable = visible.filter {
                                    !$0.terminal && $0.isDismissible(now: now)
                                }
                                if !unavailable.isEmpty {
                                    Button("Dismiss unavailable") {
                                        dismissed.formUnion(unavailable.map(\.dismissalKey))
                                    }
                                    .frame(minHeight: 44)
                                }
                                let completed = visible.filter(\.terminal)
                                if !completed.isEmpty {
                                    Button("Dismiss completed") {
                                        dismissed.formUnion(completed.map(\.dismissalKey))
                                    }
                                    .frame(minHeight: 44)
                                }
                            }
                        }.frame(maxHeight: 176)
                    }
                }
                .padding(.horizontal, 12).padding(.vertical, 5)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
                .accessibilityIdentifier("background-task-strip")
            }
        }
    }
}
