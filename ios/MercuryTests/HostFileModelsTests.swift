import Foundation
import XCTest
@testable import Mercury

final class HostFileModelsTests: XCTestCase {
    func testReferenceFormatterUsesOfficialKindsAndSafeQuotePrecedence() throws {
        XCTAssertEqual(
            try formatHostFileReference(HostFileEntry(name: "report", path: "/srv/project/my report;v1.txt", isDirectory: false)),
            "@file:`/srv/project/my report;v1.txt`"
        )
        XCTAssertEqual(
            try formatHostFileReference(HostFileEntry(name: "project", path: "/srv/project", isDirectory: true)),
            "@folder:/srv/project"
        )
        XCTAssertEqual(
            try formatHostFileReference(HostFileEntry(name: "backtick", path: "/srv/has` backtick.txt", isDirectory: false)),
            "@file:\"/srv/has` backtick.txt\""
        )
        XCTAssertEqual(
            try formatHostFileReference(HostFileEntry(name: "quotes", path: "/srv/has`\" quote.txt", isDirectory: false)),
            "@file:'/srv/has`\" quote.txt'"
        )
        XCTAssertThrowsError(
            try formatHostFileReference(HostFileEntry(name: "unsafe", path: "/srv/a`b\"c'd", isDirectory: false))
        )
    }

    func testCanonicalPathValidationAcceptsAbsoluteUnixAndWindowsAndRejectsTraversal() {
        XCTAssertEqual(validCanonicalHostFilePath(" /srv/project/file.txt "), "/srv/project/file.txt")
        XCTAssertEqual(validCanonicalHostFilePath(#"C:\work\file.txt"#), #"C:\work\file.txt"#)
        XCTAssertEqual(validCanonicalHostFilePath("D:/work/file.txt"), "D:/work/file.txt")
        XCTAssertNil(validCanonicalHostFilePath("relative/file.txt"))
        XCTAssertNil(validCanonicalHostFilePath("/srv/project/../secret"))
        XCTAssertNil(validCanonicalHostFilePath(#"C:\work\.\file.txt"#))
        XCTAssertNil(validCanonicalHostFilePath("/srv/project\u{0}file"))
        XCTAssertNil(validCanonicalHostFilePath("/" + String(repeating: "a", count: maxHostFilePathLength)))
    }

    func testNameAndMIMEValidationEnforceBoundsAndSyntax() {
        XCTAssertEqual(validHostFileName("notes.txt"), "notes.txt")
        XCTAssertNil(validHostFileName("../notes.txt"))
        XCTAssertNil(validHostFileName("bad/name"))
        XCTAssertNil(validHostFileName(String(repeating: "a", count: maxHostFileNameLength + 1)))
        XCTAssertEqual(validHostFileMIMEType(" Text/Plain "), "text/plain")
        XCTAssertNil(validHostFileMIMEType("text/plain; charset=utf-8"))
        XCTAssertNil(validHostFileMIMEType("not-a-mime"))
    }
}
