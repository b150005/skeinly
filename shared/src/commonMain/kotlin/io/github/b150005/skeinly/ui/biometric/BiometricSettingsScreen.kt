package io.github.b150005.skeinly.ui.biometric

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.biometric.BiometricAvailability
import io.github.b150005.skeinly.data.preferences.ThresholdChoice
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_back
import io.github.b150005.skeinly.generated.resources.body_biometric_settings_explanation
import io.github.b150005.skeinly.generated.resources.label_biometric_threshold_picker
import io.github.b150005.skeinly.generated.resources.state_biometric_unavailable_no_hw
import io.github.b150005.skeinly.generated.resources.state_biometric_unavailable_not_enrolled
import io.github.b150005.skeinly.generated.resources.title_biometric_settings
import io.github.b150005.skeinly.generated.resources.value_biometric_threshold_15m
import io.github.b150005.skeinly.generated.resources.value_biometric_threshold_1h
import io.github.b150005.skeinly.generated.resources.value_biometric_threshold_1m
import io.github.b150005.skeinly.generated.resources.value_biometric_threshold_5m
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Phase 26.6 (ADR-022 §6.5) — Settings → Security → Biometric
 * authentication. Toggle ON/OFF + threshold picker (1m / 5m / 15m / 1h).
 *
 * Rendered as a standalone screen pushed onto the navigation stack
 * from Settings → Security. State observation is the standard
 * `MutableStateFlow` -> `collectAsState` pattern; the picker
 * RadioButton state binds to [BiometricSettingsState.threshold] and
 * fires [BiometricSettingsEvent.SelectThreshold] on each radio tap.
 *
 * Availability gate: when the OS reports the device cannot satisfy
 * biometric/PIN ([BiometricAvailability.NoHardware] /
 * [BiometricAvailability.NotEnrolled]), the Switch disables + an
 * unavailable-status row renders below. The Settings → Security
 * entry-point screen still renders this row but the ListItem CTA
 * routes here to expose the OS-state copy (users without
 * biometric/PIN see the explanation rather than a silently-disabled
 * row).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BiometricSettingsScreen(
    onBack: () -> Unit,
    viewModel: BiometricSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        modifier = Modifier.testTag("biometricSettingsScreen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.title_biometric_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            // Master toggle
            ListItem(
                headlineContent = {
                    Text(stringResource(Res.string.title_biometric_settings))
                },
                supportingContent = {
                    Text(stringResource(Res.string.body_biometric_settings_explanation))
                },
                trailingContent = {
                    Switch(
                        checked = state.enabled,
                        enabled = state.canToggle,
                        onCheckedChange = { value ->
                            viewModel.onEvent(BiometricSettingsEvent.ToggleEnabled(value))
                        },
                        modifier = Modifier.testTag("biometricToggle"),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // Unavailable status — surfaces when the OS reports the device
            // cannot satisfy the prompt. Two distinct messages so the user
            // knows whether to enroll biometric (NotEnrolled) or accept
            // that the feature is hardware-gated (NoHardware / Lockout).
            when (state.availability) {
                BiometricAvailability.NoHardware,
                BiometricAvailability.Lockout,
                -> {
                    UnavailableNote(text = stringResource(Res.string.state_biometric_unavailable_no_hw))
                }
                BiometricAvailability.NotEnrolled -> {
                    UnavailableNote(text = stringResource(Res.string.state_biometric_unavailable_not_enrolled))
                }
                BiometricAvailability.Available -> Unit
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // Threshold picker
            Text(
                text = stringResource(Res.string.label_biometric_threshold_picker),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            ThresholdRow(state, ThresholdChoice.OneMinute, Res.string.value_biometric_threshold_1m, viewModel)
            ThresholdRow(state, ThresholdChoice.FiveMinutes, Res.string.value_biometric_threshold_5m, viewModel)
            ThresholdRow(state, ThresholdChoice.FifteenMinutes, Res.string.value_biometric_threshold_15m, viewModel)
            ThresholdRow(state, ThresholdChoice.OneHour, Res.string.value_biometric_threshold_1h, viewModel)
        }
    }
}

@Composable
private fun ThresholdRow(
    state: BiometricSettingsState,
    choice: ThresholdChoice,
    label: StringResource,
    viewModel: BiometricSettingsViewModel,
) {
    val selected = state.threshold == choice
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.RadioButton,
                    enabled = state.enabled && state.canToggle,
                ) {
                    viewModel.onEvent(BiometricSettingsEvent.SelectThreshold(choice))
                }.padding(vertical = 8.dp)
                .testTag("biometricThreshold_${choice.name}"),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = state.enabled && state.canToggle,
        )
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun UnavailableNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, start = 16.dp),
    )
}
