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
