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
}
