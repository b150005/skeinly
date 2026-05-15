package io.github.b150005.skeinly.ui.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.domain.model.MAX_UGC_REASON_LENGTH
import io.github.b150005.skeinly.domain.model.UgcReportCategory
import io.github.b150005.skeinly.domain.model.UgcTargetType
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_cancel
import io.github.b150005.skeinly.generated.resources.action_report_submit
import io.github.b150005.skeinly.generated.resources.body_report_content_explanation
import io.github.b150005.skeinly.generated.resources.hint_report_detail
import io.github.b150005.skeinly.generated.resources.label_report_category
import io.github.b150005.skeinly.generated.resources.label_report_detail
import io.github.b150005.skeinly.generated.resources.state_report_submitting
import io.github.b150005.skeinly.generated.resources.title_report_content
import io.github.b150005.skeinly.ui.components.localized
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Phase 39 (ADR-021 §D4) — the "Report content" modal. Reusable across
 * every UGC surface: Discovery pattern cards
 * (`target_type = pattern`), Suggestion threads (`suggestion`),
 * comments (`comment` / `suggestion_comment`).
 *
 * Mounts a fresh [UgcReportViewModel] keyed on [targetType] + [targetId]
 * via Koin `parametersOf` (same precedent as
 * [io.github.b150005.skeinly.ui.settings.WipeDataConfirmPhraseScreen]).
 * On a successful submit the VM emits [UgcReportNavEvent.Submitted];
 * this composable forwards it as [onSubmitted] so the caller can
 * dismiss + flash a confirmation snackbar in its own scaffold. Failure
 * stays inline (the user keeps their typed reason and can retry).
 *
 * [onDismiss] fires on Cancel / scrim tap with no report sent.
 */
@Composable
fun ReportContentDialog(
    targetType: UgcTargetType,
    targetId: String,
    onSubmitted: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: UgcReportViewModel =
        koinViewModel(
            key = "ugcReport-${targetType.wireValue}-$targetId",
            parameters = { parametersOf(targetType, targetId) },
        ),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            when (event) {
                UgcReportNavEvent.Submitted -> onSubmitted()
            }
        }
    }

    val scrollState = rememberScrollState()
    AlertDialog(
        modifier =
            Modifier
                .fillMaxWidth(0.95f)
                .testTag("ugcReportDialog"),
        onDismissRequest = { if (!state.isSubmitting) onDismiss() },
        title = { Text(stringResource(Res.string.title_report_content)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(Res.string.body_report_content_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(Res.string.label_report_category),
                    style = MaterialTheme.typography.titleSmall,
                )
                UgcReportCategory.entries.forEach { category ->
                    val selected = state.category == category
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    enabled = !state.isSubmitting,
                                    onClick = {
                                        viewModel.onEvent(UgcReportEvent.SelectCategory(category))
                                    },
                                ).testTag("ugcReportCategory_${category.wireValue}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = null,
                            enabled = !state.isSubmitting,
                        )
                        Text(
                            text = category.localizedLabel(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = state.reason,
                    onValueChange = { viewModel.onEvent(UgcReportEvent.UpdateReason(it)) },
                    label = { Text(stringResource(Res.string.label_report_detail)) },
                    placeholder = { Text(stringResource(Res.string.hint_report_detail)) },
                    enabled = !state.isSubmitting,
                    isError = state.reason.length > MAX_UGC_REASON_LENGTH,
                    supportingText = {
                        Text("${state.reason.length} / $MAX_UGC_REASON_LENGTH")
                    },
                    minLines = 3,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("ugcReportReasonField"),
                )
                state.error?.let { err ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = err.localized(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("ugcReportError"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.onEvent(UgcReportEvent.Submit) },
                enabled = state.submitEnabled(),
                modifier = Modifier.testTag("ugcReportSubmitButton"),
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp).width(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.state_report_submitting))
                } else {
                    Text(stringResource(Res.string.action_report_submit))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !state.isSubmitting,
                modifier = Modifier.testTag("ugcReportCancelButton"),
            ) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}
