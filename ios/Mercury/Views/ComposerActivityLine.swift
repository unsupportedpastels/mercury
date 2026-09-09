import SwiftUI
import MercuryCore

/// One composer-owned activity surface. Native animation/accessibility around
/// the same priority, anti-strobe, and elapsed-format policies as Android.
struct ComposerActivityLine: View {
    let candidate: MercuryCore.ActivityLineState
    let startedAtMillis: Int64?
    let onOpenDetails: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @State private var held = MercuryCore.ActivityLineHoldState(shown: nil, candidate: nil, candidateSinceMillis: 0)

    private var shown: MercuryCore.ActivityLineState {
        // Priority changes are immediate, not delayed by a task's scheduling.
        if candidate.kind != held.shown?.kind || candidate.kind != .working { return candidate }
        return held.shown ?? candidate
    }

    var body: some View {
        Group {
            if candidate.kind != .hidden {
                TimelineView(.animation(minimumInterval: 1.0 / 30, paused: reduceMotion || scenePhase != .active || !shown.animated)) { animation in
                    TimelineView(.periodic(from: .now, by: 1)) { clock in
                        let time = animation.date.timeIntervalSinceReferenceDate
                        let moving = shown.animated && !reduceMotion && scenePhase == .active
                        let elapsed = elapsedLabel(at: clock.date)
                        Button(action: onOpenDetails) {
                            HStack(spacing: 7) {
                                RoundedRectangle(cornerRadius: 2)
                                    .fill(markerColor)
                                    .frame(width: 8, height: 8)
                                    .opacity(moving ? 0.35 + 0.65 * (0.5 + 0.5 * cos(time * .pi / 0.9)) : 1)
                                Text(shown.label)
                                    .font(.caption)
                                    .foregroundStyle(Color.secondaryContent)
                                    .lineLimit(1)
                                    .truncationMode(.tail)
                                    .overlay {
                                        if moving {
                                            GeometryReader { geometry in
                                                LinearGradient(colors: [.clear, Color.accentPrimary.opacity(0.65), .clear], startPoint: .leading, endPoint: .trailing)
                                                    .frame(width: geometry.size.width)
                                                    .offset(x: geometry.size.width * (time.truncatingRemainder(dividingBy: 1.6) / 0.8 - 1))
                                            }
                                            .mask(Text(shown.label).font(.caption).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading))
                                        }
                                    }
                                    .layoutPriority(1)
                                Image(systemName: "chevron.right")
                                    .font(.system(size: 9, weight: .medium))
                                    .foregroundStyle(Color.secondary)
                                Spacer(minLength: 8)
                                if let elapsed {
                                    Text(elapsed)
                                        .font(.caption.monospaced())
                                        .foregroundStyle(Color.secondary)
                                        .fixedSize()
                                        .frame(minWidth: 58, alignment: .trailing)
                                        .accessibilityIdentifier("Activity elapsed time")
                                }
                            }
                            .frame(maxWidth: .infinity, minHeight: 32, alignment: .leading)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 6)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Activity: \(shown.label)")
                        .accessibilityValue([shown.kind.name, elapsed].compactMap { $0 }.joined(separator: ", "))
                        .accessibilityHint("Opens session activity details")
                        .accessibilityIdentifier("Composer activity line")
                    }
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.15), value: candidate.kind)
        .task(id: candidate) {
            held = MercuryCore.ActivityLineHold.shared.step(previous: held, candidate: candidate, nowMillis: nowMillis)
            guard held.candidate != nil else { return }
            do { try await Task.sleep(for: .milliseconds(MercuryCore.ActivityLineHold.shared.QUIET_MILLIS)) }
            catch { return }
            held = MercuryCore.ActivityLineHold.shared.step(previous: held, candidate: candidate, nowMillis: nowMillis)
        }
    }

    private var nowMillis: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
    private var markerColor: Color {
        if shown.kind == .needsyou || shown.kind == .connectionlost { return .statusAlert }
        return shown.animated ? .statusActive : .secondaryContent
    }
    private func elapsedLabel(at date: Date) -> String? {
        let now = Int64(date.timeIntervalSince1970 * 1000)
        guard shown.showTimer, let startedAtMillis, startedAtMillis >= 0, startedAtMillis <= now else { return nil }
        return MercuryCore.ActivityLinePolicy.shared.formatElapsed(seconds: (now - startedAtMillis) / 1000)
    }
}
