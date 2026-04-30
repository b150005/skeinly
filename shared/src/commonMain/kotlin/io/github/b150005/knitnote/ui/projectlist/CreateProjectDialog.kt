package io.github.b150005.knitnote.ui.projectlist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_cancel
import io.github.b150005.knitnote.generated.resources.action_create
import io.github.b150005.knitnote.generated.resources.dialog_new_project_title
import io.github.b150005.knitnote.generated.resources.label_none
import io.github.b150005.knitnote.generated.resources.label_pattern_optional
import io.github.b150005.knitnote.generated.resources.label_title
import io.github.b150005.knitnote.generated.resources.label_total_rows_optional
import io.github.b150005.knitnote.ui.platform.dialogTestTagsAsResourceId
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, totalRows: Int?, patternId: String?) -> Unit,
    patterns: List<Pattern> = emptyList(),
) {
    var title by remember { mutableStateOf("") }
    var totalRowsText by remember { mutableStateOf("") }
    var selectedPatternId by remember { mutableStateOf<String?>(null) }
    var patternDropdownExpanded by remember { mutableStateOf(false) }

    val noneLabel = stringResource(Res.string.label_none)
    val selectedPatternTitle =
        patterns.find { it.id == selectedPatternId }?.title ?: noneLabel

    AlertDialog(
        // `testTagsAsResourceId = true` is applied at MainActivity's root Box but does
        // NOT propagate into Compose Dialog windows — they create an independent
        // composition subtree rooted in a separate Android Window. The
        // `dialogTestTagsAsResourceId()` expect/actual helper applies that semantics
        // on Android and is a no-op on iOS (SwiftUI dialogs don't need it). Lets
        // Maestro route via `id:` selectors (newProjectDialog, projectNameInput,
        // totalRowsInput, createProjectButton) and closes the "dialog-cross-window
        // testTag" half of Phase 33.2's debt.
        modifier =
            Modifier
                .testTag("newProjectDialog")
                .dialogTestTagsAsResourceId(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.dialog_new_project_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(Res.string.label_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("projectNameInput"),
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (patterns.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = patternDropdownExpanded,
                        onExpandedChange = { patternDropdownExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedPatternTitle,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(Res.string.label_pattern_optional)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = patternDropdownExpanded) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                    .testTag("patternPicker"),
                        )
                        ExposedDropdownMenu(
                            expanded = patternDropdownExpanded,
                            onDismissRequest = { patternDropdownExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(noneLabel) },
                                onClick = {
                                    selectedPatternId = null
                                    patternDropdownExpanded = false
                                },
                            )
                            patterns.forEach { pattern ->
                                DropdownMenuItem(
                                    text = { Text(pattern.title) },
                                    onClick = {
                                        selectedPatternId = pattern.id
                                        patternDropdownExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = totalRowsText,
                    onValueChange = { totalRowsText = it.filter { ch -> ch.isDigit() } },
                    label = { Text(stringResource(Res.string.label_total_rows_optional)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("totalRowsInput"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isNotBlank()) {
                        val totalRows = totalRowsText.toIntOrNull()
                        onCreate(title.trim(), totalRows, selectedPatternId)
                    }
                },
                enabled = title.isNotBlank(),
                modifier = Modifier.testTag("createProjectButton"),
            ) {
                Text(stringResource(Res.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}
