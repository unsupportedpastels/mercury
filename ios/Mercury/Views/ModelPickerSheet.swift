import SwiftUI

/// Android-parity, server-backed model selection for the current runtime session only.
struct ModelPickerSheet: View {
    let options: ModelOptions?
    let selection: ModelSelection?
    let isLoading: Bool
    let isApplying: Bool
    let errorMessage: String?
    let onRetry: () -> Void
    let onSelectModel: (ModelSelection) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var selectedProviderSlug: String?
    @State private var query = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header

            if isLoading && options == nil {
                HStack(spacing: 12) {
                    ProgressView()
                    Text("Loading models…")
                        .foregroundStyle(Color.secondary)
                }
                .frame(maxWidth: .infinity, minHeight: 180)
            } else if let options, !options.providers.isEmpty {
                readyContent(options)
            } else {
                ContentUnavailableView {
                    Label("Models unavailable", systemImage: "cpu")
                } description: {
                    Text(errorMessage ?? "The server did not return a model catalog.")
                } actions: {
                    Button("Try Again", action: onRetry)
                }
                .frame(maxWidth: .infinity, minHeight: 220)
            }
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 16)
        .amoledScreen()
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .interactiveDismissDisabled(isApplying)
        .onAppear { synchronizeProvider() }
        .onChange(of: options) { synchronizeProvider() }
        .overlay {
            if isApplying {
                ProgressView("Applying model…")
                    .padding(18)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text("Choose model")
                    .font(.title2.bold())
                Spacer()
                Button("Done") { dismiss() }
                    .disabled(isApplying)
            }
            Text("Applies to this session only")
                .font(.footnote)
                .foregroundStyle(Color.secondary)
        }
    }

    @ViewBuilder
    private func readyContent(_ options: ModelOptions) -> some View {
        let selectedSlug = resolvedProviderSlug(in: options)
        let selectedProvider = options.providers.first { $0.slug == selectedSlug }
        let matchingModels = SessionModelPickerPolicy.models(
            in: options,
            providerSlug: selectedSlug,
            query: query
        )

        if let errorMessage {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
                    .foregroundStyle(Color.statusAlert)
                Spacer()
                Button("Retry", action: onRetry)
                    .font(.footnote.weight(.semibold))
                    .disabled(isApplying)
            }
        }

        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(options.providers, id: \.slug) { provider in
                        providerButton(provider, selected: provider.slug == selectedSlug)
                            .id(provider.slug)
                    }
                }
            }
            .onAppear {
                proxy.scrollTo(selectedSlug, anchor: .center)
            }
            .onChange(of: selectedProviderSlug) {
                guard let selectedProviderSlug else { return }
                withAnimation(.easeInOut(duration: 0.2)) {
                    proxy.scrollTo(selectedProviderSlug, anchor: .center)
                }
            }
        }
        .accessibilityLabel("Model providers")

        HStack(spacing: 9) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(Color.secondary)
            TextField(
                "Search \(selectedProvider?.name ?? selectedSlug) models",
                text: Binding(
                    get: { query },
                    set: { query = String($0.prefix(128)) }
                )
            )
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            if !query.isEmpty {
                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(Color.secondary)
                }
                .accessibilityLabel("Clear model search")
            }
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 44)
        .background(Color.surfaceLow, in: RoundedRectangle(cornerRadius: 12))
        .overlay {
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color.separatorSubtle, lineWidth: 1)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Search models")

        if matchingModels.isEmpty {
            Text("No models match your search.")
                .foregroundStyle(Color.secondary)
                .frame(maxWidth: .infinity, minHeight: 120, alignment: .topLeading)
                .padding(.top, 8)
        } else {
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(matchingModels, id: \.self) { model in
                        let candidate = ModelSelection(provider: selectedSlug, model: model)
                        modelRow(
                            candidate,
                            providerName: selectedProvider?.name ?? selectedSlug,
                            current: candidate == selection
                        )
                        if model != matchingModels.last {
                            Divider().overlay(Color.separatorSubtle)
                        }
                    }
                }
            }
            .background(Color.surfaceLow, in: RoundedRectangle(cornerRadius: 14))
            .clipShape(RoundedRectangle(cornerRadius: 14))
        }
    }

    private func providerButton(_ provider: ModelProviderOption, selected: Bool) -> some View {
        Button {
            selectedProviderSlug = provider.slug
            query = ""
        } label: {
            HStack(spacing: 6) {
                if selected {
                    Image(systemName: "checkmark")
                        .font(.caption.bold())
                }
                Text(provider.name)
                    .font(.subheadline.weight(.semibold))
            }
            .foregroundStyle(selected ? Color.amoledBlack : Color.primary)
            .padding(.horizontal, 13)
            .frame(minHeight: 36)
            .background(selected ? Color.accentPrimary : Color.surfaceMid, in: Capsule())
        }
        .buttonStyle(.plain)
        .disabled(isApplying)
        .accessibilityLabel("Provider \(provider.name)")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func modelRow(
        _ candidate: ModelSelection,
        providerName: String,
        current: Bool
    ) -> some View {
        Button {
            onSelectModel(candidate)
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(candidate.model)
                        .font(.body)
                        .foregroundStyle(Color.primary)
                        .lineLimit(2)
                    Text(providerName)
                        .font(.caption)
                        .foregroundStyle(Color.secondary)
                }
                Spacer()
                if current {
                    Text("Current")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Color.accentPrimary)
                }
            }
            .contentShape(Rectangle())
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
        }
        .buttonStyle(.plain)
        .disabled(isApplying)
        .accessibilityLabel("Select \(providerName) \(candidate.model)")
        .accessibilityValue(current ? "Current model" : "")
        .accessibilityAddTraits(current ? .isSelected : [])
    }

    private func resolvedProviderSlug(in options: ModelOptions) -> String {
        if let selectedProviderSlug,
           options.providers.contains(where: { $0.slug == selectedProviderSlug }) {
            return selectedProviderSlug
        }
        return SessionModelPickerPolicy.initialProviderSlug(in: options) ?? ""
    }

    private func synchronizeProvider() {
        guard let options else { return }
        let resolved = resolvedProviderSlug(in: options)
        if selectedProviderSlug != resolved {
            selectedProviderSlug = resolved
            query = ""
        }
    }
}
