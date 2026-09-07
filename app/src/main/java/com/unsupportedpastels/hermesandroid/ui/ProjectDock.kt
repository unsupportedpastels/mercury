package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.theme.LocalHermesSemanticColors

@Composable
internal fun ProjectDock(
    state: ProjectDockState,
    projects: List<ProjectSummary>,
    selectedProjectId: ProjectId?,
    projectIcons: Map<ProjectId, ProjectIconId>,
    canStartNewTask: Boolean,
    settingsSelected: Boolean,
    onProjectSelected: (ProjectId) -> Unit,
    onChooseProjectIcon: (ProjectId) -> Unit,
    onCreateProject: () -> Unit,
    onNewTask: () -> Unit,
    onSettings: () -> Unit,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onHide: () -> Unit,
) {
    val expanded = state == ProjectDockState.Expanded
    val dockWidth by animateDpAsState(
        targetValue = if (expanded) 228.dp else 76.dp,
        label = "Project dock width",
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .width(dockWidth)
            .fillMaxSize()
            .semantics {
                contentDescription = if (expanded) {
                    "Project dock, expanded"
                } else {
                    "Project dock, collapsed"
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Fill the status-bar inset with the dock's own surface so app
            // content reads as starting *under* the system bar rather than
            // bleeding up behind a transparent status bar.
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars),
            )
            Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (expanded) 12.dp else 10.dp)
                    .padding(top = 4.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
            if (!expanded) {
                ProjectDockControl(
                    glyph = "›",
                    description = "Expand project dock",
                    onClick = onExpand,
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                // The expanded dock's collapse control floats in this top band;
                // keep the project list itself flush so the first option fills
                // the space immediately below the status-bar inset.
                contentPadding = PaddingValues(top = 2.dp, bottom = 2.dp),
            ) {
                itemsIndexed(projects, key = { _, project -> project.id.value }) { index, project ->
                    val iconId = projectIcons[project.id] ?: defaultProjectIconId(project)
                    val iconLabel = ProjectIconCatalog.entries.first { it.id == iconId }.label
                    ProjectDockAction(
                        glyph = projectDockInitial(project.label),
                        icon = projectIconVector(iconId),
                        iconDescription = "${project.label} icon $iconLabel",
                        label = project.label,
                        description = "Open project ${project.label}",
                        expanded = expanded,
                        selected = project.id == selectedProjectId,
                        trailingContent = if (expanded && project.id == selectedProjectId) {
                            {
                                IconButton(
                                    onClick = { onChooseProjectIcon(project.id) },
                                    modifier = Modifier
                                        // The floating collapse control occupies the
                                        // top-right corner. Keep the first row's edit
                                        // target clear without moving the row itself.
                                        .offset(x = if (index == 0) (-44).dp else 0.dp)
                                        .semantics {
                                        contentDescription = "Choose icon for ${project.label}"
                                        },
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        } else {
                            null
                        },
                        onClick = { onProjectSelected(project.id) },
                    )
                }
            }
            HorizontalDivider()
            ProjectDockAction(
                glyph = "+",
                icon = Icons.Outlined.CreateNewFolder,
                iconDescription = null,
                label = "Create project",
                description = "Create project",
                expanded = expanded,
                selected = false,
                enabled = canStartNewTask,
                onClick = onCreateProject,
            )
            ProjectDockAction(
                glyph = "+",
                label = "New task",
                description = selectedProjectId
                    ?.let { id -> projects.firstOrNull { it.id == id }?.label }
                    ?.let { "New task in $it" }
                    ?: "New task",
                expanded = expanded,
                selected = false,
                enabled = canStartNewTask,
                accent = true,
                onClick = onNewTask,
            )
            ProjectDockAction(
                glyph = "⚙",
                label = "Settings",
                description = "Settings navigation",
                expanded = expanded,
                selected = settingsSelected,
                onClick = onSettings,
            )
            if (!expanded) {
                ProjectDockControl(
                    glyph = "‹",
                    description = "Hide project dock",
                    onClick = onHide,
                )
            }
            }
            // Floating collapse control: overlays the top-right corner so the
            // nav list can start flush at the top instead of reserving a full
            // header row (which left a large empty band on the left).
            if (expanded) {
                ProjectDockControl(
                    glyph = "‹",
                    description = "Collapse project dock",
                    onClick = onCollapse,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 6.dp, top = 2.dp),
                )
            }
            }
        }
    }
}

@Composable
private fun ProjectDockAction(
    glyph: String,
    label: String,
    description: String,
    expanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    accent: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    iconDescription: String? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val semanticColors = LocalHermesSemanticColors.current
    val containerColor = when {
        accent -> semanticColors.active
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val contentColor = when {
        accent -> semanticColors.onActive
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val actionModifier = if (expanded) {
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
    } else {
        Modifier.size(48.dp)
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
        modifier = actionModifier.semantics {
            contentDescription = description
            this.selected = selected
        },
    ) {
        Row(
            modifier = if (expanded) {
                Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
            } else {
                Modifier.fillMaxSize()
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (expanded) Arrangement.spacedBy(10.dp) else Arrangement.Center,
        ) {
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(24.dp)
                            .then(
                                iconDescription?.let { description ->
                                    Modifier.semantics { contentDescription = description }
                                } ?: Modifier,
                            ),
                    )
                } else {
                    Text(glyph, style = MaterialTheme.typography.titleMedium)
                }
            }
            if (expanded) {
                Text(
                    label,
                    modifier = if (trailingContent != null) Modifier.weight(1f) else Modifier,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )
                trailingContent?.invoke()
            }
        }
    }
}

@Composable
private fun ProjectDockControl(
    glyph: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .size(48.dp)
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                glyph,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
internal fun ProjectDockEdgeTab(
    modifier: Modifier = Modifier,
    onShow: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(width = 48.dp, height = 72.dp)
            .semantics { contentDescription = "Show project dock" }
            .clickable(onClick = onShow),
    ) {
        Surface(
            shape = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .size(width = 16.dp, height = 72.dp)
                .align(Alignment.CenterStart),
        ) {}
        Text(
            "›",
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.CenterStart),
        )
    }
}

private fun projectDockInitial(label: String): String {
    val initials = label
        .trim()
        .split(Regex("\\s+"))
        .take(2)
        .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
        .joinToString(separator = "") { it.uppercaseChar().toString() }
    return initials.ifBlank { "•" }
}
