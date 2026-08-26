import XCTest
@testable import MercuryShareKit

final class SharePayloadPolicyTests: XCTestCase {
    private func candidate(
        _ id: String,
        name: String,
        mime: String? = nil,
        bytes: Int64 = 10,
        readable: Bool = true
    ) -> SharedAttachmentCandidate {
        SharedAttachmentCandidate(
            id: id,
            stagedRelativePath: "staged/\(id)",
            displayName: name,
            mimeType: mime,
            sizeBytes: bytes,
            isReadableFile: readable
        )
    }

    func testBuildMatchesAndroidTextNameMimeAndImageFileRules() {
        let result = SharePayloadPolicy.build(
            text: "Review this",
            candidates: [
                candidate("image", name: "../photo.png", mime: "application/octet-stream", bytes: 12 * 1024 * 1024),
                candidate("file", name: "report.pdf", mime: "application/pdf", bytes: 2_048),
            ],
            requestID: "request-1"
        )

        XCTAssertEqual(result.payload.text, "Review this")
        XCTAssertEqual(result.payload.attachments.map(\.displayName), ["photo.png", "report.pdf"])
        XCTAssertEqual(result.payload.attachments.map(\.kind), [.image, .file])
        XCTAssertTrue(result.rejections.isEmpty)
    }

    func testRejectsUnreadableDuplicatesOversizeAndCountOverflowWithoutDroppingValidSiblings() {
        var candidates = [
            candidate("unsafe", name: "secret", readable: false),
            candidate("duplicate", name: "first.txt", mime: "text/plain"),
            candidate("duplicate", name: "second.txt", mime: "text/plain"),
            candidate("large", name: "large.pdf", mime: "application/pdf", bytes: SharePayloadPolicy.maxFileBytes + 1),
        ]
        candidates += (0..<(SharePayloadPolicy.maxAttachments + 2)).map {
            candidate("valid-\($0)", name: "valid-\($0).txt", mime: "text/plain")
        }

        let result = SharePayloadPolicy.build(text: nil, candidates: candidates)

        XCTAssertEqual(result.payload.attachments.count, SharePayloadPolicy.maxAttachments)
        XCTAssertEqual(Set(result.payload.attachments.map(\.id)).count, result.payload.attachments.count)
        XCTAssertGreaterThanOrEqual(result.rejections.count, 4)
    }

    func testBoundsTextMimeAndRejectsEmptyPayload() {
        let bounded = SharePayloadPolicy.build(
            text: String(repeating: "x", count: SharePayloadPolicy.maxTextCharacters + 10),
            candidates: [candidate("mime", name: "x.bin", mime: String(repeating: "m", count: 300))]
        )
        XCTAssertEqual(bounded.payload.text.count, SharePayloadPolicy.maxTextCharacters)
        XCTAssertEqual(bounded.payload.attachments.first?.mimeType?.count, SharePayloadPolicy.maxMIMETypeCharacters)

        let empty = SharePayloadPolicy.build(text: "   ", candidates: [])
        XCTAssertTrue(empty.payload.isEmpty)
        XCTAssertFalse(empty.rejections.isEmpty)
    }

    func testRejectsUnknownNegativeAttachmentSize() {
        let result = SharePayloadPolicy.build(
            text: nil,
            candidates: [candidate("unknown", name: "unknown.bin", bytes: -1)]
        )
        XCTAssertTrue(result.payload.attachments.isEmpty)
        XCTAssertTrue(result.rejections.contains { $0.contains("valid size") })
    }

    func testInboxRoundTripIsExplicitConsumeOnlyAndNeverCarriesSendIntent() throws {
        let suite = "MercuryShareTests.\(UUID().uuidString)"
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(suite, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = try ShareInboxStore(containerURL: root)
        let payload = SharePayloadPolicy.build(text: "draft only", candidates: []).payload

        try store.enqueue(payload)
        XCTAssertEqual(try store.peek().map(\.payload.text), ["draft only"])
        XCTAssertEqual(try store.consumeAll().map(\.payload.text), ["draft only"])
        XCTAssertTrue(try store.peek().isEmpty)
    }

    func testInboxRejectsTraversalPathsAtPersistenceBoundary() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = try ShareInboxStore(containerURL: root)
        let attachment = SharedAttachment(
            id: "bad",
            stagedRelativePath: "../escape",
            displayName: "escape.txt",
            mimeType: "text/plain",
            sizeBytes: 1,
            kind: .file
        )
        let payload = SharePayload(requestID: "bad", text: "", attachments: [attachment], rejections: [])
        XCTAssertThrowsError(try store.enqueue(payload))
    }

    func testInboxIgnoresEntryWhoseDecodedIDDoesNotMatchFilename() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = try ShareInboxStore(containerURL: root)
        let payload = SharePayload(requestID: "request", text: "draft", attachments: [], rejections: [])
        let entry = ShareInboxEntry(id: "../escape", payload: payload)
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        try encoder.encode(entry).write(to: store.inboxURL.appendingPathComponent("safe.json"))
        XCTAssertTrue(try store.peek().isEmpty)
        XCTAssertNil(try store.consume(id: "../escape"))
    }
}
