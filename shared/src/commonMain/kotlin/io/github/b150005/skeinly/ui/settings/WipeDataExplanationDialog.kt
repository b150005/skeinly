package io.github.b150005.skeinly.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_wipe_data_continue
import io.github.b150005.skeinly.generated.resources.action_wipe_data_keep
import io.github.b150005.skeinly.generated.resources.body_wipe_data_deleted_list
import io.github.b150005.skeinly.generated.resources.body_wipe_data_preserved_list
import io.github.b150005.skeinly.generated.resources.subtitle_wipe_data_explanation_deleted
import io.github.b150005.skeinly.generated.resources.subtitle_wipe_data_explanation_preserved
import io.github.b150005.skeinly.generated.resources.title_wipe_data_explanation
import org.jetbrains.compose.resources.stringResource

/**
 * Phase 27.2 (ADR-023 §UX) — preservation-matrix modal shown as Step 1
 * of the data-wipe flow. Renders an [AlertDialog] with a two-section
 * "Preserved | Deleted" enumeration in user-facing terms + two CTAs:
 *
 * - **Continue** (destructive accent) → dispatches [onContinue] which the
 *   host [WipeDataConfirmPhraseScreen] wires to
 *   [WipeDataEvent.Continue] (flips VM step to [WipeDataStep.PhraseEntry]).
 * - **Keep my data** (secondary) → dispatches [onKeep] which the host
 *   wires to `navController.popBackStack()` — bails out of the flow
 *   without touching the VM (the VM is discarded with the route).
 *
 * **Layout**: the matrix sits in a vertically-scrollable Column so the
 * dialog stays usable on small / split-screen phones where the
 * "Deleted" body string can wrap to 4-5 lines. The 0.95f width modifier
 * lands the dialog edge-to-edge on phones (`fillMaxWidth(0.95f)` via
 * the host AlertDialog `modifier`).
 */
@Composable
internal fun WipeDataExplanationDialog(
    onContinue: () -> Unit,
    onKeep: () -> Unit,
) {
    val scrollState = rememberScrollState()
    AlertDialog(
        modifier =
            Modifier
                .fillMaxWidth(0.95f)
                .testTag("wipeDataExplanationDialog"),
        onDismissRequest = onKeep,
        title = {
            Text(stringResource(Res.string.title_wipe_data_explanation))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MatrixSection(
                    titleRes = Res.string.subtitle_wipe_data_explanation_preserved,
                    bodyRes = Res.string.body_wipe_data_preserved_list,
                    testTagSuffix = "Preserved",
                )
                MatrixSection(
                    titleRes = Res.string.subtitle_wipe_data_explanation_deleted,
                    bodyRes = Res.string.body_wipe_data_deleted_list,
                    testTagSuffix = "Deleted",
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onContinue,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                modifier = Modifier.testTag("wipeDataContinueButton"),
            ) {
                Text(stringResource(Res.string.action_wipe_data_continue))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onKeep,
                modifier = Modifier.testTag("wipeDataKeepButton"),
            ) {
                Text(stringResource(Res.string.action_wipe_data_keep))
            }
        },
    )
}

@Composable
private fun MatrixSection(
    titleRes: org.jetbrains.compose.resources.StringResource,
    bodyRes: org.jetbrains.compose.resources.StringResource,
    testTagSuffix: String,
) {
    Column(
        modifier = Modifier.testTag("wipeDataMatrixSection$testTagSuffix"),
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
