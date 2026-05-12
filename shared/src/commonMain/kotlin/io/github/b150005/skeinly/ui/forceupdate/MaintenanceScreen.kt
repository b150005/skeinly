package io.github.b150005.skeinly.ui.forceupdate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_maintenance_retry
import io.github.b150005.skeinly.generated.resources.body_maintenance_default
import io.github.b150005.skeinly.generated.resources.title_maintenance
import org.jetbrains.compose.resources.stringResource

/**
 * Pre-alpha A15 — full-screen maintenance-mode surface rendered when the
 * gate ([ForceUpdateGate]) detects `app_config.maintenance_mode_active = true`.
 *
 * UX intent: zero-distraction, single action ("Retry") that re-fetches the
 * server config. No "Continue anyway", no dismissal, no swipe-back. The
 * gate replaces the entire NavHost so even system back press stays on
 * this screen. Unlike [ForceUpdateScreen], there is NO store-deep-link CTA
 * — by definition the user's installed version is fine; the service itself
 * is down.
 *
 * Locale-aware message: the gate selects `customMessageEn` / `customMessageJa`
 * based on the device locale, falling back to a bundled default when both
 * are null (matches the same precedent as `ForceUpdateScreen`).
 *
 * Test tags: `maintenanceScreen` (root), `retryButton` (CTA). Maestro /
 * XCUITest assertions reference these — keep the tags stable across UI
 * iterations.
 */
@Composable
fun MaintenanceScreen(
    customMessage: String?,
    onRetryClick: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.testTag("maintenanceScreen"),
    ) { padding ->
        MaintenanceContent(
            customMessage = customMessage,
            onRetryClick = onRetryClick,
            contentPadding = padding,
        )
    }
}

@Composable
private fun MaintenanceContent(
    customMessage: String?,
    onRetryClick: () -> Unit,
    contentPadding: PaddingValues,
) {
    val message =
        customMessage?.takeIf { it.isNotBlank() }
            ?: stringResource(Res.string.body_maintenance_default)
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(Res.string.title_maintenance),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onRetryClick,
            modifier = Modifier.testTag("retryButton"),
        ) {
            Text(stringResource(Res.string.action_maintenance_retry))
        }
    }
}
