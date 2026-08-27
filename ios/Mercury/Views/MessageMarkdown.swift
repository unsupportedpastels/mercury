import SwiftUI

enum MessageMarkdownTableAlignment: Equatable {
    case leading
    case center
    case trailing

    var viewAlignment: Alignment {
        switch self {
        case .leading: return .leading
        case .center: return .center
        case .trailing: return .trailing
        }
    }
}

enum MessageMarkdownBlock: Equatable {
    case paragraph(String)
    case heading(level: Int, text: String)
    case bullet(indent: Int, text: String)
    case numbered(indent: Int, marker: String, text: String)
    case quote(String)
    case table(
        header: [String],
        alignments: [MessageMarkdownTableAlignment],
        rows: [[String]]
    )
    case code(language: String?, code: String)
}

private func splitMarkdownTableRow(_ line: String) -> [String]? {
    let trimmed = line.trimmingCharacters(in: .whitespaces)
    guard trimmed.contains("|") else { return nil }
    let characters = Array(trimmed)
    var index = characters.first == "|" ? 1 : 0
    var cells: [String] = []
    var cell = ""
    var inCode = false
    var foundSeparator = false

    while index < characters.count {
        let character = characters[index]
        if character == "\\", index + 1 < characters.count, characters[index + 1] == "|" {
            cell.append("|")
            index += 2
            continue
        }
        if character == "`" {
            inCode.toggle()
            cell.append(character)
        } else if character == "|", !inCode {
            cells.append(cell.trimmingCharacters(in: .whitespaces))
            cell = ""
            foundSeparator = true
        } else {
            cell.append(character)
        }
        index += 1
    }
    if !cell.isEmpty || characters.last != "|" {
        cells.append(cell.trimmingCharacters(in: .whitespaces))
    }
    return foundSeparator && cells.count >= 2 ? cells : nil
}

private func tableAlignments(_ cells: [String]) -> [MessageMarkdownTableAlignment]? {
    guard cells.count >= 2 else { return nil }
    var result: [MessageMarkdownTableAlignment] = []
    for cell in cells {
        let value = cell.trimmingCharacters(in: .whitespaces)
        let startsColon = value.hasPrefix(":")
        let endsColon = value.hasSuffix(":")
        let dashes = value.trimmingCharacters(in: CharacterSet(charactersIn: ":"))
        guard dashes.count >= 3, dashes.allSatisfy({ $0 == "-" }) else { return nil }
        if startsColon && endsColon {
            result.append(.center)
        } else if endsColon {
            result.append(.trailing)
        } else {
            result.append(.leading)
        }
    }
    return result
}

private func listIndentAndContent(_ line: String) -> (indent: Int, content: String) {
    let leading = line.prefix(while: { $0 == " " || $0 == "\t" }).count
    return (min(leading / 2, 4), String(line.dropFirst(leading)))
}

private func orderedListParts(_ content: String) -> (marker: String, text: String)? {
    let characters = Array(content)
    var index = 0
    while index < characters.count, characters[index].isNumber { index += 1 }
    guard index > 0, index + 1 < characters.count,
          characters[index] == "." || characters[index] == ")",
          characters[index + 1].isWhitespace else { return nil }
    return (String(characters[0...index]), String(characters.dropFirst(index + 2)))
}

/// Parses the block structure that Foundation's inline markdown mode omits.
/// Inline emphasis and links remain Foundation-owned at render time.
func parseMessageMarkdown(_ source: String) -> [MessageMarkdownBlock] {
    guard !source.isEmpty else { return [] }
    let lines = source.replacingOccurrences(of: "\r\n", with: "\n")
        .replacingOccurrences(of: "\r", with: "\n")
        .split(separator: "\n", omittingEmptySubsequences: false)
        .map(String.init)
    var blocks: [MessageMarkdownBlock] = []
    var paragraph: [String] = []

    func flushParagraph() {
        guard !paragraph.isEmpty else { return }
        let value = paragraph.joined(separator: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if !value.isEmpty { blocks.append(.paragraph(value)) }
        paragraph.removeAll(keepingCapacity: true)
    }

    var index = 0
    while index < lines.count {
        let line = lines[index]
        let trimmed = line.trimmingCharacters(in: .whitespaces)

        if trimmed.hasPrefix("```") {
            flushParagraph()
            let language = String(trimmed.dropFirst(3)).trimmingCharacters(in: .whitespaces)
            var codeLines: [String] = []
            index += 1
            while index < lines.count,
                  !lines[index].trimmingCharacters(in: .whitespaces).hasPrefix("```") {
                codeLines.append(lines[index])
                index += 1
            }
            blocks.append(.code(
                language: language.isEmpty ? nil : String(language.prefix(32)),
                code: codeLines.joined(separator: "\n")
            ))
            if index < lines.count { index += 1 }
            continue
        }

        if line.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            flushParagraph()
            index += 1
            continue
        }

        if let header = splitMarkdownTableRow(line),
           index + 1 < lines.count,
           let delimiter = splitMarkdownTableRow(lines[index + 1]),
           let alignments = tableAlignments(delimiter),
           header.count == alignments.count {
            flushParagraph()
            var rows: [[String]] = []
            index += 2
            while index < lines.count, !lines[index].trimmingCharacters(in: .whitespaces).isEmpty,
                  let row = splitMarkdownTableRow(lines[index]) {
                rows.append((0..<header.count).map { $0 < row.count ? row[$0] : "" })
                index += 1
            }
            blocks.append(.table(header: header, alignments: alignments, rows: rows))
            continue
        }

        let hashes = trimmed.prefix(while: { $0 == "#" }).count
        if hashes > 0, hashes <= 6, trimmed.dropFirst(hashes).hasPrefix(" ") {
            flushParagraph()
            blocks.append(.heading(
                level: hashes,
                text: String(trimmed.dropFirst(hashes + 1)).trimmingCharacters(in: .whitespaces)
            ))
            index += 1
            continue
        }

        let list = listIndentAndContent(line)
        if ["- ", "+ ", "* "].contains(where: { list.content.hasPrefix($0) }) {
            flushParagraph()
            blocks.append(.bullet(indent: list.indent, text: String(list.content.dropFirst(2))))
            index += 1
            continue
        }
        if let ordered = orderedListParts(list.content) {
            flushParagraph()
            blocks.append(.numbered(indent: list.indent, marker: ordered.marker, text: ordered.text))
            index += 1
            continue
        }

        if trimmed.hasPrefix(">") {
            flushParagraph()
            blocks.append(.quote(
                String(trimmed.dropFirst()).trimmingCharacters(in: .whitespaces)
            ))
            index += 1
            continue
        }

        paragraph.append(line)
        index += 1
    }
    flushParagraph()
    return blocks
}

/// Markdown renderer for chat message bodies.
///
/// Ports the rendering DECISIONS of Android's `ui/MessageMarkdown.kt`:
/// - Fenced code blocks are extracted first and rendered as monospaced text
///   on a raised surface — never parsed as inline markdown.
/// - Inline code is monospaced; links are tinted and tappable.
/// - Raw HTML and remote images are never rendered (AttributedString's
///   markdown parser already ignores both; this view does not add them back).
/// - Headings, lists, quotes, and pipe tables are parsed as block structure;
///   Foundation still owns inline emphasis, code, and link semantics.
struct MessageMarkdownView: View {
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(parseMessageMarkdown(text).enumerated()), id: \.offset) { _, block in
                blockView(block)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .textSelection(.enabled)
        .tint(Color.accentPrimary)
    }

    @ViewBuilder
    private func blockView(_ block: MessageMarkdownBlock) -> some View {
        switch block {
        case .paragraph(let content):
            inlineText(content)
                .font(.body)
                .foregroundStyle(Color.primary)
                .fixedSize(horizontal: false, vertical: true)
        case .heading(let level, let content):
            inlineText(content)
                .font(headingFont(level))
                .foregroundStyle(Color.primary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, level <= 2 ? 4 : 1)
        case .bullet(let indent, let content):
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text("•")
                inlineText(content).frame(maxWidth: .infinity, alignment: .leading)
            }
            .font(.body)
            .padding(.leading, CGFloat(indent) * 16)
        case .numbered(let indent, let marker, let content):
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(marker).monospacedDigit()
                inlineText(content).frame(maxWidth: .infinity, alignment: .leading)
            }
            .font(.body)
            .padding(.leading, CGFloat(indent) * 16)
        case .quote(let content):
            HStack(alignment: .top, spacing: 10) {
                RoundedRectangle(cornerRadius: 2)
                    .fill(Color.accentPrimary)
                    .frame(width: 3)
                inlineText(content)
                    .font(.body)
                    .foregroundStyle(Color.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.vertical, 6)
            .padding(.horizontal, 10)
            .background(Color.surfaceLow)
            .clipShape(RoundedRectangle(cornerRadius: 8))
        case .table(let header, let alignments, let rows):
            tableView(header: header, alignments: alignments, rows: rows)
        case .code(let language, let code):
            codeBlock(code, language: language)
        }
    }

    private func inlineText(_ content: String) -> Text {
        if let attributed = try? AttributedString(
            markdown: content,
            options: AttributedString.MarkdownParsingOptions(
                interpretedSyntax: .inlineOnlyPreservingWhitespace
            )
        ) {
            return Text(attributed)
        }
        return Text(content)
    }

    private func headingFont(_ level: Int) -> Font {
        switch level {
        case 1: return .title2.bold()
        case 2: return .title3.bold()
        case 3: return .headline
        default: return .subheadline.bold()
        }
    }

    private func tableView(
        header: [String],
        alignments: [MessageMarkdownTableAlignment],
        rows: [[String]]
    ) -> some View {
        ScrollView(.horizontal, showsIndicators: true) {
            Grid(horizontalSpacing: 1, verticalSpacing: 1) {
                GridRow {
                    ForEach(header.indices, id: \.self) { column in
                        tableCell(
                            header[column],
                            alignment: alignments[column],
                            isHeader: true
                        )
                    }
                }
                ForEach(rows.indices, id: \.self) { row in
                    GridRow {
                        ForEach(header.indices, id: \.self) { column in
                            tableCell(
                                column < rows[row].count ? rows[row][column] : "",
                                alignment: alignments[column],
                                isHeader: false
                            )
                        }
                    }
                }
            }
            .background(Color.separatorSubtle)
        }
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private func tableCell(
        _ content: String,
        alignment: MessageMarkdownTableAlignment,
        isHeader: Bool
    ) -> some View {
        inlineText(content)
            .font(isHeader ? .caption.bold() : .caption)
            .foregroundStyle(Color.primary)
            .frame(width: 168, alignment: alignment.viewAlignment)
            .frame(minHeight: 38, alignment: alignment.viewAlignment)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(isHeader ? Color.surfaceHigh : Color.surfaceLow)
    }

    // MARK: - Code blocks

    private func codeBlock(_ code: String, language: String?) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            if let language {
                Text(language)
                    .font(.caption2.monospaced())
                    .foregroundStyle(Color.secondary)
                    .padding(.horizontal, 10)
                    .padding(.top, 8)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                Text(code.isEmpty ? " " : code)
                    .font(.callout.monospaced())
                    .foregroundStyle(Color.primary)
                    .padding(10)
            }
        }
        .background(Color.surfaceLow)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

#if DEBUG
#Preview("Markdown") {
    ScrollView {
        MessageMarkdownView(
            text: """
            **Bold** and *italic* plus `inline code` here.

            - First item
            - Second item with `code`

            > A short quote

            ```swift
            let greeting = "hello"
            print(greeting)
            ```

            Visit [Hermes](https://hermes.example.com) for more.
            """
        )
        .padding()
    }
    .amoledScreen()
}
#endif
