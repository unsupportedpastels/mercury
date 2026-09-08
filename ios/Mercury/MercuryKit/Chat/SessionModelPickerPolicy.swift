import Foundation
import MercuryCore

enum SessionModelPickerPolicy {
    /// Resolve only explicitly advertised capabilities for the model that the
    /// session actually resumed. A session response may use a qualified model
    /// identifier while `model.options` keys the same model by its final
    /// component; accept that one unambiguous alias without guessing across
    /// providers or models.
    static func capabilities(
        in options: ModelOptions?,
        for selection: ModelSelection?
    ) -> ModelCapabilities? {
        guard let options, let selection,
              let provider = options.providers.first(where: { $0.slug == selection.provider }) else {
            return nil
        }
        let catalog = provider.capabilities.mapValues { value in
            ModelCapabilitiesSpec(
                fast: value.fast.map { KotlinBoolean(bool: $0) },
                reasoning: value.reasoning.map { KotlinBoolean(bool: $0) }
            )
        }
        guard let result = ModelCapabilityPolicy.shared.fromCatalog(model: selection.model, capabilities: catalog) else {
            return nil
        }
        return ModelCapabilities(fast: result.fast?.boolValue, reasoning: result.reasoning?.boolValue)
    }

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
