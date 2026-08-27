import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// Server-origin entry screen with a mode picker: self-hosted (origin field)
/// or Hermes Cloud (Portal device-code sign-in + agent picker).
struct ConnectView: View {
    @Environment(AppModel.self) private var appModel

    @State private var mode: ConnectionMode = .selfHosted
    @State private var originText = ""
    @State private var validationError: String?
    @State private var showSavedServers = false
    /// Error banner text injected when arriving via `.failed(_)` phase.
    private let bannerMessage: String?

    /// True while a network step (probe or portal request) is in flight.
    private var isBusy: Bool {
        appModel.connectionPhase == .probing ||
            appModel.connectionPhase == .connecting ||
            appModel.isCloudPolling
    }

    init(errorMessage: String? = nil) {
        bannerMessage = errorMessage
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Spacer()

            Text("Connect to Hermes")
                .font(.largeTitle.bold())
                .foregroundStyle(Color.primary)

            Text("Mercury is a companion for the Hermes agent you already run — self-hosted or on Hermes Cloud. Official Hermes endpoints only.")
                .font(.subheadline)
                .foregroundStyle(Color.secondary)

            Picker("Connection type", selection: $mode) {
                ForEach(ConnectionMode.allCases) { m in
                    Text(m.title).tag(m)
                }
            }
            .pickerStyle(.segmented)

            switch mode {
            case .selfHosted:
                selfHostedSection
            case .hermesCloud:
                cloudSection
            }

            Spacer()
        }
        .padding(24)
        .amoledScreen()
        .sheet(isPresented: $showSavedServers) {
            NavigationStack {
                ServerListView(
                    catalog: appModel.serverCatalog,
                    onSelect: { entry in
                        showSavedServers = false
                        Task { await appModel.switchServer(entry) }
                    },
                    onAdd: { origin, label in Task { await appModel.addServer(origin: origin, label: label) } },
                    onEditLabel: { entry, label in Task { await appModel.renameServer(entry, label: label) } },
                    onRemove: { entry in Task { await appModel.removeServer(entry) } }
                )
            }
        }
    }

    // MARK: - Self-hosted

    @ViewBuilder
    private var selfHostedSection: some View {
        if let bannerMessage {
            errorBanner(bannerMessage)
        }

        TextField("hermes.example.com", text: $originText)
            .keyboardType(.URL)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .textContentType(.URL)
            .padding(12)
            .background(Color.surfaceLow, in: RoundedRectangle(cornerRadius: 10))
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .strokeBorder(
                        validationError == nil ? Color.separatorSubtle : Color.statusAlert.opacity(0.6)
                    )
            )
            .submitLabel(.go)
            .onSubmit(continueTapped)

        if let validationError {
            Text(validationError)
                .font(.caption)
                .foregroundStyle(Color.statusAlert)
        }

        Button(action: continueTapped) {
            HStack {
                if isBusy {
                    ProgressView().controlSize(.small).tint(.white)
                }
                Text("Continue")
                    .fontWeight(.semibold)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
        }
        .buttonStyle(.borderedProminent)
        .tint(Color.accentPrimary)
        .disabled(isBusy)

        if !appModel.serverCatalog.entries.isEmpty {
            Button("Saved servers") { showSavedServers = true }
                .buttonStyle(.bordered)
        }

        Label(mode.subtitle, systemImage: mode.icon)
            .font(.caption)
            .foregroundStyle(Color.secondary)
    }

    // MARK: - Hermes Cloud

    @ViewBuilder
    private var cloudSection: some View {
        Button(action: cloudSignInTapped) {
            HStack {
                if isBusy {
                    ProgressView().controlSize(.small).tint(.white)
                }
                Image(systemName: "person.crop.circle.badge.checkmark")
                Text("Sign in to Nous Portal")
                    .fontWeight(.semibold)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
        }
        .buttonStyle(.borderedProminent)
        .tint(Color.accentPrimary)
        .disabled(isBusy)

        if let deviceCode = appModel.pendingPortalDeviceCode, appModel.isCloudPolling {
            VStack(alignment: .leading, spacing: 6) {
                Text("Authorize Mercury with code")
                    .font(.caption)
                    .foregroundStyle(Color.secondary)
                Text(deviceCode.userCode)
                    .font(.title2.monospaced().bold())
                    .textSelection(.enabled)
                Label("Waiting for authorization…", systemImage: "clock.arrow.circlepath")
                    .font(.caption)
                    .foregroundStyle(Color.secondary)
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.surfaceLow, in: RoundedRectangle(cornerRadius: 10))
        }

        if !appModel.cloudOrganizations.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("Choose an organization").font(.headline)
                ForEach(appModel.cloudOrganizations, id: \.id) { organization in
                    Button(organization.name) {
                        Task {
                            do {
                                try await appModel.selectCloudOrganization(organization)
                            } catch {
                                validationError = "Could not load that organization's agents."
                            }
                        }
                    }
                    .buttonStyle(.bordered)
                }
            }
        }

        if !appModel.cloudAgents.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("Your agents").font(.headline)
                ForEach(appModel.cloudAgents, id: \.id) { agent in
                    Button {
                        Task { await appModel.selectAgent(agent) }
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(agent.name).fontWeight(.semibold)
                                Text(agent.gatewayState ?? agent.status)
                                    .font(.caption)
                                    .foregroundStyle(Color.secondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(agent.dashboardURL == nil)
                }
            }
        } else if appModel.cloudDiscoveryComplete {
            Label("No provisioned agents are available for this account.", systemImage: "cloud")
                .font(.caption)
                .foregroundStyle(Color.secondary)
        }

        if let validationError {
            errorBanner(validationError)
        }

        VStack(alignment: .leading, spacing: 8) {
            Label("Sign in once, then pick an agent from your account.", systemImage: "checkmark.circle")
            Label("No URL to paste — agents are discovered automatically.", systemImage: "sparkle.magnifyingglass")
            Label("Multi-org accounts get an org chooser.", systemImage: "person.2")
        }
        .font(.caption)
        .foregroundStyle(Color.secondary)
        .padding(.top, 4)
    }

    private func errorBanner(_ text: String) -> some View {
        Label(text, systemImage: "exclamationmark.triangle.fill")
            .font(.footnote)
            .foregroundStyle(Color.statusAlert)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.surfaceMid, in: RoundedRectangle(cornerRadius: 10))
    }

    // MARK: - Actions

    private func continueTapped() {
        validationError = nil
        guard ServerOrigin.normalize(originText) != nil else {
            validationError = "That doesn't look like a server address. Try something like hermes.example.com or 192.168.1.20:8080."
            return
        }
        Task { await appModel.probeSelfHosted(origin: originText) }
    }

    private func cloudSignInTapped() {
        validationError = nil
        Task {
            do {
                let start = try await appModel.startCloudSignIn()
                await Self.openInBrowser(start.verificationURL)
                try await appModel.completeCloudSignIn(start.deviceCode)
            } catch {
                validationError = "Could not reach Nous Portal — check your network and try again."
            }
        }
    }

    @MainActor
    private static func openInBrowser(_ url: URL) async {
        #if canImport(UIKit)
        _ = await UIApplication.shared.open(url)
        #endif
    }
}

#Preview {
    ConnectView()
        .environment(AppModel())
        .preferredColorScheme(.dark)
}
