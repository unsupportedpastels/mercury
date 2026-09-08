package com.unsupportedpastels.mercury.core.rpc

/** Explicit model-capability matching shared by the native session state owners. */
object ModelCapabilityPolicy {
    fun identifiersMatch(first: String?, second: String?): Boolean {
        if (first == null || second == null) return false
        if (first == second) return true
        return ('/' in first) != ('/' in second) &&
            first.substringAfterLast('/') == second.substringAfterLast('/')
    }

    /** The caller supplies only the exact selected provider's advertised catalog. */
    fun fromCatalog(model: String, capabilities: Map<String, ModelCapabilitiesSpec>): ModelCapabilitiesSpec? {
        capabilities[model]?.takeIf { it.explicit }?.let { return it }
        return capabilities.filterKeys { identifiersMatch(it, model) }
            .values.filter { it.explicit }.distinct().singleOrNull()
    }

    private val ModelCapabilitiesSpec.explicit: Boolean
        get() = fast != null || reasoning != null
}
