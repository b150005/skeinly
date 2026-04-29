package io.github.b150005.knitnote.ui.patternedit

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.domain.model.Visibility
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_back
import io.github.b150005.knitnote.generated.resources.action_save
import io.github.b150005.knitnote.generated.resources.hint_gauge_example
import io.github.b150005.knitnote.generated.resources.hint_needle_size_example
import io.github.b150005.knitnote.generated.resources.hint_yarn_info_example
import io.github.b150005.knitnote.generated.resources.label_description_optional
import io.github.b150005.knitnote.generated.resources.label_difficulty
import io.github.b150005.knitnote.generated.resources.label_gauge
import io.github.b150005.knitnote.generated.resources.label_needle_size
import io.github.b150005.knitnote.generated.resources.label_pattern_title
import io.github.b150005.knitnote.generated.resources.label_visibility
import io.github.b150005.knitnote.generated.resources.label_visibility_private
import io.github.b150005.knitnote.generated.resources.label_visibility_public
import io.github.b150005.knitnote.generated.resources.label_visibility_shared
import io.github.b150005.knitnote.generated.resources.label_yarn_info
import io.github.b150005.knitnote.generated.resources.title_edit_pattern
import io.github.b150005.knitnote.generated.resources.title_new_pattern
import io.github.b150005.knitnote.ui.components.labelKey
import io.github.b150005.knitnote.ui.components.localized
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
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

    val errorText = state.error?.localized()

    LaunchedEffect(errorText) {
        errorText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(PatternEditEvent.ClearError)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (patternId == null) Res.string.title_new_pattern else Res.string.title_edit_pattern,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("backButton")) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back),
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
                            Text(stringResource(Res.string.action_save))
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
                    label = { Text(stringResource(Res.string.label_pattern_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = state.description,
                    onValueChange = { viewModel.onEvent(PatternEditEvent.UpdateDescription(it)) },
                    label = { Text(stringResource(Res.string.label_description_optional)) },
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
                    label = { Text(stringResource(Res.string.label_gauge)) },
                    placeholder = { Text(stringResource(Res.string.hint_gauge_example)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = state.yarnInfo,
                    onValueChange = { viewModel.onEvent(PatternEditEvent.UpdateYarnInfo(it)) },
                    label = { Text(stringResource(Res.string.label_yarn_info)) },
                    placeholder = { Text(stringResource(Res.string.hint_yarn_info_example)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = state.needleSize,
                    onValueChange = { viewModel.onEvent(PatternEditEvent.UpdateNeedleSize(it)) },
                    label = { Text(stringResource(Res.string.label_needle_size)) },
                    placeholder = { Text(stringResource(Res.string.hint_needle_size_example)) },
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
            text = stringResource(Res.string.label_difficulty),
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
                    label = { Text(stringResource(difficulty.labelKey)) },
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
            text = stringResource(Res.string.label_visibility),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Visibility.entries.forEach { visibility ->
                FilterChip(
                    selected = selected == visibility,
                    onClick = { onSelected(visibility) },
                    label = { Text(stringResource(visibility.labelKey)) },
                )
            }
        }
    }
}

private val Visibility.labelKey: StringResource
    get() =
        when (this) {
            Visibility.PRIVATE -> Res.string.label_visibility_private
            Visibility.SHARED -> Res.string.label_visibility_shared
            Visibility.PUBLIC -> Res.string.label_visibility_public
        }
