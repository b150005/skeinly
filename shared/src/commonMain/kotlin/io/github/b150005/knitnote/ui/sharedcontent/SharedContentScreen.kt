package io.github.b150005.knitnote.ui.sharedcontent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.SharePermission
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_back
import io.github.b150005.knitnote.generated.resources.action_fork_to_projects
import io.github.b150005.knitnote.generated.resources.label_difficulty
import io.github.b150005.knitnote.generated.resources.label_gauge
import io.github.b150005.knitnote.generated.resources.label_needle_size
import io.github.b150005.knitnote.generated.resources.label_projects
import io.github.b150005.knitnote.generated.resources.label_yarn
import io.github.b150005.knitnote.generated.resources.state_pattern_not_found
import io.github.b150005.knitnote.generated.resources.state_view_only_share
import io.github.b150005.knitnote.generated.resources.title_shared_pattern
import io.github.b150005.knitnote.ui.components.labelKey
import io.github.b150005.knitnote.ui.components.localized
import kotlinx.coroutines.flow.collect
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedContentScreen(
    token: String? = null,
    shareId: String? = null,
    onBack: () -> Unit,
    onForked: (projectId: String) -> Unit,
    viewModel: SharedContentViewModel = koinViewModel { parametersOf(token, shareId) },
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val errorText = state.error?.localized()

    LaunchedEffect(errorText) {
        errorText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(SharedContentEvent.ClearError)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.forkedProjectId.collect { projectId ->
            onForked(projectId)
        }
    }

    Scaffold(
        modifier = Modifier.testTag("sharedContentScreen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.title_shared_pattern)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("backButton")) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val pattern = state.pattern
            when {
                state.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                pattern != null -> {
                    PatternContent(
                        pattern = pattern,
                        projectCount = state.projectCount,
                        canFork = state.share?.permission == SharePermission.FORK,
                        isForkInProgress = state.isForkInProgress,
                        onFork = { viewModel.onEvent(SharedContentEvent.Fork) },
                    )
                }
                else -> {
                    Text(
                        text = state.error?.localized() ?: stringResource(Res.string.state_pattern_not_found),
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun PatternContent(
    pattern: Pattern,
    projectCount: Int,
    canFork: Boolean,
    isForkInProgress: Boolean,
    onFork: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = pattern.title,
            style = MaterialTheme.typography.headlineMedium,
        )

        pattern.description?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        pattern.difficulty?.let { difficulty ->
            DetailRow(
                labelRes = Res.string.label_difficulty,
                value = stringResource(difficulty.labelKey),
            )
        }

        pattern.gauge?.let { gauge ->
            DetailRow(labelRes = Res.string.label_gauge, value = gauge)
        }

        pattern.yarnInfo?.let { yarnInfo ->
            DetailRow(labelRes = Res.string.label_yarn, value = yarnInfo)
        }

        pattern.needleSize?.let { needleSize ->
            DetailRow(labelRes = Res.string.label_needle_size, value = needleSize)
        }

        if (projectCount > 0) {
            DetailRow(labelRes = Res.string.label_projects, value = projectCount.toString())
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (canFork) {
            Button(
                onClick = onFork,
                modifier = Modifier.fillMaxWidth().testTag("forkButton"),
                enabled = !isForkInProgress,
            ) {
                if (isForkInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(Res.string.action_fork_to_projects))
                }
            }
        } else {
            Text(
                text = stringResource(Res.string.state_view_only_share),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailRow(
    labelRes: StringResource,
    value: String,
) {
    Column {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
