import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

struct SettingsView: View {
    @Environment(AppModel.self) private var appModel
    @Environment(\.dismiss) private var dismiss
    @State private var relay: RelayAppModel?
    @State private var confirmRemoveRelay = false
    @State private var showRelayPairing = false

    var body: some View {
        NavigationStack {
            List {
                if let message = appModel.localSettingsError {
                    Section {
                        Label(message, systemImage: "exclamationmark.triangle.fill")
                            .font(.footnote)
                            .foregroundStyle(Color.statusAlert)
                    }
                }

                Section {
                    NavigationLink {
                        ServerListView(
                            catalog: appModel.serverCatalog,
                            relayTargets: appModel.relayTargets,
                            activeIdentity: appModel.activeStartupIdentity,
                            onSelect: { entry in Task { await appModel.switchServer(entry) } },
                            onSelectRelay: { target in Task { await appModel.connectRelay(target) } },
                            onAdd: { origin, label in
                                Task { await appModel.addServer(origin: origin, label: label) }
                            },
                            onPairRelay: { showRelayPairing = true },
                            onEditLabel: { entry, label in
                                Task { await appModel.renameServer(entry, label: label) }
                            },
                            onEditRelayLabel: { target, label in
                                Task { await appModel.renameRelay(target, label: label) }
                            },
                            onRemove: { entry in
                                Task { await appModel.removeServer(entry) }
                            },
                            onRemoveRelay: { target in
                                Task { await appModel.removeRelay(target) }
                            }
                        )
                    } label: {
                        settingsRow("Servers", subtitle: "Add, switch, or remove Hermes servers", icon: "server.rack")
                    }

                    NavigationLink {
                        CronSettingsView()
                    } label: {
                        settingsRow("Scheduled jobs", subtitle: "Cron jobs running on this server", icon: "calendar.badge.clock")
                    }
                }

                Section {
                    NavigationLink {
                        OfflineSettingsView()
                    } label: {
                        settingsRow("Offline & privacy", subtitle: "Encrypted conversation cache", icon: "lock.doc")
                    }

                    NavigationLink {
                        NotificationSettingsView()
                    } label: {
                        settingsRow("Notifications", subtitle: "Turn-complete and attention alerts", icon: "bell.badge")
                    }

                    NavigationLink {
                        VoiceSettingsView()
                    } label: {
                        settingsRow("Voice", subtitle: "Dictation and read aloud", icon: "waveform")
                    }
                }

                if let target = appModel.activeRelayTarget ?? appModel.selectedRelayTarget {
                    Section {
                        Button {
                            appModel.disconnect()
                            dismiss()
                        } label: {
                            Label("Disconnect from this relay", systemImage: "antenna.radiowaves.left.and.right.slash")
                        }
                        Button(role: .destructive) {
                            confirmRemoveRelay = true
                        } label: {
                            Label("Remove this pairing from this phone", systemImage: "trash")
                        }
                    } header: {
                        Text("Mercury Relay — \(target.displayLabel)")
                    } footer: {
                        Text("Disconnecting returns you to the connect screen and keeps the pairing. Removing deletes this phone's key for the host; the host still lists the device until you revoke it there.")
                    }
                    .confirmationDialog(
                        "Remove the pairing with \(target.displayLabel)?",
                        isPresented: $confirmRemoveRelay,
                        titleVisibility: .visible
                    ) {
                        Button("Remove pairing", role: .destructive) {
                            Task {
                                appModel.disconnect()
                                await appModel.removeRelay(target)
                                await appModel.loadRelayTargets()
                                dismiss()
                            }
                        }
                    }
                } else {
                    Section {
                        Button(role: .destructive) {
                            Task { await appModel.signOut() }
                        } label: {
                            Label("Sign out of this server", systemImage: "rectangle.portrait.and.arrow.right")
                        }
                    } header: {
                        Text(appModel.serverOrigin ?? "Not connected")
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { dismiss() } label: {
                        Image(systemName: "chevron.left")
                    }
                    .accessibilityLabel("Back")
                }
            }
            .amoledScreen()
        }
        .interactiveDismissDisabled(true)
        .task {
            let model = appModel.makeRelayAppModel()
            relay = model
            await model.loadTargets()
            await appModel.loadRelayTargets()
        }
        .sheet(isPresented: $showRelayPairing, onDismiss: {
            relay?.cancelPairing()
            Task { await appModel.loadRelayTargets() }
        }) {
            if let relay { RelayPairingView(relay: relay) }
        }
    }

    private func settingsRow(_ title: String, subtitle: String, icon: String) -> some View {
        Label {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                Text(subtitle).font(.caption).foregroundStyle(Color.secondary)
            }
        } icon: {
            Image(systemName: icon).foregroundStyle(Color.accentPrimary)
        }
    }
}

private struct CronSettingsView: View {
    @Environment(AppModel.self) private var appModel
    @StateObject private var operations = OperationsController()

    var body: some View {
        CronPanel(
            jobs: operations.jobs,
            isLoading: operations.isLoading,
            loadError: operations.loadError,
            actionState: operations.actionState,
            onRefresh: {
                Task { try? await operations.refresh(profile: appModel.activeProfile) }
            },
            onSetEnabled: { job, enabled in
                Task { await operations.setEnabled(enabled, job: job, profile: appModel.activeProfile) }
            },
            onRunNow: { job in
                Task { await operations.runNow(job) }
            }
        )
        .task { await operations.connect(appModel: appModel) }
        .onDisappear { Task { await operations.stop() } }
        .amoledScreen()
    }
}

private struct OfflineSettingsView: View {
    @Environment(AppModel.self) private var appModel

    var body: some View {
        Form {
            Section {
                Toggle(
                    "Save conversations for offline reading",
                    isOn: Binding(
                        get: { appModel.transcriptCachingEnabled },
                        set: { value in Task { await appModel.setTranscriptCachingEnabled(value) } }
                    )
                )
            } footer: {
                Text("Session titles remain available for cached-first loading. Conversation tails are opt-in, encrypted with a Keychain-protected key, origin/profile/session scoped, and expire after 30 days.")
            }

            Section {
                Button("Clear offline cache", role: .destructive) {
                    Task { await appModel.clearOfflineCache() }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .navigationTitle("Offline & Privacy")
        .amoledScreen()
    }
}

private struct VoiceSettingsView: View {
    @AppStorage(VoiceDisplayPreferences.playbackControlsKey)
    private var showMessagePlaybackControls = false

    var body: some View {
        List {
            Section("Composer") {
                Label("On-device dictation", systemImage: "mic.fill")
                Text("The app-owned microphone inserts recognized speech into your draft. Stop is always explicit and dictation never sends a message.")
                    .font(.footnote)
                    .foregroundStyle(Color.secondary)
            }
            Section("Replies") {
                Label("Read aloud", systemImage: "speaker.wave.2")
                Toggle("Show message playback controls", isOn: $showMessagePlaybackControls)
                Text("When enabled, completed assistant replies show a playback button. Reasoning, actions, processes, and empty rows never show one.")
                    .font(.footnote)
                    .foregroundStyle(Color.secondary)
            }
        }
        .scrollContentBackground(.hidden)
        .navigationTitle("Voice")
        .amoledScreen()
    }
}

private struct NotificationSettingsView: View {
    @Environment(AppModel.self) private var appModel
    @State private var lastTestFiredAt: Date?

    private var status: MercuryNotificationAuthorizationStatus {
        appModel.notificationAuthorizationStatus
    }

    private var prefs: MercuryNotificationPreferences {
        appModel.notificationPreferences
    }

    var body: some View {
        List {
            statusSection

            if status.countsAsAuthorized && prefs.notificationsEnabled {
                alertsSection
            }

            Section {
                Label("Relay push and local delivery", systemImage: "info.circle")
                Text(appModel.relayPush.status).accessibilityIdentifier("relay-push-status")
                    .font(.subheadline.weight(.semibold))
                Text("Supported Relay hosts can send a generic Apple push alert while Mercury is suspended. Direct connections and older hosts use best-effort local delivery, which may be delayed until you reopen Mercury. Live Activities stop updating when iOS suspends the app.")
                    .font(.footnote)
                    .foregroundStyle(Color.secondary)
            } header: {
                Text("About delivery")
            }

            Section {
                Text("Direct connections use local, best-effort notifications. Supported paired Relay hosts can send a generic alert through Apple; session content stays encrypted and is loaded only after you open Mercury.")
                    .font(.footnote)
                    .foregroundStyle(Color.secondary)
            }

            #if DEBUG
            Section {
                Button {
                    Task {
                        let granted = await appModel.notificationAuthorizationGranted()
                        if !granted {
                            _ = await appModel.requestNotificationAuthorization()
                        }
                        await appModel.fireTestNotification()
                        await appModel.refreshNotificationAuthorizationStatus()
                        lastTestFiredAt = Date()
                    }
                } label: {
                    Label("Send a test notification", systemImage: "paperplane")
                }
                if let lastTestFiredAt {
                    Text("Fired at \(lastTestFiredAt.formatted(date: .omitted, time: .standard)). Lock the phone or swipe to the Home Screen to see the banner — iOS hides banners while you're on this exact screen.")
                        .font(.footnote)
                        .foregroundStyle(Color.secondary)
                }
            } header: {
                Text("Test")
            } footer: {
                Text("Sends one local notification through the real delivery path so you can confirm banners work on this device.")
            }
            #endif
        }
        .scrollContentBackground(.hidden)
        .navigationTitle("Notifications")
        .amoledScreen()
        .task {
            await appModel.refreshNotificationAuthorizationStatus()
        }
    }

    @ViewBuilder
    private var statusSection: some View {
        Section {
            switch status {
            case .authorized, .provisional, .ephemeral:
                Toggle(
                    "Notifications",
                    isOn: Binding(
                        get: { prefs.notificationsEnabled },
                        set: { value in
                            appModel.updateNotificationPreferences { $0.notificationsEnabled = value }
                        }
                    )
                )
            case .denied:
                Label("Notifications are turned off in iOS Settings", systemImage: "bell.slash")
                    .foregroundStyle(Color.secondary)
                Button {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                } label: {
                    Label("Open iOS Settings", systemImage: "gear")
                }
            case .notDetermined, .unknown:
                Button {
                    Task {
                        _ = await appModel.requestNotificationAuthorization()
                        await appModel.refreshNotificationAuthorizationStatus()
                        if appModel.notificationAuthorizationStatus.countsAsAuthorized {
                            appModel.updateNotificationPreferences { prefs in
                                prefs.notificationsEnabled = true
                                prefs.completionEnabled = true
                                prefs.attentionEnabled = true
                                prefs.failureAndCancellationEnabled = true
                            }
                        }
                    }
                } label: {
                    Label("Enable notifications", systemImage: "bell.badge")
                }
            }
        } header: {
            Text("Status")
        } footer: {
            Text(statusFooter)
        }
    }

    private var statusFooter: String {
        switch status {
        case .authorized, .provisional, .ephemeral:
            return "Mercury notifies you when a turn finishes or the agent needs a decision. It never notifies for the session you're actively viewing."
        case .denied:
            return "Turn notifications on for Mercury in iOS Settings, then come back here to choose which alerts you want."
        case .notDetermined, .unknown:
            return "Not requested yet. Mercury only asks for permission when you tap Enable — never at launch."
        }
    }

    @ViewBuilder
    private var alertsSection: some View {
        Section {
            Toggle(
                "Turn completed",
                isOn: Binding(
                    get: { prefs.completionEnabled },
                    set: { value in
                        appModel.updateNotificationPreferences { $0.completionEnabled = value }
                    }
                )
            )
            Toggle(
                "Approval or information required",
                isOn: Binding(
                    get: { prefs.attentionEnabled },
                    set: { value in
                        appModel.updateNotificationPreferences { $0.attentionEnabled = value }
                    }
                )
            )
            Toggle(
                "Failure and cancellation",
                isOn: Binding(
                    get: { prefs.failureAndCancellationEnabled },
                    set: { value in
                        appModel.updateNotificationPreferences { $0.failureAndCancellationEnabled = value }
                    }
                )
            )
        } header: {
            Text("Alerts")
        }
    }
}
