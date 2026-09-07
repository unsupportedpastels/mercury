package com.unsupportedpastels.mercury.core.connection

/** The two persisted startup connection namespaces. */
enum class StartupConnectionKind {
    DIRECT,
    RELAY,
}

/**
 * Secret-free configured target projection used by startup selection.
 *
 * The id is an app-local catalog/pairing identifier. Origins, cookies, tokens,
 * and relay key material deliberately never enter this policy.
 */
data class StartupConnectionTarget(
    val kind: StartupConnectionKind,
    val id: String,
    val usable: Boolean,
)

/** A typed, secret-free last-successful identity. */
data class StartupConnectionChoice(
    val kind: StartupConnectionKind,
    val id: String,
)

enum class StartupConnectionAction {
    ONBOARDING,
    AUTO_CONNECT,
    SHOW_PICKER,
}

data class StartupConnectionDecision(
    val action: StartupConnectionAction,
    val selected: StartupConnectionChoice?,
    val savedChoiceUnavailable: Boolean,
)

/**
 * Pure startup selection policy shared by Android and iOS.
 *
 * Rules:
 * - no configured rows means first-run onboarding;
 * - a valid saved successful identity is the only automatic choice;
 * - without a saved identity, exactly one usable target may autostart;
 * - pending/unusable relay rows are never selected for autostart;
 * - a missing, removed, or pending saved identity opens the picker instead of
 *   silently falling back to another host.
 */
object StartupConnectionPolicy {
    fun decide(
        targets: List<StartupConnectionTarget>,
        lastSuccessful: StartupConnectionChoice?,
    ): StartupConnectionDecision {
        val uniqueTargets = targets
            .asSequence()
            .filter { it.id.isNotBlank() }
            .distinctBy { it.kind to it.id }
            .toList()
        val usable = uniqueTargets.filter { it.usable }

        if (lastSuccessful != null) {
            val matching = usable.firstOrNull {
                it.kind == lastSuccessful.kind && it.id == lastSuccessful.id
            }
            if (matching != null) {
                return StartupConnectionDecision(
                    action = StartupConnectionAction.AUTO_CONNECT,
                    selected = lastSuccessful,
                    savedChoiceUnavailable = false,
                )
            }
            return StartupConnectionDecision(
                action = StartupConnectionAction.SHOW_PICKER,
                selected = null,
                savedChoiceUnavailable = true,
            )
        }

        return when {
            uniqueTargets.isEmpty() -> StartupConnectionDecision(
                action = StartupConnectionAction.ONBOARDING,
                selected = null,
                savedChoiceUnavailable = false,
            )
            uniqueTargets.size == 1 && usable.size == 1 -> StartupConnectionDecision(
                action = StartupConnectionAction.AUTO_CONNECT,
                selected = usable.single().let {
                    StartupConnectionChoice(kind = it.kind, id = it.id)
                },
                savedChoiceUnavailable = false,
            )
            else -> StartupConnectionDecision(
                action = StartupConnectionAction.SHOW_PICKER,
                selected = null,
                savedChoiceUnavailable = false,
            )
        }
    }
}
