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
import androidx.compose.ui.unit.dp
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.SharePermission
import kotlinx.coroutines.flow.collect
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

    LaunchedEffect(state.error) {
        state.error?.let {
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
        topBar = {
            TopAppBar(
                title = { Text("Shared Pattern") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                        text = state.error ?: "Pattern not found",
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
            DetailRow(label = "Difficulty", value = difficulty.name.lowercase().replaceFirstChar { it.uppercase() })
        }

        pattern.gauge?.let { gauge ->
            DetailRow(label = "Gauge", value = gauge)
        }

        pattern.yarnInfo?.let { yarnInfo ->
            DetailRow(label = "Yarn", value = yarnInfo)
        }

        pattern.needleSize?.let { needleSize ->
            DetailRow(label = "Needle Size", value = needleSize)
        }

        if (projectCount > 0) {
            DetailRow(label = "Projects using this pattern", value = projectCount.toString())
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (canFork) {
            Button(
                onClick = onFork,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isForkInProgress,
            ) {
                if (isForkInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Fork to My Projects")
                }
            }
        } else {
            Text(
                text = "View only — forking is not permitted for this share",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
