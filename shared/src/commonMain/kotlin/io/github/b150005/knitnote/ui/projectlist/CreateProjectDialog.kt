package io.github.b150005.knitnote.ui.projectlist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
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

    val selectedPatternTitle =
        patterns.find { it.id == selectedPatternId }?.title ?: "None"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Project") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Project Name") },
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
                            label = { Text("Pattern (optional)") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = patternDropdownExpanded) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = patternDropdownExpanded,
                            onDismissRequest = { patternDropdownExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("None") },
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
                    label = { Text("Total Rows (optional)") },
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
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
