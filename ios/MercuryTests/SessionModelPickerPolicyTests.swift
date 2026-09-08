import XCTest
@testable import Mercury

final class SessionModelPickerPolicyTests: XCTestCase {
    private let options = ModelOptions(
        current: ModelSelection(provider: "nous", model: "sol"),
        providers: [
            ModelProviderOption(slug: "openai", name: "OpenAI", models: ["gpt-5", "o3"], capabilities: [:]),
            ModelProviderOption(slug: "nous", name: "Nous", models: ["sol", "hermes-4"], capabilities: [:]),
        ]
    )

    func testInitialProviderUsesCurrentModelProviderWhenAvailable() {
        XCTAssertEqual(SessionModelPickerPolicy.initialProviderSlug(in: options), "nous")
    }

    func testInitialProviderFallsBackToFirstAdvertisedProvider() {
        let unavailableCurrent = ModelOptions(
            current: ModelSelection(provider: "missing", model: "ghost"),
            providers: options.providers
        )
        XCTAssertEqual(SessionModelPickerPolicy.initialProviderSlug(in: unavailableCurrent), "openai")
    }

    func testSearchFiltersOnlyTheSelectedProviderCaseInsensitively() {
        XCTAssertEqual(
            SessionModelPickerPolicy.models(in: options, providerSlug: "nous", query: "HERM"),
            ["hermes-4"]
        )
        XCTAssertEqual(
            SessionModelPickerPolicy.models(in: options, providerSlug: "openai", query: ""),
            ["gpt-5", "o3"]
        )
    }

    func testUnknownProviderHasNoModels() {
        XCTAssertEqual(
            SessionModelPickerPolicy.models(in: options, providerSlug: "missing", query: ""),
            []
        )
    }

    func testCapabilitiesResolveAQualifiedResumedModelFromTheSessionCatalog() {
        let catalog = ModelOptions(
            current: ModelSelection(provider: "openai", model: "gpt-5"),
            providers: [
                ModelProviderOption(
                    slug: "openai",
                    name: "OpenAI",
                    models: ["gpt-5"],
                    capabilities: [
                        "gpt-5": ModelCapabilities(fast: true, reasoning: true)
                    ]
                )
            ]
        )

        XCTAssertEqual(
            SessionModelPickerPolicy.capabilities(
                in: catalog,
                for: ModelSelection(provider: "openai", model: "openai/gpt-5")
            ),
            ModelCapabilities(fast: true, reasoning: true)
        )
    }

    @MainActor
    func testLateCatalogHydratesTheResumedSelectionWithoutPickerState() {
        let state = ChatSessionState(
            sessionID: "durable-1",
            title: "Session",
            isNewSession: false,
            incomingShare: nil
        )
        let resumed = ModelSelection(provider: "openai", model: "openai/gpt-5")
        state.applyModelSelection(resumed)

        let catalog = ModelOptions(
            current: ModelSelection(provider: "openai", model: "gpt-5"),
            providers: [
                ModelProviderOption(
                    slug: "openai",
                    name: "OpenAI",
                    models: ["gpt-5"],
                    capabilities: [
                        "gpt-5": ModelCapabilities(fast: true, reasoning: true)
                    ]
                )
            ]
        )
        state.applyModelOptions(catalog)

        XCTAssertEqual(state.currentModelSelection, resumed)
        XCTAssertEqual(
            state.currentModelCapabilities,
            ModelCapabilities(fast: true, reasoning: true)
        )
    }
}
