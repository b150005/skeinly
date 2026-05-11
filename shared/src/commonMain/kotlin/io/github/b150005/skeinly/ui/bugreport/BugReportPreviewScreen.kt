package io.github.b150005.skeinly.ui.bugreport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_back
import io.github.b150005.skeinly.generated.resources.action_bug_report_dismiss_result
import io.github.b150005.skeinly.generated.resources.action_cancel
import io.github.b150005.skeinly.generated.resources.action_submit_bug_report
import io.github.b150005.skeinly.generated.resources.body_bug_report_includes_actions
import io.github.b150005.skeinly.generated.resources.hint_bug_description_placeholder
import io.github.b150005.skeinly.generated.resources.label_bug_description
import io.github.b150005.skeinly.generated.resources.label_bug_report_preview_body
import io.github.b150005.skeinly.generated.resources.state_bug_report_error_config_missing
import io.github.b150005.skeinly.generated.resources.state_bug_report_error_offline
import io.github.b150005.skeinly.generated.resources.state_bug_report_error_rate_limited
import io.github.b150005.skeinly.generated.resources.state_bug_report_error_server
import io.github.b150005.skeinly.generated.resources.state_bug_report_error_unknown
import io.github.b150005.skeinly.generated.resources.state_bug_report_error_validation
import io.github.b150005.skeinly.generated.resources.state_bug_report_submitted
import io.github.b150005.skeinly.generated.resources.state_bug_report_submitting
import io.github.b150005.skeinly.generated.resources.title_bug_report_preview
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Phase 39 W5b (ADR-020) — bug-report preview + submit.
 *
 * Reachable from:
 * - Settings → Beta → "Send Feedback".
 * - Android 3-finger long-press at `MainActivity.dispatchTouchEvent`.
 * - iOS shake gesture via `UIWindow.motionEnded`.
 *
 * UI contract (W5b changes over Phase 39.5):
 * - "Send report" button (previously "Submit on GitHub") POSTs to the
 *   `submit-bug-report` Edge Function which creates the GitHub Issue
 *   server-side. No browser hand-off.
 * - Post-submit banner above the editor surfaces the result: Success
 *   banner with the GitHub Issue number ("#123"), or one of six
 *   typed error banners (offline / rate-limited / validation /
 *   config-missing / server / unknown). Dismiss button clears the
 *   banner so the user can edit and retry.
 * - Submit button shows a spinner + "Sending…" label while the HTTP
 *   round-trip is in flight; the underlying ViewModel guards against
 *   double-submit so multi-tap is harmless.
 *
 * testTag landmarks: `bugReportPreviewScreen` (root),
 * `bugDescriptionField`, `bugReportPreviewBody`, `submitBugReportButton`,
 * `cancelBugReportButton`, `bugReportResultBanner`,
 * `bugReportResultBannerDismiss`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BugReportPreviewScreen(
    onCancel: () -> Unit,
    viewModel: BugReportPreviewViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        modifier = Modifier.testTag("bugReportPreviewScreen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.title_bug_report_preview)) },
                navigationIcon = {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag("cancelBugReportButton"),
                    ) {
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
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))

            // Result banner — sits above the disclosure so a successful
            // submission's confirmation is immediately visible without
            // scrolling. Null state hides the banner entirely.
            state.submitResult?.let { result ->
                ResultBanner(
                    result = result,
                    onDismiss = { viewModel.onEvent(BugReportPreviewEvent.DismissResult) },
                )
                Spacer(Modifier.height(12.dp))
            }

            Text(
                text = stringResource(Res.string.body_bug_report_includes_actions),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = state.description,
                onValueChange = {
                    viewModel.onEvent(BugReportPreviewEvent.DescriptionChanged(it))
                },
                label = { Text(stringResource(Res.string.label_bug_description)) },
                placeholder = {
                    Text(stringResource(Res.string.hint_bug_description_placeholder))
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("bugDescriptionField"),
                minLines = 4,
                maxLines = 12,
                // Disable editing during the round-trip so a slow
                // network doesn't let the user type a different
                // description than the one being submitted.
                enabled = !state.isSubmitting,
            )

            Spacer(Modifier.height(16.dp))

            HorizontalDivider()

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(Res.string.label_bug_report_preview_body),
                style = MaterialTheme.typography.titleSmall,
            )

            Spacer(Modifier.height(8.dp))

            SelectionContainer {
                Text(
                    text = state.previewBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("bugReportPreviewBody"),
                )
            }

            Spacer(Modifier.height(24.dp))

            Box(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { viewModel.onEvent(BugReportPreviewEvent.Submit) },
                    // Disable the button on success — the user can still
                    // dismiss the banner to send another. Error states
                    // re-enable so retry is one tap.
                    enabled = !state.isSubmitting && state.submitResult !is SubmitResultState.Success,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("submitBugReportButton"),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Text(stringResource(Res.string.state_bug_report_submitting))
                        } else {
                            Text(stringResource(Res.string.action_submit_bug_report))
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.action_cancel))
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ResultBanner(
    result: SubmitResultState,
    onDismiss: () -> Unit,
) {
    val (text, containerColor, contentColor) =
        when (result) {
            is SubmitResultState.Success ->
                Triple(
                    "${stringResource(Res.string.state_bug_report_submitted)} #${result.issueNumber}",
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                )
            is SubmitResultState.Error -> {
                val errorKey =
                    when (result.kind) {
                        ErrorKind.OFFLINE -> Res.string.state_bug_report_error_offline
                        ErrorKind.RATE_LIMITED -> Res.string.state_bug_report_error_rate_limited
                        ErrorKind.VALIDATION_FAILED -> Res.string.state_bug_report_error_validation
                        ErrorKind.CONFIG_MISSING -> Res.string.state_bug_report_error_config_missing
                        ErrorKind.SERVER -> Res.string.state_bug_report_error_server
                        ErrorKind.UNKNOWN -> Res.string.state_bug_report_error_unknown
                    }
                Triple(
                    stringResource(errorKey),
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("bugReportResultBanner"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("bugReportResultBannerDismiss"),
                ) {
                    Text(stringResource(Res.string.action_bug_report_dismiss_result))
                }
            }
        }
    }
}
