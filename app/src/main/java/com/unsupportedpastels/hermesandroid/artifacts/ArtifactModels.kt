package com.unsupportedpastels.hermesandroid.artifacts

/**
 * Artifact models live in the shared KMP core; these aliases keep existing
 * imports working. (Enum entries are referenced via these aliases — if a
 * call site needs a nested classifier, import the shared type directly.)
 */
typealias Artifact = com.unsupportedpastels.mercury.core.artifacts.Artifact
typealias ArtifactType = com.unsupportedpastels.mercury.core.artifacts.ArtifactType
typealias ArtifactOrigin = com.unsupportedpastels.mercury.core.artifacts.ArtifactOrigin
typealias ArtifactExtractionLimits = com.unsupportedpastels.mercury.core.artifacts.ArtifactExtractionLimits
