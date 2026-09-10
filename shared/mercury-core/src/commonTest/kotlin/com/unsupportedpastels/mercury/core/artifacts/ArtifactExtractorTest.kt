package com.unsupportedpastels.mercury.core.artifacts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Merged from the Android ArtifactExtractorTest and the iOS
 * MediaDirectiveExtractorTests (the superset for extraction rules).
 */
class ArtifactExtractorTest {

    private fun extract(text: String, limits: ArtifactExtractionLimits = ArtifactExtractionLimits()) =
        ArtifactExtractor.extract(text, limits)

    // --- MEDIA directives -----------------------------------------------------

    @Test
    fun standaloneMediaDirectiveExtractsManagedPath() {
        val artifacts = extract("MEDIA:/workspace/out/plot.png")
        assertEquals(1, artifacts.size)
        assertEquals(ArtifactOrigin.ManagedPath, artifacts[0].origin)
        assertEquals(ArtifactType.Image, artifacts[0].type)
        assertEquals("/workspace/out/plot.png", artifacts[0].source)
        assertEquals("managed:/workspace/out/plot.png", artifacts[0].stableIdentity)
        assertEquals("plot.png", artifacts[0].displayName)
    }

    @Test
    fun quotedAndBacktickedMediaValuesAreAccepted() {
        assertEquals(1, extract("MEDIA:\"/a/b c.png\"").size)
        assertEquals(1, extract("`MEDIA:/a/b.png`").size)
        assertEquals(1, extract("MEDIA:'/a/b.wav'").size)
    }

    @Test
    fun malformedMediaValuesAreRejected() {
        assertTrue(extract("MEDIA:").isEmpty())
        assertTrue(extract("MEDIA: /a b.png").isEmpty())
        assertTrue(extract("MEDIA:\"/a/unterminated").isEmpty())
        assertTrue(extract("prose MEDIA:/a/b.png prose").isEmpty())
    }

    @Test
    fun managedPathTraversalAndBareRootAreRejected() {
        assertTrue(extract("MEDIA:/a/../b.png").isEmpty())
        assertTrue(extract("MEDIA:/a/").isEmpty())
        assertTrue(extract("MEDIA://host/x.png").isEmpty())
    }

    @Test
    fun managedPathIsCanonicalized() {
        val artifacts = extract("MEDIA:/a//./b.png")
        assertEquals("/a/b.png", artifacts.single().source)
    }

    // --- standalone HTTPS -----------------------------------------------------

    @Test
    fun standaloneHttpsUrlIsCanonicalized() {
        val artifacts = extract("HTTPS://Example.COM:443/Path/File.PDF?x=1")
        assertEquals(1, artifacts.size)
        assertEquals(ArtifactOrigin.RemoteUrl, artifacts[0].origin)
        assertEquals("https://example.com/Path/File.PDF?x=1", artifacts[0].source)
        assertEquals(ArtifactType.File, artifacts[0].type)
    }

    @Test
    fun hostileHttpsVariantsAreRejected() {
        for (input in listOf(
            "https://localhost/x.png",
            "https://svc.local/x.png",
            "https://10.0.0.1/x.png",
            "https://example.com:8443/x.png",
            "https://user@example.com/x.png",
            "https://example.com/x.png#frag",
            "https://example.com/%00.png",
            "https://exa mple.com/x.png",
            "http://example.com/x.png",
        )) {
            assertTrue(extract(input).isEmpty(), "should reject: $input")
        }
    }

    // --- markdown links -------------------------------------------------------

    @Test
    fun markdownLinksAndImageLinksExtract() {
        val text = "See ![chart](https://example.com/c.bin) and [audio: take one](/runs/take1.wav)."
        val artifacts = extract(text)
        assertEquals(2, artifacts.size)
        assertEquals(ArtifactType.Image, artifacts[0].type)
        assertEquals("https://example.com/c.bin", artifacts[0].source)
        assertEquals(ArtifactType.Audio, artifacts[1].type)
        assertEquals("take1.wav", artifacts[1].displayName)
    }

    @Test
    fun explicitLocalMarkdownImagesPreserveRangesRejectUnsafeSourcesAndDedupe() {
        val text = "Before ![one](/runs/one.png) middle ![two](/runs/two.jpg) " +
            "again ![duplicate](/runs/one.png) after"

        val references = ArtifactExtractor.explicitLocalMarkdownImages(text)

        assertEquals(listOf("/runs/one.png", "/runs/two.jpg", "/runs/one.png"), references.map { it.source })
        assertEquals(listOf(true, true, false), references.map { it.shouldRender })
        assertEquals(
            listOf("![one](/runs/one.png)", "![two](/runs/two.jpg)", "![duplicate](/runs/one.png)"),
            references.map { text.substring(it.startOffset, it.endOffsetExclusive) },
        )

        for (invalid in listOf(
            "![remote](https://example.com/image.png)",
            "![traversal](/runs/../secret.png)",
            "![double slash](/runs//image.png)",
            "![backslash](/runs\\image.png)",
            "![control](/runs/ima\u0000ge.png)",
        )) {
            assertTrue(ArtifactExtractor.explicitLocalMarkdownImages(invalid).isEmpty(), invalid)
        }
    }

    @Test
    fun explicitLocalMarkdownImagesIgnoreInlineAndFencedCode() {
        val text = """
            `![inline](/runs/inline.png)`
            ```markdown
            ![fenced](/runs/fenced.png)
            ```
            ![visible](/runs/visible.png)
        """.trimIndent()

        assertEquals(
            listOf("/runs/visible.png"),
            ArtifactExtractor.explicitLocalMarkdownImages(text).map { it.source },
        )
    }

    @Test
    fun explicitLocalMarkdownImagesIgnoreMultilineInlineCodeSpans() {
        val text = """
            Before ``code starts
            ![not visible](/runs/code.png)
            and ends`` after
            ![visible](/runs/visible.png)
        """.trimIndent()

        assertEquals(
            listOf("/runs/visible.png"),
            ArtifactExtractor.explicitLocalMarkdownImages(text).map { it.source },
        )
    }

    @Test
    fun escapedOpeningBracketNeverBecomesAnImage() {
        assertTrue(
            ArtifactExtractor.explicitLocalMarkdownImages("!\\[example](/tmp/synthetic.png)").isEmpty(),
        )
    }

    @Test
    fun orderedManagedImageSegmentsPreserveDocumentOrderAndRemoveDuplicateSyntax() {
        val text = "Before ![one](/runs/one.png) middle\nMEDIA:/runs/two.png\nafter ![again](/runs/one.png)."

        val segments = ArtifactExtractor.orderedManagedImageSegments(text, ManagedImageFormatPolicy.Android)

        assertEquals(
            listOf(
                ManagedImageContentSegmentKind.Text,
                ManagedImageContentSegmentKind.Image,
                ManagedImageContentSegmentKind.Text,
                ManagedImageContentSegmentKind.Image,
                ManagedImageContentSegmentKind.Text,
            ),
            segments.map { it.kind },
        )
        assertEquals(listOf("/runs/one.png", "/runs/two.png"), segments.mapNotNull { it.source })
        assertEquals("Before  middle\n\nafter .", segments.mapNotNull { it.text }.joinToString(""))
    }

    @Test
    fun orderedManagedImageSegmentsPreserveTextBeyondExtractionBudget() {
        val text = "![one](/a/one.png)" + "x".repeat(40)
        val segments = ArtifactExtractor.orderedManagedImageSegments(
            text,
            ManagedImageFormatPolicy.Android,
            ArtifactExtractionLimits(maxTranscriptChars = 24),
        )

        assertEquals("x".repeat(40), segments.mapNotNull { it.text }.joinToString(""))
        assertEquals(listOf("/a/one.png"), segments.mapNotNull { it.source })
    }

    @Test
    fun sharedFenceSegmentsSupportTildesAndVariableLengthClosers() {
        val segments = MarkdownPresentationPolicy.fencedSegments(
            "before\n~~~~swift\nlet value = 1\n~~~\nstill code\n~~~~~\nafter",
        )

        assertEquals(
            listOf(MarkdownFenceSegmentKind.Text, MarkdownFenceSegmentKind.Code, MarkdownFenceSegmentKind.Text),
            segments.map { it.kind },
        )
        assertEquals("swift", segments[1].language)
        assertEquals("let value = 1\n~~~\nstill code", segments[1].text)
    }

    @Test
    fun explicitLocalMarkdownImagesRespectMarkdownCodeEscapesAndLineBounds() {
        val text = """
            ``![double tick](/runs/double.png)``
            ~~~~markdown
            ![tilde fenced](/runs/tilde.png)
            ~~~
            ![still fenced](/runs/still-fenced.png)
            ~~~~
            \![escaped](/runs/escaped.png)
            ![line break](
            /runs/cross-line.png)
            ![](/runs/empty-alt.png)
            ![visible](/runs/visible.png)
        """.trimIndent()

        assertEquals(
            listOf("/runs/empty-alt.png", "/runs/visible.png"),
            ArtifactExtractor.explicitLocalMarkdownImages(text).map { it.source },
        )
    }

    @Test
    fun managedImageSelectionExcludesFencedMediaAndPreservesPlatformFormats() {
        val text = """
            ~~~text
            MEDIA:/runs/fenced.heic
            ~~~
            MEDIA:/runs/photo.heic
            MEDIA:/runs/scan.tif
            ![](/runs/page.tiff)
            MEDIA:/runs/portable.png
            /runs/bare.jpg
            [ordinary](/runs/link.jpg)
            ![remote](https://example.com/remote.jpg)
        """.trimIndent()

        assertEquals(
            listOf("/runs/portable.png"),
            ArtifactExtractor.managedImageArtifacts(text, ManagedImageFormatPolicy.Android).map { it.source },
        )
        assertEquals(
            listOf("/runs/photo.heic", "/runs/scan.tif", "/runs/page.tiff", "/runs/portable.png"),
            ArtifactExtractor.managedImageArtifacts(text, ManagedImageFormatPolicy.Ios).map { it.source },
        )
    }

    @Test
    fun angleBracketDestinationsAllowSpaces() {
        val artifacts = extract("[report](</files/quarterly report.pdf>)")
        assertEquals("/files/quarterly report.pdf", artifacts.single().source)
    }

    @Test
    fun labelFallsBackToDisplayNameForExtensionlessSource() {
        val artifacts = extract("[image: Sales Chart](https://example.com/render)")
        assertEquals(ArtifactType.Image, artifacts.single().type)
        assertEquals("render", artifacts.single().displayName)
    }

    @Test
    fun percentEscapedNamesDecodeForDisplay() {
        val artifacts = extract("MEDIA:/out/final%20report.pdf")
        assertEquals("final report.pdf", artifacts.single().displayName)
        assertEquals("/out/final%20report.pdf", artifacts.single().source)
        // Faithful to the original URI-then-URLDecoder double decode: %2B
        // becomes '+', which the second (form) decode folds to a space.
        val upper = extract("MEDIA:/out/final%2Breport.pdf")
        assertEquals("final report.pdf", upper.single().displayName)
    }

    // --- dedup, ordering, budgets ---------------------------------------------

    @Test
    fun duplicateIdentitiesCollapseAcrossForms() {
        val text = """
            MEDIA:/a/b.png
            [same](/a/b.png)
        """.trimIndent()
        assertEquals(1, extract(text).size)
    }

    @Test
    fun candidatesEmitInDocumentOrder() {
        val text = "[one](/z/one.pdf)\nMEDIA:/a/two.png\nhttps://example.com/three.pdf"
        assertEquals(
            listOf("one.pdf", "two.png", "three.pdf"),
            extract(text).map { it.displayName },
        )
    }

    @Test
    fun maxItemsBoundsAcrossMessages() {
        val texts = (1..10).map { "MEDIA:/a/f$it.png" }
        val artifacts = ArtifactExtractor.extract(texts, ArtifactExtractionLimits(maxItems = 3))
        assertEquals(3, artifacts.size)
    }

    @Test
    fun transcriptBudgetTruncatesInUtf16Units() {
        val first = "x".repeat(30)
        val second = "MEDIA:/a/b.png"
        val artifacts = ArtifactExtractor.extract(
            listOf(first, second),
            ArtifactExtractionLimits(maxTranscriptChars = 32),
        )
        // Only 2 chars of budget remain: the directive is truncated away.
        assertTrue(artifacts.isEmpty())
    }

    @Test
    fun oversizedSourcesAreRejected() {
        val long = "/a/" + "b".repeat(5000) + ".png"
        assertTrue(extract("MEDIA:$long").isEmpty())
    }

    @Test
    fun displayNameSanitizesHostileCharacters() {
        val artifacts = extract("[file: bad\u0007name](/a/x)")
        assertEquals("x", artifacts.single().displayName)
        val hinted = extract("[file: we\u0007ird   name](https://example.com/)")
        assertEquals("we_ird name", hinted.single().displayName)
    }

    @Test
    fun limitsRejectNonPositiveValues() {
        assertTrue(runCatching { ArtifactExtractionLimits(maxItems = 0) }.isFailure)
        assertTrue(runCatching { ArtifactExtractionLimits(maxTranscriptChars = -1) }.isFailure)
    }
}
