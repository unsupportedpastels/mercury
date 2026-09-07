package com.unsupportedpastels.mercury.core.voice

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

/** Mirrors apps/desktop/src/lib/speech-text.test.ts one-for-one. */
class SpeechTextPolicyTest {
    @Test
    fun summarizesFencedCodeBlocks() {
        assertEquals(
            "Here is code: code block omitted Done.",
            SpeechTextPolicy.sanitize("Here is code:\n```ts\nconst x = 1\n```\nDone."),
        )
    }

    @Test
    fun keepsProseAndInlineCodeReadable() {
        assertEquals(
            "Use git status after the change.",
            SpeechTextPolicy.sanitize("Use `git status` after the change."),
        )
    }

    @Test
    fun skipsTableDataPreservingSurroundingText() {
        val text = """
            Here is the quick takeaway: the totals remain unchanged.

            | Item | Value | Notes |
            | --- | ---: | --- |
            | Example A | 10 | first row |
            | Example B | 20 | second row |

            Full detail stays visible on screen.
        """.trimIndent()
        assertEquals(
            "Here is the quick takeaway: the totals remain unchanged. Full detail stays visible on screen.",
            SpeechTextPolicy.sanitize(text),
        )
    }

    @Test
    fun doesNotStripProseContainingAPipe() {
        val text = "Use the summary first | keep the table on screen when it matters."
        assertEquals(text, SpeechTextPolicy.sanitize(text))
    }

    @Test
    fun doesNotDuplicatePunctuationAcrossParagraphBreaks() {
        assertEquals(
            "First sentence. Second sentence.",
            SpeechTextPolicy.sanitize("First sentence.\n\nSecond sentence."),
        )
    }

    @Test
    fun doesNotDuplicatePunctuationAfterEmphasisQuoteOrParen() {
        assertEquals(
            "First sentence. Second sentence.",
            SpeechTextPolicy.sanitize("**First sentence.**\n\nSecond sentence."),
        )
        assertEquals(
            "“First sentence.” Second sentence.",
            SpeechTextPolicy.sanitize("“First sentence.”\n\nSecond sentence."),
        )
        assertEquals(
            "(First sentence.) Second sentence.",
            SpeechTextPolicy.sanitize("(First sentence.)\n\nSecond sentence."),
        )
    }

    @Test
    fun skipsTablesWithoutLeadingAndTrailingPipes() {
        val text = """
            Main takeaway: total is unchanged.

            Item | Value
            --- | ---:
            Example A | 10
            Example B | 20

            Done.
        """.trimIndent()
        assertEquals("Main takeaway: total is unchanged. Done.", SpeechTextPolicy.sanitize(text))
    }

    @Test
    fun skipsTablesNestedInsideBlockquotes() {
        val text = """
            Before the table.

            > | Item | Value |
            > | --- | ---: |
            > | Example A | 10 |
            > | Example B | 20 |

            After the table.
        """.trimIndent()
        assertEquals("Before the table. After the table.", SpeechTextPolicy.sanitize(text))
    }

    @Test
    fun allowsMarkerPaddingPlusThreeSpacesInBlockquotedTables() {
        val text = """
            Before the table.

            >    | Item | Value |
            >    | --- | ---: |
            >    | Example A | 10 |

            After the table.
        """.trimIndent()
        assertEquals("Before the table. After the table.", SpeechTextPolicy.sanitize(text))
    }

    @Test
    fun skipsExplicitSingleColumnTables() {
        val text = """
            Before the table.

            | Item |
            | --- |
            | Example A |

            After the table.
        """.trimIndent()
        assertEquals("Before the table. After the table.", SpeechTextPolicy.sanitize(text))
    }

    @Test
    fun preservesRowsOutsideATableBlockquote() {
        val text = """
            > | Item | Value |
            > | --- | ---: |
            > | Example A | 10 |
            Outside | prose
        """.trimIndent()
        assertEquals("Outside | prose", SpeechTextPolicy.sanitize(text))
    }

    @Test
    fun preservesMalformedTablesWithMismatchedColumnCounts() {
        val text = """
            Heading | Detail
            --- | --- | ---
            Keep this prose.
        """.trimIndent()
        assertTrue(SpeechTextPolicy.sanitize(text).contains("Heading | Detail"))
    }

    @Test
    fun skipsGfmBodyRowsWhoseCellCountsDifferFromHeader() {
        val text = """
            Before the table.

            | Item | Value |
            | --- | ---: |
            | Example A |
            | Example B | 20 | ignored |

            After the table.
        """.trimIndent()
        assertEquals("Before the table. After the table.", SpeechTextPolicy.sanitize(text))
    }

    @Test
    fun skipsTablesContainingEscapedPipes() {
        val text = """
            Before the table.

            | Item \| detail | Value |
            | --- | ---: |
            | Example A | 10 |

            After the table.
        """.trimIndent()
        assertEquals("Before the table. After the table.", SpeechTextPolicy.sanitize(text))
    }

    @Test
    fun preservesIndentedCodeThatResemblesATable() {
        val text = "    Item | Value\n    --- | ---\n    Example A | 10"
        assertTrue(SpeechTextPolicy.sanitize(text).contains("Item | Value"))
    }

    @Test
    fun stripsFencedCodeAndUrlAndEmoji() {
        assertEquals(
            "See link for details.",
            SpeechTextPolicy.sanitize("See https://example.com/x for details. 🎉"),
        )
    }
}
