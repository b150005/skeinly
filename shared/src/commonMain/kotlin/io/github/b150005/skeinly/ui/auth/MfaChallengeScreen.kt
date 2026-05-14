package io.github.b150005.skeinly.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_mfa_back_to_totp
import io.github.b150005.skeinly.generated.resources.action_mfa_use_recovery_code
import io.github.b150005.skeinly.generated.resources.action_mfa_verify
import io.github.b150005.skeinly.generated.resources.body_mfa_challenge_prompt
import io.github.b150005.skeinly.generated.resources.error_mfa_invalid_code
import io.github.b150005.skeinly.generated.resources.error_mfa_invalid_recovery_code
import io.github.b150005.skeinly.generated.resources.error_mfa_locked
import io.github.b150005.skeinly.generated.resources.label_mfa_recovery_code_input
import io.github.b150005.skeinly.generated.resources.title_mfa_challenge
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Phase 26.5 (ADR-022 §6.4) — post-password TOTP challenge gate.
 * Routed to by NavGraph when [io.github.b150005.skeinly.domain.model.AuthState.MfaChallengeRequired]
 * is observed. On verify-success the session JWT carries AAL2 →
 * `observeAuthState` re-emits plain Authenticated → NavGraph routes
 * off this screen automatically (the LaunchedEffect on completed
 * here also forwards as a backup for tests that don't run NavGraph).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MfaChallengeScreen(
    onCompleted: () -> Unit,
    viewModel: MfaChallengeViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.completed) {
        if (state.completed) onCompleted()
    }

    Scaffold(
        modifier = Modifier.testTag("mfaChallengeScreen"),
        topBar = {
            TopAppBar(title = { Text(stringResource(Res.string.title_mfa_challenge)) })
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(Res.string.body_mfa_challenge_prompt),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))

            val isRecoveryPhase = state.phase == MfaChallengePhase.EnterRecoveryCode
            OutlinedTextField(
                value = state.codeInput,
                onValueChange = { viewModel.onEvent(MfaChallengeEvent.UpdateCode(it)) },
                modifier = Modifier.fillMaxWidth().testTag("mfaChallengeCodeInput"),
                label =
                    if (isRecoveryPhase) {
                        { Text(stringResource(Res.string.label_mfa_recovery_code_input)) }
                    } else {
                        null
                    },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType =
                            if (isRecoveryPhase) {
                                KeyboardType.Ascii
                            } else {
                                KeyboardType.NumberPassword
                            },
                        imeAction = ImeAction.Done,
                    ),
                singleLine = true,
                enabled = !state.isSubmitting && state.error != MfaChallengeError.Locked,
                isError =
                    state.error is MfaChallengeError.InvalidCode ||
                        state.error is MfaChallengeError.InvalidRecoveryCode,
            )

            val errorText =
                when (state.error) {
                    MfaChallengeError.InvalidCode ->
                        stringResource(Res.string.error_mfa_invalid_code)
                    MfaChallengeError.InvalidRecoveryCode ->
                        stringResource(Res.string.error_mfa_invalid_recovery_code)
                    MfaChallengeError.Locked ->
                        stringResource(Res.string.error_mfa_locked)
                    null -> null
                    MfaChallengeError.Generic ->
                        stringResource(Res.string.error_mfa_invalid_recovery_code)
                }
            if (errorText != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    viewModel.onEvent(
                        if (isRecoveryPhase) {
                            MfaChallengeEvent.SubmitRecoveryCode
                        } else {
                            MfaChallengeEvent.SubmitCode
                        },
                    )
                },
                enabled =
                    !state.isSubmitting &&
                        state.error != MfaChallengeError.Locked &&
                        state.codeInput.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("mfaChallengeVerifyButton"),
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Text(stringResource(Res.string.action_mfa_verify))
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = {
                    viewModel.onEvent(
                        if (isRecoveryPhase) {
                            MfaChallengeEvent.SwitchToTotp
                        } else {
                            MfaChallengeEvent.SwitchToRecoveryCode
                        },
                    )
                },
                modifier = Modifier.testTag("mfaChallengeToggleRecoveryButton"),
            ) {
                Text(
                    text =
                        stringResource(
                            if (isRecoveryPhase) {
                                Res.string.action_mfa_back_to_totp
                            } else {
                                Res.string.action_mfa_use_recovery_code
                            },
                        ),
                )
            }
        }
    }
}
