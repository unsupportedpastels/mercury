package com.unsupportedpastels.hermesandroid.app

import com.unsupportedpastels.mercury.core.files.RelayFoldersContract
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class ProjectId(val value: String) {
    init {
        require(value.isNotBlank()) { "Project ID must not be blank" }
        require(value.length <= MAX_LENGTH) { "Project ID is too long" }
    }

    companion object { const val MAX_LENGTH = 256 }
}

@JvmInline
value class LocalDraftId(val value: String) {
    init {
        require(value.isNotBlank()) { "Local draft ID must not be blank" }
        require(value.length <= 128) { "Local draft ID is too long" }
    }
}

data class ProjectTreeResult(
    val projects: List<ProjectSummary>,
    val activeProjectId: ProjectId? = null,
    val scopedSessionIds: Set<DurableSessionId> = emptySet(),
)

data class ProjectSessionsResult(
    val project: ProjectSummary,
    val sessions: List<SessionSummary>,
)

/**
 * A bounded project row from the read-only Hermes project metadata RPCs.
 *
 * This is intentionally a regular class rather than a data class: values are
 * normalized at the model boundary while retaining the convenient copy API at
 * call sites. Project identity is the server-provided ID, never the basename
 * of [primaryPath].
 */
class ProjectSummary(
    val id: ProjectId,
    label: String,
    primaryPath: String?,
    sessionCount: Int,
    previewSessions: List<SessionSummary>,
) {
    val label: String = label.take(MAX_LABEL_LENGTH).ifBlank { id.value }
    val primaryPath: String? = primaryPath
        ?.take(MAX_PATH_LENGTH)
        ?.takeIf(String::isNotBlank)
    val sessionCount: Int = sessionCount.coerceAtLeast(0)
    val previewSessions: List<SessionSummary> = previewSessions
        .take(MAX_PREVIEW_SESSIONS)
        .map { session ->
            session.copy(
                title = session.title.take(MAX_SESSION_TITLE_LENGTH).ifBlank { "Untitled session" },
                projectId = id,
                workspacePath = session.workspacePath?.take(MAX_PATH_LENGTH),
            )
        }

    fun copy(
        id: ProjectId = this.id,
        label: String = this.label,
        primaryPath: String? = this.primaryPath,
        sessionCount: Int = this.sessionCount,
        previewSessions: List<SessionSummary> = this.previewSessions,
    ) = ProjectSummary(id, label, primaryPath, sessionCount, previewSessions)

    override fun equals(other: Any?): Boolean = other is ProjectSummary &&
        id == other.id && label == other.label && primaryPath == other.primaryPath &&
        sessionCount == other.sessionCount && previewSessions == other.previewSessions

    override fun hashCode(): Int = listOf(id, label, primaryPath, sessionCount, previewSessions).hashCode()

    companion object {
        const val MAX_LABEL_LENGTH = 160
        const val MAX_PATH_LENGTH = 1_024
        const val MAX_PREVIEW_SESSIONS = 3
        const val MAX_SESSION_TITLE_LENGTH = 160
        const val MAX_PROJECTS = 100
        const val MAX_PROJECT_SESSIONS = 100
        const val MAX_SCOPED_SESSION_IDS = 2_000
    }
}

sealed interface ProjectLoadState {
    data object Loading : ProjectLoadState
    data object Unsupported : ProjectLoadState
    data class TransientError(val message: String) : ProjectLoadState
    data class Loaded(
        val projects: List<ProjectSummary>,
        val activeProjectId: ProjectId? = null,
        val scopedSessionIds: Set<DurableSessionId> = emptySet(),
    ) : ProjectLoadState
}

sealed interface ProjectSessionLoadState {
    data object Loading : ProjectSessionLoadState
    data object Unsupported : ProjectSessionLoadState
    data class TransientError(val message: String) : ProjectSessionLoadState
    data class Loaded(val sessions: List<SessionSummary>) : ProjectSessionLoadState
}

fun validHostFolderName(name: String?): String? {
    val value = name?.trim()?.takeIf(String::isNotBlank) ?: return null
    return RelayFoldersContract.validName(value)
        ?.takeIf { it.length <= MAX_HOST_FOLDER_NAME_LENGTH }
}

private const val MAX_HOST_FOLDER_NAME_LENGTH = 128

/**
 * The gateway's synthetic bucket holding every session no project claimed
 * (labelled "Home" by Hermes clients). It intentionally has no workspace path:
 * sessions in it are created without a cwd and the server applies its default
 * working directory. Drafts in this bucket must therefore be sendable without
 * a workspace, unlike drafts in real projects.
 */
const val NO_PROJECT_BUCKET_ID = "__no_project__"

fun isNoProjectBucket(projectId: ProjectId?): Boolean = projectId?.value == NO_PROJECT_BUCKET_ID

fun validProjectWorkspacePath(path: String?): String? =
    RelayFoldersContract.validPath(path)

/**
 * Reconciles metadata-only project rows with the authenticated REST session
 * listing. Durable IDs are the join key; runtime IDs are never involved.
 */
fun reconcileProjectSession(
    projectId: ProjectId,
    projectSession: SessionSummary,
    durableSessions: List<SessionSummary>,
): SessionSummary {
    val restSession = durableSessions.firstOrNull { it.id == projectSession.id }
    return (restSession ?: projectSession).copy(
        projectId = projectId,
        workspacePath = restSession?.workspacePath ?: projectSession.workspacePath,
        title = (restSession?.title ?: projectSession.title)
            .take(ProjectSummary.MAX_SESSION_TITLE_LENGTH)
            .ifBlank { "Untitled session" },
        preview = restSession?.preview ?: projectSession.preview,
        lastActiveEpochSeconds = restSession?.lastActiveEpochSeconds ?: projectSession.lastActiveEpochSeconds,
        messageCount = restSession?.messageCount ?: projectSession.messageCount,
        model = restSession?.model ?: projectSession.model,
        provider = restSession?.provider ?: projectSession.provider,
        profile = restSession?.profile ?: projectSession.profile,
    )
}
