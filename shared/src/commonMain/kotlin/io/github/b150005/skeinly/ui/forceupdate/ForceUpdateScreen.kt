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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_update_now
import io.github.b150005.skeinly.generated.resources.body_force_update_default
import io.github.b150005.skeinly.generated.resources.title_force_update
import org.jetbrains.compose.resources.stringResource

/**
 * Phase 39 (W4 / 2026-05-11) — full-screen force-update surface
 * rendered when the gate ([ForceUpdateGate]) detects the installed
 * version is below `app_config.min_required_version_*`.
 *
 * UX intent: zero-distraction, single action ("Update now") that opens
 * the platform store. No "Later" button, no dismissal, no swipe-back.
 * The gate replaces the entire NavHost so even system back press
 * stays on this screen.
 *
 * Locale-aware message: the gate selects `customMessageEn` /
 * `customMessageJa` based on the device locale (English/Japanese
 * being the alpha-rubric languages), falling back to a bundled
 * default copy when both are null.
 *
 * Test tags: `forceUpdateScreen` (root), `updateNowButton` (CTA).
 * Maestro / XCUITest assertions reference these — keep the tags
 * stable across UI iterations.
 */
@Composable
fun ForceUpdateScreen(
    customMessage: String?,
    onUpdateClick: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.testTag("forceUpdateScreen"),
    ) { padding ->
        ForceUpdateContent(
            customMessage = customMessage,
            onUpdateClick = onUpdateClick,
            contentPadding = padding,
        )
    }
}

@Composable
private fun ForceUpdateContent(
    customMessage: String?,
    onUpdateClick: () -> Unit,
    contentPadding: PaddingValues,
) {
    val message =
        customMessage?.takeIf { it.isNotBlank() }
            ?: stringResource(Res.string.body_force_update_default)
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
            text = stringResource(Res.string.title_force_update),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onUpdateClick,
            modifier = Modifier.testTag("updateNowButton"),
        ) {
            Text(stringResource(Res.string.action_update_now))
        }
    }
}
