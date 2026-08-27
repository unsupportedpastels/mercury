package com.unsupportedpastels.hermesandroid.artifacts

import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactExtractorTest {
    @Test
    fun extractsStandaloneMediaDirectiveAsManagedImage() {
        val artifacts = ArtifactExtractor.extract(
            listOf(
                ChatMessage(
                    role = ChatMessageRole.Assistant,
                    text = "Here is the result.\n  MEDIA: '/workspace/project/generated mockup.png'  \n",
                ),
            ),
        )

        assertEquals(1, artifacts.size)
        assertEquals(ArtifactType.Image, artifacts.single().type)
        assertEquals(ArtifactOrigin.ManagedPath, artifacts.single().origin)
        assertEquals("/workspace/project/generated mockup.png", artifacts.single().source)
        assertEquals("generated mockup.png", artifacts.single().displayName)
        assertTrue(artifacts.single().stableIdentity.startsWith("managed:"))
    }

    @Test
    fun acceptsFirstPartyQuoteAndBacktickFormsButOnlyOnStandaloneLines() {
        val artifacts = ArtifactExtractor.extract(
            """
            MEDIA:/tmp/one.mp3
            `MEDIA:/tmp/two.wav`
            'MEDIA:/tmp/three.ogg'
            prose MEDIA:/tmp/not-an-artifact.png here
            MEDIA:/tmp/malformed path.png
            MEDIA:"/tmp/unclosed.png
            """.trimIndent(),
        )

        assertEquals(listOf("one.mp3", "two.wav", "three.ogg"), artifacts.map { it.displayName })
        assertTrue(artifacts.all { it.type == ArtifactType.Audio })
    }

    @Test
    fun extractsExplicitHttpsAndMarkdownImageAudioAndFileLinks() {
        val artifacts = ArtifactExtractor.extract(
            """
            ![generated image](https://cdn.example/assets/result.PNG)
            [Audio: voice recording](https://cdn.example/audio/voice.mp3?download=1)
            [report.pdf](https://files.example/download/report.pdf)
            https://cdn.example/assets/standalone.webp
            """.trimIndent(),
        )

        assertEquals(4, artifacts.size)
        assertEquals(listOf(ArtifactType.Image, ArtifactType.Audio, ArtifactType.File, ArtifactType.Image), artifacts.map { it.type })
        assertEquals(ArtifactOrigin.RemoteUrl, artifacts[0].origin)
        assertEquals("result.PNG", artifacts[0].displayName)
        assertFalse(artifacts.any { it.source.contains("#") })
    }

    @Test
    fun rejectsUnsafeUrlsMalformedSourcesAndArbitraryProse() {
        val credentialedUrl = "https://user" + ":pass@example.com/secret.png"
        val artifacts = ArtifactExtractor.extract(
            """
            [userinfo]($credentialedUrl)
            [fragment](https://example.com/image.png#fragment)
            [http](http://example.com/image.png)
            [file](file:///tmp/secret.png)
            [data](data:image/png;base64,AAAA)
            [local](https://localhost/image.png)
            [private](https://192.168.1.2/image.png)
            This prose mentions https://example.com/not-a-link.png but is not a deliverable.
            MEDIA:javascript:alert(1)
            MEDIA:/tmp/bad\u0000name.png
            """.trimIndent(),
        )

        assertTrue(artifacts.isEmpty())
    }

    @Test
    fun deduplicatesByStableSourceIdentityAndAppliesBounds() {
        val repeated = """
            MEDIA:/tmp/output.png
            ![same](https://EXAMPLE.com:443/output.png)
            MEDIA:/tmp/output.png
            [same](https://example.com/output.png)
            MEDIA:/tmp/second.mp3
        """.trimIndent()
        val artifacts = ArtifactExtractor.extract(
            repeated,
            ArtifactExtractionLimits(maxItems = 2),
        )

        assertEquals(2, artifacts.size)
        assertEquals("/tmp/output.png", artifacts[0].source)
        assertEquals("https://example.com/output.png", artifacts[1].source)
        assertEquals(artifacts[1].stableIdentity, ArtifactExtractor.extract("[again](https://example.com/output.png)").single().stableIdentity)
    }

    @Test
    fun capsTranscriptSourceLocationAndDisplayNameLengths() {
        val longName = "abcdefghijkl.png"
        val text = "MEDIA:/a/$longName\nMEDIA:/b/second-long-name.png"
        val artifacts = ArtifactExtractor.extract(
            text,
            ArtifactExtractionLimits(
                maxTranscriptChars = text.length,
                maxDisplayNameChars = 12,
                maxSourceChars = 20,
                maxLocationChars = 20,
            ),
        )

        assertEquals(1, artifacts.size)
        assertTrue(artifacts.single().displayName.length <= 12)
        assertTrue(artifacts.single().source.length <= 20)
    }

    @Test
    fun extractsOnlyMessageTextNotReasoningAndSanitizesDisplayLabels() {
        val artifacts = ArtifactExtractor.extract(
            listOf(
                ChatMessage(
                    role = ChatMessageRole.Assistant,
                    text = "[download](https://files.example/download)",
                    reasoningText = "MEDIA:/tmp/hidden.png",
                ),
            ),
        )

        assertEquals(1, artifacts.size)
        assertEquals(ArtifactType.File, artifacts.single().type)
        assertEquals("download", artifacts.single().displayName)
        assertFalse(artifacts.single().displayName.contains('/'))
    }
}
