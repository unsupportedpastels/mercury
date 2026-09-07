package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectIconPickerSheet(
    project: ProjectSummary,
    selectedIcon: ProjectIconId,
    onDismiss: () -> Unit,
    onSave: suspend (ProjectIconId) -> Result<Unit>,
) {
    var query by rememberSaveable(project.id.value) { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by rememberSaveable(project.id.value) { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val normalizedQuery = query.trim().lowercase()
    val visibleIcons = remember(normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            ProjectIconCatalog.entries
        } else {
            ProjectIconCatalog.entries.filter { option ->
                option.label.lowercase().contains(normalizedQuery) ||
                    option.searchTerms.any { it.contains(normalizedQuery) }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Choose project icon", style = MaterialTheme.typography.headlineSmall)
            Text(
                project.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it.take(80)
                    saveError = null
                },
                enabled = !isSaving,
                singleLine = true,
                label = { Text("Search icons") },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Search project icons" },
            )
            saveError?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (visibleIcons.isEmpty()) {
                Text(
                    "No matching icons",
                    modifier = Modifier.padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 84.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    gridItems(ProjectIconCatalog.entries.filter { it in visibleIcons }, key = { it.id.persistedValue }) { option ->
                        val selected = option.id == selectedIcon
                        Surface(
                            onClick = {
                                if (!isSaving) {
                                    coroutineScope.launch {
                                        isSaving = true
                                        saveError = null
                                        val result = onSave(option.id)
                                        isSaving = false
                                        if (result.isSuccess) {
                                            onDismiss()
                                        } else {
                                            saveError = "Could not save icon. Try again."
                                        }
                                    }
                                }
                            },
                            enabled = !isSaving,
                            selected = selected,
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                            modifier = Modifier
                                .heightIn(min = 80.dp)
                                .semantics {
                                    contentDescription = "Project icon ${option.label}"
                                    this.selected = selected
                                },
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = projectIconVector(option.id),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                )
                                Text(
                                    option.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
