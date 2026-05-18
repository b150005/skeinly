package io.github.b150005.skeinly.ui.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_back
import io.github.b150005.skeinly.generated.resources.action_mfa_advance_to_confirm
import io.github.b150005.skeinly.generated.resources.action_mfa_recovery_code_saved
import io.github.b150005.skeinly.generated.resources.action_mfa_verify
import io.github.b150005.skeinly.generated.resources.body_mfa_confirm_code
import io.github.b150005.skeinly.generated.resources.body_mfa_enroll_scan_qr
import io.github.b150005.skeinly.generated.resources.body_mfa_recovery_code_warning
import io.github.b150005.skeinly.generated.resources.error_mfa_enroll_failed
import io.github.b150005.skeinly.generated.resources.error_mfa_invalid_code
import io.github.b150005.skeinly.generated.resources.label_mfa_manual_secret
import io.github.b150005.skeinly.generated.resources.title_mfa_confirm_code
import io.github.b150005.skeinly.generated.resources.title_mfa_enroll
import io.github.b150005.skeinly.generated.resources.title_mfa_recovery_code
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Phase 26.5 (ADR-022 §6.4) — TOTP enrollment flow.
 *
 * Three sub-screens driven by [MfaEnrollmentUiState.phase]:
 *   - PairingQr — QR code preview + manual entry secret + "I've added it"
 *     CTA. Tap kicks the user to the confirm step (no server round-trip
 *     yet; enrollment row already exists from `start()` on entry).
 *   - ConfirmCode — 6-digit TOTP input + Verify button. Calls
 *     `auth.mfa.createChallenge + verifyChallenge` server-side.
 *   - RecoveryCodeDisplay — single-use recovery code shown ONCE with
 *     screenshot-recommend warning + tap-to-copy. Dismissing exits the
 *     screen.
 *
 * Alpha scope: QR code rendering is deferred (the secret/uri text is
 * surfaced as a monospace string for manual entry into the
 * authenticator app). Production rendering needs a multiplatform QR
 * library — landed as a follow-up Tech Debt entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MfaEnrollmentScreen(
    onBack: () -> Unit,
    onCompleted: () -> Unit,
    viewModel: MfaEnrollmentViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.onEvent(MfaEnrollmentEvent.Start)
    }

    LaunchedEffect(state.completed) {
        if (state.completed) onCompleted()
    }

    Scaffold(
        modifier = Modifier.testTag("mfaEnrollmentScreen"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text =
                            when (state.phase) {
                                MfaEnrollmentPhase.PairingQr ->
                                    stringResource(Res.string.title_mfa_enroll)
                                MfaEnrollmentPhase.ConfirmCode ->
                                    stringResource(Res.string.title_mfa_confirm_code)
                                MfaEnrollmentPhase.RecoveryCodeDisplay ->
                                    stringResource(Res.string.title_mfa_recovery_code)
                            },
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
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.enrollment == null && state.isSubmitting) {
                Spacer(Modifier.height(48.dp))
                CircularProgressIndicator()
            } else if (state.enrollment == null) {
                // Enroll failed before we had an envelope. Surface the
                // error inline so the user knows; back-tap closes.
                Text(
                    text = stringResource(Res.string.error_mfa_enroll_failed),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                when (state.phase) {
                    MfaEnrollmentPhase.PairingQr ->
                        PairingContent(
                            state = state,
                            onAdvance = { viewModel.onEvent(MfaEnrollmentEvent.AdvanceToConfirm) },
                        )
                    MfaEnrollmentPhase.ConfirmCode ->
                        ConfirmContent(
                            state = state,
                            onUpdateCode = {
                                viewModel.onEvent(MfaEnrollmentEvent.UpdateCode(it))
                            },
                            onSubmit = { viewModel.onEvent(MfaEnrollmentEvent.SubmitCode) },
                        )
                    MfaEnrollmentPhase.RecoveryCodeDisplay ->
                        RecoveryCodeContent(
                            recoveryCode = state.enrollment?.recoveryCode.orEmpty(),
                            onDismiss = {
                                viewModel.onEvent(MfaEnrollmentEvent.DismissRecoveryCode)
                            },
                        )
                }
            }
        }
    }
}

@Composable
private fun PairingContent(
    state: MfaEnrollmentUiState,
    onAdvance: () -> Unit,
) {
    val enrollment = state.enrollment ?: return
    Text(
        text = stringResource(Res.string.body_mfa_enroll_scan_qr),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(Res.string.label_mfa_manual_secret),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = enrollment.secret,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("mfaManualSecret"),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = enrollment.otpAuthUri,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("mfaOtpAuthUri"),
            )
        }
    }
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onAdvance,
        modifier = Modifier.fillMaxWidth().testTag("mfaAdvanceToConfirmButton"),
    ) {
        Text(stringResource(Res.string.action_mfa_advance_to_confirm))
    }
}

@Composable
private fun ConfirmContent(
    state: MfaEnrollmentUiState,
    onUpdateCode: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Text(
        text = stringResource(Res.string.body_mfa_confirm_code),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = state.codeInput,
        onValueChange = onUpdateCode,
        modifier = Modifier.fillMaxWidth().testTag("mfaCodeInput"),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            ),
        singleLine = true,
        enabled = !state.isSubmitting,
        isError = state.error == MfaEnrollmentError.InvalidCode,
    )
    if (state.error == MfaEnrollmentError.InvalidCode) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(Res.string.error_mfa_invalid_code),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onSubmit,
        enabled = !state.isSubmitting && state.codeInput.length == 6,
        modifier = Modifier.fillMaxWidth().testTag("mfaVerifyButton"),
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
}

@Composable
private fun RecoveryCodeContent(
    recoveryCode: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Text(
        text = stringResource(Res.string.body_mfa_recovery_code_warning),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable {
                    clipboard.setText(AnnotatedString(recoveryCode))
                }.testTag("mfaRecoveryCodeBox"),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
        ) {
            Text(
                text = recoveryCode,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
            )
        }
    }
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth().testTag("mfaRecoveryCodeSavedButton"),
        // 5-second disable hold per ADR §6.4 ("CTA disabled for 5 seconds
        // to force the user to actually look at the code") — deferred to a
        // follow-up; the Box-clickable-to-copy gesture is the
        // alpha-acceptable engagement signal.
    ) {
        Text(stringResource(Res.string.action_mfa_recovery_code_saved))
    }
}
