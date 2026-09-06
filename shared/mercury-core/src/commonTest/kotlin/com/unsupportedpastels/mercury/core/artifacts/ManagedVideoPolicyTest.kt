package com.unsupportedpastels.mercury.core.artifacts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ManagedVideoPolicyTest {
    @Test fun standaloneVideoGrammarIsSharedByInlineAndCatalogConsumers() {
        for (directive in listOf("MEDIA: /tmp/clip.mp4", "  MEDIA:/tmp/clip.mp4  ", "MEDIA: \"/tmp/clip.mp4\"", "`MEDIA:/tmp/clip.mp4`")) {
            assertEquals("/tmp/clip.mp4", ArtifactExtractor.standaloneMediaSource(directive))
            assertEquals(ArtifactType.Video, ArtifactExtractor.extract(directive).single().type)
        }
        for (malformed in listOf("Here is MEDIA: /tmp/clip.mp4", "MEDIA: /tmp/clip.mp4 trailing", "MEDIA:", "MEDIA: \"/tmp/clip.mp4")) {
            assertEquals(null, ArtifactExtractor.standaloneMediaSource(malformed))
        }
    }

    @Test fun videoExtensionsAndLabelAreClassifiedAcrossOrigins() {
        for (extension in listOf("mp4", "webm", "mov", "m4v", "mkv", "MP4")) {
            assertEquals(ArtifactType.Video, ArtifactExtractor.extract("MEDIA:/workspace/clip.$extension").single().type)
            assertTrue(ManagedVideoPolicy.isManagedVideoPath("/workspace/clip.$extension"))
        }
        assertEquals(ArtifactType.Video, ArtifactExtractor.extract("[Video: demo](https://cdn.example/download)").single().type)
        assertEquals(ArtifactType.Video, ArtifactExtractor.extract("https://cdn.example/clip.webm?dl=1").single().type)
    }
    @Test fun pathAndMimePolicyFailClosed() {
        for (path in listOf("relative.mp4", "//host/clip.mp4", "/a/../clip.mp4", "/a/./clip.mp4", "/a\\clip.mp4", "/a/clip.pdf", "/a/clip.mp4?x=1", "/a/\u0000clip.mp4", "/" + "a".repeat(4096) + ".mp4")) {
            assertFalse(ManagedVideoPolicy.isManagedVideoPath(path), path)
        }
        assertTrue(ManagedVideoPolicy.isVideoMimeType(" Video/MP4; codecs=avc1 "))
        for (mime in listOf("video/", "image/mp4", "application/octet-stream", "video/mp4\r\nheader", "video/a b")) {
            assertFalse(ManagedVideoPolicy.isVideoMimeType(mime), mime)
        }
        assertEquals(268435456L, ManagedVideoPolicy.MAX_DOWNLOAD_BYTES)
        assertEquals(536870912L, ManagedVideoPolicy.MAX_CACHE_BYTES)
    }
}
