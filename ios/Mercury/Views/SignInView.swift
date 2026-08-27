import SwiftUI

/// Sign-in screen shown when the server reports `auth_required` and
/// advertises Nous sign-in. The native PKCE flow opens the authorize URL in
/// the user's browser, awaits the loopback callback, exchanges the code, and
/// persists credentials — all inside `ConnectionController`.
struct SignInView: View {
    @Environment(AppModel.self) private var appModel: AppModel
    @State private var showPasswordSignIn = false
    @State private var username = "admin"
    @State private var password = ""

    private var hasNousProvider: Bool {
        appModel.authProviders.contains { $0.name.lowercased() == "nous" }
    }

    private var hasPasswordProvider: Bool {
        appModel.authProviders.contains(where: \.supportsPassword)
    }

    static func nousButtonTitle(isSigningIn: Bool, authenticationError: String?) -> String {
        if !isSigningIn, authenticationError != nil {
            return "Retry sign in"
        }
        return "Sign in with Nous"
    }

    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            Image(systemName: "lock.shield")
                .font(.system(size: 48))
                .foregroundStyle(Color.accentPrimary)

            Text("Sign in required")
                .font(.title2.bold())
                .foregroundStyle(Color.primary)

            if let origin = appModel.serverOrigin {
                Text(origin)
                    .font(.footnote.monospaced())
                    .foregroundStyle(Color.secondary)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Color.surfaceLow, in: Capsule())
            }

            Text("This Hermes server requires authentication. Choose one of the methods advertised by this server.")
                .font(.subheadline)
                .foregroundStyle(Color.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 8)

            if let error = appModel.authenticationError {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
                    .foregroundStyle(Color.statusAlert)
                    .multilineTextAlignment(.center)
            }

            if hasPasswordProvider {
                Button {
                    showPasswordSignIn = true
                } label: {
                    Text("Sign in with username and password")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .tint(Color.accentPrimary)
                .disabled(appModel.isSigningIn)
            }

            if hasNousProvider {
                Button(action: signInTapped) {
                    HStack {
                        if appModel.isSigningIn {
                            ProgressView().controlSize(.small)
                        }
                        Text(Self.nousButtonTitle(
                            isSigningIn: appModel.isSigningIn,
                            authenticationError: appModel.authenticationError
                        ))
                            .fontWeight(.semibold)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
                }
                .buttonStyle(.bordered)
                .tint(Color.accentPrimary)
                .disabled(appModel.isSigningIn)
            }

            Button("Use a different server") {
                appModel.reset()
            }
            .font(.footnote)
            .foregroundStyle(Color.secondary)

            Spacer()
        }
        .padding(24)
        .amoledScreen()
        .sheet(isPresented: $showPasswordSignIn) {
            NavigationStack {
                Form {
                    Section {
                        TextField("Username", text: $username)
                            .textContentType(.username)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .onChange(of: username) {
                                if username.count > 256 { username = String(username.prefix(256)) }
                            }
                        SecureField("Password", text: $password)
                            .textContentType(.password)
                            .onChange(of: password) {
                                if password.count > 4_096 { password = String(password.prefix(4_096)) }
                            }
                    } footer: {
                        Text("Your password is sent only to this Hermes server for the current sign-in and is never stored by Mercury.")
                    }
                }
                .scrollContentBackground(.hidden)
                .navigationTitle("Password sign-in")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") {
                            password = ""
                            showPasswordSignIn = false
                        }
                        .disabled(appModel.isSigningIn)
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Sign in") { passwordSignInTapped() }
                            .disabled(
                                username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                    || password.isEmpty
                                    || appModel.isSigningIn
                            )
                    }
                }
                .amoledScreen()
            }
            .presentationDetents([.medium])
        }
    }

    private func signInTapped() {
        Task { await appModel.beginSelfHostedSignInAndAwaitBrowser() }
    }

    private func passwordSignInTapped() {
        let submittedUsername = username
        let submittedPassword = password
        password = ""
        showPasswordSignIn = false
        Task {
            await appModel.signInWithPassword(
                username: submittedUsername,
                password: submittedPassword
            )
        }
    }
}

#Preview {
    SignInView()
        .environment(AppModel())
        .preferredColorScheme(.dark)
}
