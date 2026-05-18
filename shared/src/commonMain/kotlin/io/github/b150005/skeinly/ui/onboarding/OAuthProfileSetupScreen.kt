package io.github.b150005.skeinly.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.domain.model.OAuthOnboardingMetadata
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_choose_different_avatar
import io.github.b150005.skeinly.generated.resources.action_save_profile_setup
import io.github.b150005.skeinly.generated.resources.action_skip_profile_setup
import io.github.b150005.skeinly.generated.resources.action_use_oauth_avatar
import io.github.b150005.skeinly.generated.resources.body_change_avatar_in_profile_hint
import io.github.b150005.skeinly.generated.resources.body_oauth_profile_setup
import io.github.b150005.skeinly.generated.resources.label_oauth_profile_display_name
import io.github.b150005.skeinly.generated.resources.state_oauth_avatar_imported
import io.github.b150005.skeinly.generated.resources.state_oauth_avatar_importing
import io.github.b150005.skeinly.generated.resources.title_oauth_profile_avatar
import io.github.b150005.skeinly.generated.resources.title_oauth_profile_setup
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Phase 26.6 (ADR-022 §6.6) — post-OAuth profile setup gate.
 *
 * Mounted by [NavGraph] after the first Authenticated transition for a
 * user whose `profiles.display_name` is empty AND whose
 * [OAuthOnboardingMetadata.displayName] is non-null. The screen
 * pre-fills the name input + (when [OAuthOnboardingMetadata.pictureUrl]
 * is non-null) offers a one-tap avatar import tile.
 *
 * Two exit paths:
 *   - Save → commits displayName + (optionally) imported avatar to
 *     `profiles`, marks the gate preference complete, fires
 *     [OAuthProfileSetupNavEvent.Completed].
 *   - Skip → marks the gate preference complete without touching the
 *     profile row, fires the same nav event.
 *
 * Both paths route the caller to ProjectList via NavGraph's
 * `popUpTo(OAuthProfileSetup) { inclusive = true }` so the back stack
 * is clean.
 */
@Composable
fun OAuthProfileSetupScreen(
    metadata: OAuthOnboardingMetadata,
    onCompleted: () -> Unit,
    viewModel: OAuthProfileSetupViewModel =
        koinViewModel { parametersOf(metadata) },
) {
    val state by viewModel.state.collectAsState()

    // One-shot nav collector. Channel-backed flow so a previous Save
    // success doesn't re-fire after a process restart (Channel is
    // not replay-backed).
    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is OAuthProfileSetupNavEvent.Completed -> onCompleted()
            }
        }
    }

    Scaffold(
        modifier = Modifier.testTag("oauthProfileSetupScreen"),
        contentWindowInsets = WindowInsets.systemBars,
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = stringResource(Res.string.title_oauth_profile_setup),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(Res.string.body_oauth_profile_setup),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.displayName,
                onValueChange = { value ->
                    viewModel.onEvent(OAuthProfileSetupEvent.UpdateDisplayName(value))
                },
                label = { Text(stringResource(Res.string.label_oauth_profile_display_name)) },
                singleLine = true,
                enabled = !state.isSubmitting,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("oauthProfileDisplayNameField"),
            )

            // Avatar tile renders only when the OAuth provider exposed a
            // picture URL (Google). Apple + email/password sign-ins
            // skip this surface naturally — the pictureUrl is null.
            if (state.pictureUrl != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.title_oauth_profile_avatar),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    when {
                        state.isImportingAvatar -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier =
                                        Modifier
                                            .height(24.dp)
                                            .testTag("oauthAvatarImportingSpinner"),
                                )
                                Spacer(modifier = Modifier.fillMaxWidth(0.04f))
                                Text(stringResource(Res.string.state_oauth_avatar_importing))
                            }
                        }
                        state.avatarImported -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(Res.string.state_oauth_avatar_imported),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(modifier = Modifier.fillMaxWidth(0.05f))
                                TextButton(
                                    onClick = {
                                        viewModel.onEvent(
                                            OAuthProfileSetupEvent.ChooseDifferentAvatar,
                                        )
                                    },
                                    modifier = Modifier.testTag("oauthAvatarChooseDifferentButton"),
                                ) {
                                    Text(
                                        stringResource(Res.string.action_choose_different_avatar),
                                    )
                                }
                            }
                        }
                        else -> {
                            Button(
                                onClick = {
                                    viewModel.onEvent(OAuthProfileSetupEvent.UseOAuthAvatar)
                                },
                                enabled = !state.isSubmitting,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .testTag("oauthAvatarUseButton"),
                            ) {
                                Text(stringResource(Res.string.action_use_oauth_avatar))
                            }
                        }
                    }
                    // Phase 26.7 (Tech Debt resolution) — after "Choose
                    // different", point the user at the canonical photo-
                    // picker path (Settings → Profile). Photo picker
                    // integration in the setup screen itself is deferred
                    // because the existing UploadAvatar pipeline already
                    // covers arbitrary uploads.
                    if (state.chooseDifferentHintVisible) {
                        Text(
                            text = stringResource(Res.string.body_change_avatar_in_profile_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("oauthAvatarChangeProfileHint"),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.onEvent(OAuthProfileSetupEvent.Submit) },
                enabled = !state.isSubmitting && state.displayName.isNotBlank(),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("oauthProfileSaveButton"),
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.height(24.dp))
                } else {
                    Text(stringResource(Res.string.action_save_profile_setup))
                }
            }
            OutlinedButton(
                onClick = { viewModel.onEvent(OAuthProfileSetupEvent.Skip) },
                enabled = !state.isSubmitting,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("oauthProfileSkipButton"),
            ) {
                Text(stringResource(Res.string.action_skip_profile_setup))
            }
        }
    }
}
