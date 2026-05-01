package io.github.b150005.skeinly.ui.bugreport

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_back
import io.github.b150005.skeinly.generated.resources.action_cancel
import io.github.b150005.skeinly.generated.resources.action_submit_bug_report
import io.github.b150005.skeinly.generated.resources.body_bug_report_includes_actions
import io.github.b150005.skeinly.generated.resources.hint_bug_description_placeholder
import io.github.b150005.skeinly.generated.resources.label_bug_description
import io.github.b150005.skeinly.generated.resources.label_bug_report_preview_body
import io.github.b150005.skeinly.generated.resources.title_bug_report_preview
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Phase 39.5 (ADR-015 §3, §6) — bug-report preview + submit.
 *
 * Reachable from:
 * - Settings → Beta → "Send Feedback" (Phase 39.4 wired the callback to a
 *   no-op stub; this screen is the destination).
 * - Android 3-finger long-press at `MainActivity.dispatchTouchEvent`.
 * - iOS shake gesture via `UIWindow.motionEnded` extension (see
 *   `iosApp/iosApp/Core/Bridging/ShakeDetection.swift`).
 *
 * UI contract:
 * - Description TextField (multi-line) is the only editable field; the
 *   diagnostic body underneath is read-only and re-renders on every
 *   description edit so the user sees what will be submitted.
 * - "Submit on GitHub" button hands off to [BugSubmissionLauncher]; the
 *   GitHub Issue form opens in the system browser with title + body
 *   prefilled. The user can still edit any field on the GitHub side
 *   before tapping Submit there.
 * - Cancel pops back via [onCancel] without surfacing the report.
 *
 * testTag landmarks: `bugReportPreviewScreen` (root),
 * `bugDescriptionField`, `bugReportPreviewBody`, `submitBugReportButton`,
 * `cancelBugReportButton`.
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

            // Disclosure surfaces the data-attachment contract before the
            // user types anything — closes the privacy-policy loop the
            // 39.2 update opened.
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
            )

            Spacer(Modifier.height(16.dp))

            HorizontalDivider()

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(Res.string.label_bug_report_preview_body),
                style = MaterialTheme.typography.titleSmall,
            )

            Spacer(Modifier.height(8.dp))

            // The body is read-only; SelectionContainer lets a curious
            // tester copy individual lines (e.g. the device-context block)
            // into another tool without forcing them through the GitHub
            // hand-off. Read-only `Text` (not OutlinedTextField) keeps the
            // styling distinct from the editable description above so the
            // user understands the boundary between "what I write" and
            // "what gets attached automatically".
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

            Box(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = { viewModel.onEvent(BugReportPreviewEvent.Submit) },
                    enabled = !state.isSubmitting,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("submitBugReportButton"),
                ) {
                    Text(stringResource(Res.string.action_submit_bug_report))
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
