import SwiftUI

struct BackgroundTaskStrip: View {
    let tasks: BackgroundTasks
    var nowOverride: Int64? = nil
    @State private var expanded = false
    @State private var dismissed: Set<String> = []
    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let now = nowOverride ?? Int64(context.date.timeIntervalSince1970 * 1000)
            let visible = tasks.rows.filter { !($0.terminal && dismissed.contains($0.id)) }
            if !visible.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("Background tasks · \(visible.filter { $0.recentlyActive(now: now) }.count) active")
                                .font(.subheadline.weight(.semibold))
                            if let observed = visible.filter({ !$0.terminal && $0.observedAtMillis > 0 })
                                .map(\.observedAtMillis).max() {
                                Text("Task activity last observed \(max(0, now - observed) / 1000)s ago")
                                    .font(.caption).foregroundStyle(.secondary)
                            } else if visible.allSatisfy(\.terminal) {
                                Text("Observed tasks finished").font(.caption).foregroundStyle(.secondary)
                            } else {
                                Text("Activity time unavailable").font(.caption).foregroundStyle(.secondary)
                            }
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
                                        if row.terminal {
                                            Text(row.observedAtMillis > 0
                                                 ? "Outcome observed \(max(0, now - row.observedAtMillis) / 1000)s ago"
                                                 : "Completion time unavailable")
                                                .font(.caption).foregroundStyle(.secondary)
                                        }
                                        if let action = row.action { Text(action).font(.caption).foregroundStyle(.secondary) }
                                    }.frame(maxWidth: .infinity, alignment: .leading)
                                }
                                Text("Only observed child events are shown. Silence does not mean finished or needs input.")
                                    .font(.caption).foregroundStyle(.secondary)
                                if visible.contains(where: \.terminal) {
                                    Button("Dismiss completed") { dismissed.formUnion(visible.filter(\.terminal).map(\.id)) }
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
