package com.unsupportedpastels.hermesandroid.navigation

import androidx.navigation3.runtime.NavKey
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProjectId
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute : NavKey

/** Compatibility key retained while callers migrate to [HomeRoute]. */
@Serializable
data object SessionListRoute : NavKey

@Serializable
data object RecentSessionsRoute : NavKey

@Serializable
data class ProjectRoute(val projectId: ProjectId) : NavKey

@Serializable
data object ServerSettingsRoute : NavKey

/**
 * Settings sub-section destinations. [ServerSettingsRoute] is the hub; each of
 * these is pushed onto the same back stack and rendered in the detail pane so
 * compact windows get native push/pop and Back, and expanded windows keep the
 * adaptive list/detail behaviour.
 */
@Serializable
data object SettingsServersRoute : NavKey

@Serializable
data object SettingsFilesRoute : NavKey

@Serializable
data object SettingsConnectionRoute : NavKey

@Serializable
data object SettingsModelRoute : NavKey

@Serializable
data object SettingsVoiceRoute : NavKey

@Serializable
data object SettingsOfflineRoute : NavKey

@Serializable
data object SettingsJobsRoute : NavKey

@Serializable
data object SettingsAccountRoute : NavKey

@Serializable
data class SessionDetailRoute(val durableSessionId: DurableSessionId) : NavKey
