import Foundation

enum SessionModelPickerPolicy {
    static func initialProviderSlug(in options: ModelOptions) -> String? {
        if let current = options.current,
           options.providers.contains(where: { $0.slug == current.provider }) {
            return current.provider
        }
        return options.providers.first?.slug
    }

    static func models(
        in options: ModelOptions,
        providerSlug: String,
        query: String
    ) -> [String] {
        guard let provider = options.providers.first(where: { $0.slug == providerSlug }) else {
            return []
        }
        let normalized = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return provider.models }
        return provider.models.filter { $0.localizedCaseInsensitiveContains(normalized) }
    }
}
