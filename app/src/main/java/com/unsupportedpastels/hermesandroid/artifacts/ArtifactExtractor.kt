package com.unsupportedpastels.hermesandroid.artifacts

import com.unsupportedpastels.hermesandroid.gateway.ChatMessage

/**
 * Artifact extraction now decides in the shared KMP core
 * (AGENTS.md cross-platform rule) so Android and iOS accept and
 * reject exactly the same transcript references. These wrappers keep the
 * app's ChatMessage-based call sites.
 */
object ArtifactExtractor {
    fun extract(
        messages: List<ChatMessage>,
        limits: ArtifactExtractionLimits = ArtifactExtractionLimits(),
    ): List<Artifact> =
        com.unsupportedpastels.mercury.core.artifacts.ArtifactExtractor.extract(
            messages.map { it.text },
            limits,
        )

    fun extract(
        message: ChatMessage,
        limits: ArtifactExtractionLimits = ArtifactExtractionLimits(),
    ): List<Artifact> = extract(listOf(message), limits)

    fun extract(
        text: String,
        limits: ArtifactExtractionLimits = ArtifactExtractionLimits(),
    ): List<Artifact> =
        com.unsupportedpastels.mercury.core.artifacts.ArtifactExtractor.extract(text, limits)
}
