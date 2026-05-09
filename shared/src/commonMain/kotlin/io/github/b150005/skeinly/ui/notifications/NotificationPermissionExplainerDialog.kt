package io.github.b150005.skeinly.ui.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_enable_notifications
import io.github.b150005.skeinly.generated.resources.action_not_now_notifications
import io.github.b150005.skeinly.generated.resources.body_notifications_explainer
import io.github.b150005.skeinly.generated.resources.title_notifications_explainer
import org.jetbrains.compose.resources.stringResource

/**
 * Phase 24.2c (ADR-017 §3.6) — pre-permission explainer dialog. Surfaces
 * before the OS prompt fires so users understand WHY the app wants
 * notification permission ("Stay in the loop — get notified when
 * collaborators..."). Tapping "Enable" closes this dialog and triggers
 * the OS-level prompt; tapping "Not now" closes the dialog AND records a
 * dismissal in [io.github.b150005.skeinly.notifications.NotificationPermissionPrompter]
 * so the dialog does not re-surface on subsequent triggers.
 *
 * **Why not show the OS prompt directly:** industry data (Apple HIG /
 * Material Design / Localytics studies) reports 50–80% denial rates
 * when the permission ask is decontextualized. The deferred-prompt
 * pattern preserves the user's later opt-in path even after they say
 * "Not now" — they can re-enable from Settings without polluting the
 * OS denial state.
 *
 * @param isVisible bound to [NotificationPermissionState.isExplainerVisible].
 * @param isRequestingPermission bound to
 *   [NotificationPermissionState.isRequestingPermission]; disables both
 *   buttons + shows a small spinner on the Enable button while the OS
 *   prompt round-trip is in flight.
 * @param onAccept dispatches [NotificationPermissionEvent.UserAcceptedExplainer].
 * @param onDismiss dispatches [NotificationPermissionEvent.UserDismissedExplainer].
 */
@Composable
fun NotificationPermissionExplainerDialog(
    isVisible: Boolean,
    isRequestingPermission: Boolean,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!isVisible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("notificationPermissionExplainerDialog"),
        title = { Text(stringResource(Res.string.title_notifications_explainer)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.body_notifications_explainer))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onAccept,
                enabled = !isRequestingPermission,
                modifier = Modifier.testTag("enableNotificationsButton"),
            ) {
                if (isRequestingPermission) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.height(20.dp),
                    )
                } else {
                    Text(stringResource(Res.string.action_enable_notifications))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isRequestingPermission,
                modifier = Modifier.testTag("notNowNotificationsButton"),
            ) {
                Text(stringResource(Res.string.action_not_now_notifications))
            }
        },
    )
}
