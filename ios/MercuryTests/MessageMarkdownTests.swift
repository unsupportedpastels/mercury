import XCTest
@testable import Mercury

final class MessageMarkdownTests: XCTestCase {
    func testParsesHeadingQuoteAndListsAsStructuredBlocks() {
        let blocks = parseMessageMarkdown("""
        ## Example Markdown

        > **Tip:** Markdown is useful.

        - Clear headings
        1. First step
        """)

        XCTAssertEqual(blocks, [
            .heading(level: 2, text: "Example Markdown"),
            .quote("**Tip:** Markdown is useful."),
            .bullet(indent: 0, text: "Clear headings"),
            .numbered(indent: 0, marker: "1.", text: "First step"),
        ])
    }

    func testParsesPipeTableWithoutExposingDelimiterRow() {
        let blocks = parseMessageMarkdown("""
        | Element | Markdown syntax | Rendered result |
        |:---|:---:|---:|
        | Bold | **Important** | Important |
        | Link | [Nous](https://nousresearch.com) | Nous |
        """)

        XCTAssertEqual(blocks, [
            .table(
                header: ["Element", "Markdown syntax", "Rendered result"],
                alignments: [.leading, .center, .trailing],
                rows: [
                    ["Bold", "**Important**", "Important"],
                    ["Link", "[Nous](https://nousresearch.com)", "Nous"],
                ]
            ),
        ])
    }

    func testTableParserPreservesEscapedPipesAndInlineCodePipes() {
        let blocks = parseMessageMarkdown("""
        | Value | Meaning |
        |---|---|
        | a\\|b | `x|y` |
        """)

        guard case .table(_, _, let rows) = try? XCTUnwrap(blocks.first) else {
            return XCTFail("Expected a table")
        }
        XCTAssertEqual(rows, [["a|b", "`x|y`"]])
    }

    func testFencedCodeRemainsASeparateBlockWithLanguage() {
        let blocks = parseMessageMarkdown("""
        Before

        ```python
        def greet(name):
            return name
        ```
        """)

        XCTAssertEqual(blocks, [
            .paragraph("Before"),
            .code(language: "python", code: "def greet(name):\n    return name"),
        ])
    }
}
