package com.unsupportedpastels.hermesandroid.ui

import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageMarkdownTest {
    @Test
    fun stablePrefixEndsAtLastBlankLineOutsideCodeFence() {
        val text = "First paragraph.\n\nSecond paragraph still stream"
        assertEquals("First paragraph.\n\n".length, stableMarkdownPrefixLength(text))
    }

    @Test
    fun stablePrefixIsZeroInsideUnclosedCodeFence() {
        val text = "```python\nprint('partial')\n\n**unclosed"
        assertEquals(0, stableMarkdownPrefixLength(text))
    }

    @Test
    fun stablePrefixIgnoresBlankLinesInsideFenceButAdvancesAfterItCloses() {
        val text = "Intro.\n\n```sh\nls\n\npwd\n```\n\ntail text"
        assertEquals(text.length - "tail text".length, stableMarkdownPrefixLength(text))
    }

    @Test
    fun stablePrefixIsZeroWithoutAnyBlankLine() {
        assertEquals(0, stableMarkdownPrefixLength("single still-streaming paragraph"))
    }

    @Test
    fun parsesHttpsMediaDirectiveAsImageBlockInsteadOfRawText() {
        val url = "https://cdn.example/generated.png"

        val blocks = parseMessageMarkdown("Here it is:\n\nMEDIA:$url\n\nDone")

        val image = blocks.filterIsInstance<MarkdownImageBlock>().single()
        assertEquals(url, image.url)
        assertFalse(
            blocks.filterIsInstance<MarkdownTextBlock>()
                .any { it.plainText.contains("MEDIA:") },
        )
    }

    @Test
    fun parsesGatewayLocalMediaDirectiveAsImageBlockInsteadOfRawPath() {
        val path = "/workspace/project/design/generated-mockup.jpg"

        val blocks = parseMessageMarkdown("Result:\n\nMEDIA:$path\n\nDone")

        val image = blocks.filterIsInstance<MarkdownImageBlock>().single()
        assertEquals(path, image.url)
        assertFalse(
            blocks.filterIsInstance<MarkdownTextBlock>()
                .any { it.plainText.contains("MEDIA:") },
        )
    }

    @Test
    fun compactsEmbeddedImagePayloadAndHidesServerAttachmentPath() {
        val source = buildString {
            appendLine("before")
            appendLine("[Image attached at: /private/server/upload.jpg]")
            append("data:image/jpeg;base64,")
            append("A".repeat(2_000))
            appendLine()
            append("after")
        }

        val compacted = compactEmbeddedPayloads(source)

        assertEquals(
            "before\nAttached image · embedded data hidden\nafter",
            compacted,
        )
        assertFalse(compacted.contains("/private/server"))
        assertFalse(compacted.contains("base64"))
        assertFalse(compacted.contains("A".repeat(100)))
    }

    @Test
    fun parsesGfmTableIntoStructuredCellsInsteadOfLiteralPipes() {
        val block = parseMessageMarkdown(
            """
            | Category | Preferred source |
            |---|---|
            | Body Battery, stress, Garmin recovery | CIRQA |
            | Golf UX and round activity | Apple Watch + 18Birdies |
            | Apple activity and workout details | HealthKit |
            | Sleep timing/stages | Compare Apple Watch and CIRQA |
            | Unified coaching/history | Foundry |
            """.trimIndent(),
        ).single() as MarkdownTableBlock

        assertEquals(listOf("Category", "Preferred source"), block.header.map { it.plainText })
        assertEquals(5, block.rows.size)
        assertEquals(
            listOf("Golf UX and round activity", "Apple Watch + 18Birdies"),
            block.rows[1].map { it.plainText },
        )
    }

    @Test
    fun tableCellsPreserveInlinePipesFormattingAndAlignment() {
        val block = parseMessageMarkdown(
            """
            | Name | Expression | Status |
            |:---|:---:|---:|
            | A \| B | `left|right` | **Done** |
            """.trimIndent(),
        ).single() as MarkdownTableBlock

        assertEquals(
            listOf(
                MarkdownTableAlignment.Start,
                MarkdownTableAlignment.Center,
                MarkdownTableAlignment.End,
            ),
            block.alignments,
        )
        assertEquals("A | B", block.rows.single()[0].plainText)
        assertTrue(block.rows.single()[1].inlines.single().code)
        assertEquals("left|right", block.rows.single()[1].plainText)
        assertTrue(block.rows.single()[2].inlines.single().bold)
    }

    @Test
    fun malformedTableDelimiterRemainsOrdinaryText() {
        val block = parseMessageMarkdown("| Header | Value |\n|--|---|\n| A | B |")
            .single() as MarkdownTextBlock

        assertTrue(block.plainText.contains("|--|---|"))
    }

    @Test
    fun parsesFiftyThousandCharacterPlainMessageWithinInteractiveBudget() {
        val source = "plain response ".repeat(4_000).take(50_000)
        lateinit var block: MarkdownTextBlock

        // Exclude one-time class loading and JIT compilation from the parser budget.
        // The fastest repeated sample still catches sustained parser regressions while
        // ignoring unrelated scheduling pauses on shared CI runners.
        parseMessageMarkdown(source)
        val elapsedSamples = List(5) {
            measureTimeMillis {
                block = parseMessageMarkdown(source).single() as MarkdownTextBlock
            }
        }

        assertEquals(source, block.plainText)
        assertTrue(
            "Best parse took ${elapsedSamples.min()}ms from $elapsedSamples; " +
                "large transcript messages must not block UI rendering",
            elapsedSamples.min() < 100,
        )
    }

    @Test
    fun parsesListsInlineFormattingAndFencedCodeWithoutLiteralMarkers() {
        val blocks = parseMessageMarkdown(
            """
            Summary

            - **Container:** removed; no `service` remains.
            - **Image:** still present:
            ```text
            example/image:latest
            Size: 800 MB
            ```
            """.trimIndent(),
        )

        assertEquals(4, blocks.size)
        assertEquals("Summary", (blocks[0] as MarkdownTextBlock).plainText)

        val firstBullet = blocks[1] as MarkdownTextBlock
        assertEquals(MarkdownTextKind.Bullet, firstBullet.kind)
        assertEquals("•", firstBullet.prefix)
        assertEquals("Container: removed; no service remains.", firstBullet.plainText)
        assertTrue(firstBullet.inlines.single { it.text == "Container:" }.bold)
        assertTrue(firstBullet.inlines.single { it.text == "service" }.code)

        val secondBullet = blocks[2] as MarkdownTextBlock
        assertEquals("Image: still present:", secondBullet.plainText)
        assertTrue(secondBullet.inlines.single { it.text == "Image:" }.bold)

        val code = blocks[3] as MarkdownCodeBlock
        assertEquals("text", code.language)
        assertEquals("example/image:latest\nSize: 800 MB", code.code)
        assertFalse(code.code.contains("```"))
    }

    @Test
    fun parsesHeadingsQuotesNumberedItemsLinksAndTextStyles() {
        val blocks = parseMessageMarkdown(
            """
            ## Result
            > Read the warning first.
            1. Open **Settings**.
            2. Select *Network*, visit [documentation](https://example.invalid), and ignore ~~old~~ advice.
            """.trimIndent(),
        )

        val heading = blocks[0] as MarkdownTextBlock
        assertEquals(MarkdownTextKind.Heading, heading.kind)
        assertEquals(2, heading.headingLevel)
        assertEquals("Result", heading.plainText)

        val quote = blocks[1] as MarkdownTextBlock
        assertEquals(MarkdownTextKind.Quote, quote.kind)
        assertEquals("Read the warning first.", quote.plainText)

        val firstItem = blocks[2] as MarkdownTextBlock
        assertEquals(MarkdownTextKind.Numbered, firstItem.kind)
        assertEquals("1.", firstItem.prefix)
        assertTrue(firstItem.inlines.single { it.text == "Settings" }.bold)

        val secondItem = blocks[3] as MarkdownTextBlock
        assertTrue(secondItem.inlines.single { it.text == "Network" }.italic)
        assertEquals("https://example.invalid", secondItem.inlines.single { it.text == "documentation" }.link)
        assertTrue(secondItem.inlines.single { it.text == "old" }.strikethrough)
    }

    @Test
    fun trimsUnmatchedPairedDelimitersFromBareUrls() {
        val block = parseMessageMarkdown(
            "See (https://example.com/docs), \"https://example.com/quoted\", " +
                "'https://example.com/single-quoted', " +
                "\"(https://example.com/nested)\", " +
                "[https://example.com/square], {https://example.com/curly}, " +
                "<https://example.com/angle>, and " +
                "https://en.wikipedia.org/wiki/Function_(mathematics), plus " +
                "https://example.com/wiki/Readers'.",
        ).single() as MarkdownTextBlock

        assertEquals(
            listOf(
                "https://example.com/docs",
                "https://example.com/quoted",
                "https://example.com/single-quoted",
                "https://example.com/nested",
                "https://example.com/square",
                "https://example.com/curly",
                "https://example.com/angle",
                "https://en.wikipedia.org/wiki/Function_(mathematics)",
                "https://example.com/wiki/Readers'",
            ),
            block.inlines.mapNotNull { it.link },
        )
    }

    @Test
    fun preservesUnmatchedInlineMarkersAndTreatsStreamingFenceAsCode() {
        val unmatched = parseMessageMarkdown("Keep **unfinished and `partial")
            .single() as MarkdownTextBlock
        assertEquals("Keep **unfinished and `partial", unmatched.plainText)

        val streaming = parseMessageMarkdown("```kotlin\nval answer = 42")
            .single() as MarkdownCodeBlock
        assertEquals("kotlin", streaming.language)
        assertEquals("val answer = 42", streaming.code)
    }

    @Test
    fun normalizesBlankParagraphsAndEmptyInput() {
        assertTrue(parseMessageMarkdown("").isEmpty())
        val blocks = parseMessageMarkdown("First\nline\n\n\nSecond")
        assertEquals(2, blocks.size)
        assertEquals("First\nline", (blocks[0] as MarkdownTextBlock).plainText)
        assertEquals("Second", (blocks[1] as MarkdownTextBlock).plainText)
        assertNull((blocks[1] as MarkdownTextBlock).prefix)
    }
}
