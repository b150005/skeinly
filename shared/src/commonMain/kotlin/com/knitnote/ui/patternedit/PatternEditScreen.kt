package com.knitnote.ui.patternedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.knitnote.domain.model.Difficulty
import com.knitnote.domain.model.Visibility
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PatternEditScreen(
    patternId: String? = null,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    viewModel: PatternEditViewModel = koinViewModel { parametersOf(patternId) },
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.saveSuccess.collect { onSaved() }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(PatternEditEvent.ClearError)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (patternId == null) "New Pattern" else "Edit Pattern")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        TextButton(
                            onClick = { viewModel.onEvent(PatternEditEvent.Save) },
                            enabled = state.title.isNotBlank(),
                        ) {
                            Text("Save")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = { viewModel.onEvent(PatternEditEvent.UpdateTitle(it)) },
                    label = { Text("Pattern Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = state.description,
                    onValueChange = { viewModel.onEvent(PatternEditEvent.UpdateDescription(it)) },
                    label = { Text("Description (optional)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )

                DifficultySelector(
                    selected = state.difficulty,
                    onSelected = { viewModel.onEvent(PatternEditEvent.UpdateDifficulty(it)) },
                )

                OutlinedTextField(
                    value = state.gauge,
                    onValueChange = { viewModel.onEvent(PatternEditEvent.UpdateGauge(it)) },
                    label = { Text("Gauge (e.g., 20 sts = 4 in)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = state.yarnInfo,
                    onValueChange = { viewModel.onEvent(PatternEditEvent.UpdateYarnInfo(it)) },
                    label = { Text("Yarn (e.g., Worsted weight, 100% merino)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = state.needleSize,
                    onValueChange = { viewModel.onEvent(PatternEditEvent.UpdateNeedleSize(it)) },
                    label = { Text("Needle Size (e.g., US 7 / 4.5mm)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                VisibilitySelector(
                    selected = state.visibility,
                    onSelected = { viewModel.onEvent(PatternEditEvent.UpdateVisibility(it)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DifficultySelector(
    selected: Difficulty?,
    onSelected: (Difficulty?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Difficulty",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Difficulty.entries.forEach { difficulty ->
                val isSelected = selected == difficulty
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        onSelected(if (isSelected) null else difficulty)
                    },
                    label = { Text(difficulty.displayName()) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VisibilitySelector(
    selected: Visibility,
    onSelected: (Visibility) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Visibility",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Visibility.entries.forEach { visibility ->
                FilterChip(
                    selected = selected == visibility,
                    onClick = { onSelected(visibility) },
                    label = { Text(visibility.displayName()) },
                )
            }
        }
    }
}

private fun Difficulty.displayName(): String =
    when (this) {
        Difficulty.BEGINNER -> "Beginner"
        Difficulty.INTERMEDIATE -> "Intermediate"
        Difficulty.ADVANCED -> "Advanced"
    }

private fun Visibility.displayName(): String =
    when (this) {
        Visibility.PRIVATE -> "Private"
        Visibility.SHARED -> "Shared"
        Visibility.PUBLIC -> "Public"
    }
