package io.github.b150005.skeinly.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_back
import io.github.b150005.skeinly.generated.resources.action_close
import io.github.b150005.skeinly.generated.resources.action_export_data
import io.github.b150005.skeinly.generated.resources.body_export_data_explanation
import io.github.b150005.skeinly.generated.resources.body_export_data_not_included
import io.github.b150005.skeinly.generated.resources.body_export_data_ready_detail
import io.github.b150005.skeinly.generated.resources.label_export_data_record_count
import io.github.b150005.skeinly.generated.resources.state_export_data_in_progress
import io.github.b150005.skeinly.generated.resources.state_export_data_ready
import io.github.b150005.skeinly.generated.resources.title_export_data_screen
import io.github.b150005.skeinly.ui.components.localized
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Pre-Phase-40 A20 Option B (docs/en/ops/data-export-sop.md §Scope
 * deferrals) — Settings → Privacy → Export My Data.
 *
 * Non-destructive GDPR Art. 20 / CCPA "right to know" surface (it only
 * reads). Explains what is / isn't included, then a single Export
 * action: progress spinner → on success the OS share sheet is fired by
 * [DataExportViewModel] (via the platform `DataExportSaver`) and a
 * success Card shows the record count; on failure an error Card shows a
 * localized message and the user can re-tap Export.
 *
 * Scaffold / TopAppBar / back-button shape mirrors
 * [io.github.b150005.skeinly.ui.moderation.BlockedUsersScreen]; the
 * result Card mirrors `BugReportPreviewScreen`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataExportScreen(
    onBack: () -> Unit,
    viewModel: DataExportViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        modifier = Modifier.testTag("dataExportScreen"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.title_export_data_screen),
                        modifier = Modifier.semantics { heading() },
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
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = stringResource(Res.string.body_export_data_explanation),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = stringResource(Res.string.body_export_data_not_included),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (val result = state.result) {
                is DataExportResult.Success ->
                    ResultCard(
                        container = MaterialTheme.colorScheme.primaryContainer,
                        onContainer = MaterialTheme.colorScheme.onPrimaryContainer,
                        testTag = "dataExportSuccessCard",
                        title = stringResource(Res.string.state_export_data_ready),
                        body =
                            stringResource(
                                Res.string.label_export_data_record_count,
                                result.totalRows,
                            ) +
                                "\n" +
                                stringResource(Res.string.body_export_data_ready_detail),
                        onDismiss = { viewModel.onEvent(DataExportEvent.DismissResult) },
                    )
                is DataExportResult.Error ->
                    ResultCard(
                        container = MaterialTheme.colorScheme.errorContainer,
                        onContainer = MaterialTheme.colorScheme.onErrorContainer,
                        testTag = "dataExportErrorCard",
                        title = result.message.localized(),
                        body = null,
                        onDismiss = { viewModel.onEvent(DataExportEvent.DismissResult) },
                    )
                null -> Unit
            }

            Button(
                onClick = { viewModel.onEvent(DataExportEvent.Export) },
                enabled = !state.isExporting,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("exportDataButton"),
            ) {
                if (state.isExporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = stringResource(Res.string.state_export_data_in_progress),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                } else {
                    Text(stringResource(Res.string.action_export_data))
                }
            }
        }
    }
}

@Composable
private fun ResultCard(
    container: androidx.compose.ui.graphics.Color,
    onContainer: androidx.compose.ui.graphics.Color,
    testTag: String,
    title: String,
    body: String?,
    onDismiss: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(testTag),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = onContainer,
            )
            if (body != null) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContainer,
                )
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dataExportDismissButton"),
                colors = ButtonDefaults.textButtonColors(contentColor = onContainer),
            ) {
                Text(stringResource(Res.string.action_close))
            }
        }
    }
}
