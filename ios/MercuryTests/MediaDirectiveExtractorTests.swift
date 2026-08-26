import Foundation
import XCTest
@testable import Mercury

/// Port of the Android `ArtifactExtractorTest` matrix for the Swift
/// `MediaDirectiveExtractor`. Every Kotlin case is mirrored; a few extra
/// Swift-specific cases cover the `java.net.URI` bridging (ports, encoded
/// controls, path traversal, `<...>` wrapping).
final class MediaDirectiveExtractorTests: XCTestCase {
    // MARK: - Helpers

    private func message(_ text: String) -> MediaExtractionMessage {
        MediaExtractionMessage(text: text)
    }

    // MARK: Standalone MEDIA directives

    func testExtractsStandaloneMediaDirectiveAsManagedImage() {
        let artifacts = MediaDirectiveExtractor.extract(
            messages: [
                message("Here is the result.\n  MEDIA: '/home/user/project/generated mockup.png'  \n"),
            ]
        )

        XCTAssertEqual(artifacts.count, 1)
        XCTAssertEqual(artifacts.first?.type, .image)
        XCTAssertEqual(artifacts.first?.origin, .managedPath)
        XCTAssertEqual(artifacts.first?.source, "/home/user/project/generated mockup.png")
        XCTAssertEqual(artifacts.first?.displayName, "generated mockup.png")
        XCTAssertTrue(artifacts.first!.stableIdentity.hasPrefix("managed:"))
    }

    func testAcceptsFirstPartyQuoteAndBacktickFormsButOnlyOnStandaloneLines() {
        let artifacts = MediaDirectiveExtractor.extract(
            """
            MEDIA:/tmp/one.mp3
            `MEDIA:/tmp/two.wav`
            'MEDIA:/tmp/three.ogg'
            prose MEDIA:/tmp/not-an-artifact.png here
            MEDIA:/tmp/malformed path.png
            MEDIA:"/tmp/unclosed.png
            """
        )

        XCTAssertEqual(artifacts.map { $0.displayName }, ["one.mp3", "two.wav", "three.ogg"])
        XCTAssertTrue(artifacts.allSatisfy { $0.type == .audio })
    }

    // MARK: HTTPS URLs and markdown links

    func testExtractsExplicitHttpsAndMarkdownImageAudioAndFileLinks() {
        let artifacts = MediaDirectiveExtractor.extract(
            """
            ![generated image](https://cdn.example/assets/result.PNG)
            [Audio: voice recording](https://cdn.example/audio/voice.mp3?download=1)
            [report.pdf](https://files.example/download/report.pdf)
            https://cdn.example/assets/standalone.webp
            """
        )

        XCTAssertEqual(artifacts.count, 4)
        XCTAssertEqual(artifacts.map { $0.type }, [.image, .audio, .file, .image])
        XCTAssertEqual(artifacts[0].origin, .remoteURL)
        XCTAssertEqual(artifacts[0].displayName, "result.PNG")
        XCTAssertFalse(artifacts.contains { $0.source.contains("#") })
    }

    // MARK: Rejections

    func testRejectsUnsafeUrlsMalformedSourcesAndArbitraryProse() {
        let credentialedURL = "https://user" + ":pass@example.com/secret.png"
        let artifacts = MediaDirectiveExtractor.extract(
            """
            [userinfo](\(credentialedURL))
            [fragment](https://example.com/image.png#fragment)
            [http](http://example.com/image.png)
            [file](file:///tmp/secret.png)
            [data](data:image/png;base64,AAAA)
            [local](https://localhost/image.png)
            [private](https://192.168.1.2/image.png)
            This prose mentions https://example.com/not-a-link.png but is not a deliverable.
            MEDIA:javascript:alert(1)
            MEDIA:/tmp/bad\u{0}name.png
            """
        )

        XCTAssertTrue(artifacts.isEmpty)
    }

    // MARK: Dedupe and bounds

    func testDeduplicatesByStableSourceIdentityAndAppliesBounds() {
        let repeated = """
            MEDIA:/tmp/output.png
            ![same](https://EXAMPLE.com:443/output.png)
            MEDIA:/tmp/output.png
            [same](https://example.com/output.png)
            MEDIA:/tmp/second.mp3
            """
        let artifacts = MediaDirectiveExtractor.extract(
            repeated,
            limits: ArtifactExtractionLimits(maxItems: 2)
        )

        XCTAssertEqual(artifacts.count, 2)
        XCTAssertEqual(artifacts[0].source, "/tmp/output.png")
        XCTAssertEqual(artifacts[1].source, "https://example.com/output.png")
        XCTAssertEqual(
            artifacts[1].stableIdentity,
            MediaDirectiveExtractor.extract("[again](https://example.com/output.png)").first!.stableIdentity
        )
    }

    func testCapsTranscriptSourceLocationAndDisplayNameLengths() {
        let longName = "abcdefghijkl.png"
        let text = "MEDIA:/a/\(longName)\nMEDIA:/b/second-long-name.png"
        let artifacts = MediaDirectiveExtractor.extract(
            text,
            limits: ArtifactExtractionLimits(
                maxTranscriptChars: text.utf16.count,
                maxDisplayNameChars: 12,
                maxSourceChars: 20,
                maxLocationChars: 20
            )
        )

        XCTAssertEqual(artifacts.count, 1)
        XCTAssertLessThanOrEqual(artifacts.first!.displayName.utf16.count, 12)
        XCTAssertLessThanOrEqual(artifacts.first!.source.utf16.count, 20)
    }

    // MARK: Message text only

    func testExtractsOnlyMessageTextNotReasoningAndSanitizesDisplayLabels() {
        let artifacts = MediaDirectiveExtractor.extract(
            messages: [
                MediaExtractionMessage(
                    text: "[download](https://files.example/download)",
                    reasoningText: "MEDIA:/tmp/hidden.png"
                ),
            ]
        )

        XCTAssertEqual(artifacts.count, 1)
        XCTAssertEqual(artifacts.first?.type, .file)
        XCTAssertEqual(artifacts.first?.displayName, "download")
        XCTAssertFalse(artifacts.first!.displayName.contains("/"))
    }

    // MARK: - Swift-port extras: managed-path canonicalization

    func testManagedPathCanonicalizationAndTraversalRejections() {
        let accepted = MediaDirectiveExtractor.extract(
            """
            MEDIA:/tmp/./a.png
            MEDIA:/tmp//b.png
            MEDIA:/tmp/c/../d.png
            """
        )
        // '..' anywhere rejects the whole source, so only the first two land.
        XCTAssertEqual(accepted.map { $0.source }, ["/tmp/a.png", "/tmp/b.png"])

        let rejected = MediaDirectiveExtractor.extract(
            """
            MEDIA:/tmp/../secret.png
            MEDIA:..//evil.png
            MEDIA:/tmp/trailing/
            MEDIA:/tmp/back\\slash.png
            MEDIA://double-slash.png
            MEDIA:/tmp/
            MEDIA:/tmp/..
            """
        )
        XCTAssertTrue(rejected.isEmpty)
    }

    // MARK: - Swift-port extras: URL safety and canonicalization

    func testHttpsUrlSafetyAndCanonicalization() {
        let artifacts = MediaDirectiveExtractor.extract(
            """
            <https://CDN.example:443/assets/a.png>
            https://cdn.example
            https://cdn.example?raw=query&kept=1
            https://cdn.example/ok/..%2Fnot-traversal.png
            """
        )
        XCTAssertEqual(artifacts.count, 4)
        XCTAssertEqual(artifacts[0].source, "https://cdn.example/assets/a.png")
        XCTAssertEqual(artifacts[1].source, "https://cdn.example/")
        XCTAssertEqual(artifacts[2].source, "https://cdn.example/?raw=query&kept=1")
        XCTAssertEqual(artifacts[3].source, "https://cdn.example/ok/..%2Fnot-traversal.png")
    }

    func testRejectsHostileHttpsVariants() {
        let rejected = MediaDirectiveExtractor.extract(
            """
            https://example.com:8443/a.png
            https://user@example.com/a.png
            https://example.com/a.png%00
            https://sub.localhost/a.png
            https://printer.local/a.png
            https://10.0.0.7/a.png
            https://example_underscore.com/a.png
            https://[2001:db8::1]/a.png
            https://example.com/a%zz.png
            https://example.com/a.png?x=%1b
            """
        )
        XCTAssertTrue(rejected.isEmpty)
    }

    // MARK: - Swift-port extras: markdown destination forms

    func testMarkdownAngleBracketDestinationsAndLabelPrefixes() {
        let artifacts = MediaDirectiveExtractor.extract(
            """
            [Image: chart](<https://cdn.example/chart.bmp>)
            [Video: clip](https://cdn.example/clip.mkv)
            [plain](<https://files.example/plain>)
            """
        )
        XCTAssertEqual(artifacts.count, 3)
        // Extension beats label prefix.
        XCTAssertEqual(artifacts[0].type, .image)
        // Label prefix only promotes image/audio; video falls back to file,
        // and the prefix is stripped from the display name.
        XCTAssertEqual(artifacts[1].type, .file)
        XCTAssertEqual(artifacts[1].displayName, "clip.mkv")
        XCTAssertEqual(artifacts[2].type, .file)
        XCTAssertEqual(artifacts[2].displayName, "plain")
    }

    // MARK: - Swift-port extras: consumption budget across messages

    func testPerMessageTranscriptBudgetStopsExtraction() {
        let messages = [
            message("MEDIA:/tmp/first.png"),
            message(String(repeating: "x", count: 100) + "\nMEDIA:/tmp/second.png"),
            message("MEDIA:/tmp/third.png"),
        ]
        let limits = ArtifactExtractionLimits(maxTranscriptChars: 120)
        let artifacts = MediaDirectiveExtractor.extract(messages: messages, limits: limits)

        // Message two is truncated to its remaining budget (100 chars, which
        // is all padding), so its directive is cut off; the budget is then
        // exhausted and message three is never scanned.
        XCTAssertEqual(artifacts.map { $0.source }, ["/tmp/first.png"])
    }

    func testMaxItemsBudgetStopsAcrossMessages() {
        let messages = [
            message("MEDIA:/tmp/a.png"),
            message("MEDIA:/tmp/b.png"),
            message("MEDIA:/tmp/c.png"),
        ]
        let artifacts = MediaDirectiveExtractor.extract(
            messages: messages,
            limits: ArtifactExtractionLimits(maxItems: 2)
        )
        XCTAssertEqual(artifacts.map { $0.source }, ["/tmp/a.png", "/tmp/b.png"])
    }

    // MARK: - Swift-port extras: display-name sanitization

    func testDisplayNameSanitizesHostileContent() {
        let artifacts = MediaDirectiveExtractor.extract(
            """
            MEDIA:"/tmp/my report (final).png"
            MEDIA:/tmp/.hidden..png
            """
        )
        XCTAssertEqual(artifacts.count, 2)
        XCTAssertEqual(artifacts[0].displayName, "my report (final).png")
        XCTAssertEqual(artifacts[1].displayName, "hidden..png")
    }

    func testDisplayNameFallsBackToLabelAndArtifact() {
        let artifacts = MediaDirectiveExtractor.extract(
            """
            [Audio: briefing](https://files.example/briefing)
            [ ](https://files.example/)
            """
        )
        XCTAssertEqual(artifacts.count, 2)
        XCTAssertEqual(artifacts[0].displayName, "briefing")
        // Empty path name plus a blank stripped label fall back to "artifact".
        XCTAssertEqual(artifacts[1].displayName, "artifact")
    }

    // MARK: - Swift-port extras: limits validation and identity aliases

    func testLimitsDefaultsAndAliases() {
        let limits = ArtifactExtractionLimits()
        XCTAssertEqual(limits.maxTranscriptChars, 64 * 1024)
        XCTAssertEqual(limits.maxItems, 64)
        XCTAssertEqual(limits.maxDisplayNameChars, 128)
        XCTAssertEqual(limits.maxSourceChars, 4 * 1024)
        XCTAssertEqual(limits.maxLocationChars, 4 * 1024)

        let artifact = MediaDirectiveExtractor.extract("MEDIA:/tmp/alias.wav").first!
        XCTAssertEqual(artifact.identity, artifact.stableIdentity)
        XCTAssertEqual(artifact.location, artifact.source)
        XCTAssertEqual(artifact.origin, .managedPath)
    }

    func testTopLevelExtractArtifactsConvenience() {
        let artifacts = extractArtifacts(messages: [message("https://cdn.example/x.png")])
        XCTAssertEqual(artifacts.count, 1)
        XCTAssertEqual(artifacts.first?.identity, "remote:https://cdn.example/x.png")
    }

    func testAcceptsUppercasePercentEscapesAndDecodesDisplayName() {
        let artifacts = MediaDirectiveExtractor.extract("https://cdn.example/A%41.png")
        XCTAssertEqual(artifacts.count, 1)
        // Kotlin decodes twice (URI.getPath then URLDecoder), so %41 -> 'A'.
        XCTAssertEqual(artifacts.first?.displayName, "AA.png")
        XCTAssertEqual(artifacts.first?.type, .image)
    }
}
