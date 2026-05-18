package io.github.b150005.skeinly.ui.settings

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.data.wipe.WipeCompletionNotifier
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_back
import io.github.b150005.skeinly.generated.resources.action_wipe_data_submit
import io.github.b150005.skeinly.generated.resources.body_wipe_data_confirm_phrase_helper
import io.github.b150005.skeinly.generated.resources.phrase_wipe_data_confirm
import io.github.b150005.skeinly.generated.resources.title_wipe_data_confirm_phrase
import io.github.b150005.skeinly.ui.components.LiveSnackbarHost
import io.github.b150005.skeinly.ui.components.localized
import io.github.b150005.skeinly.ui.platform.SystemBackHandler
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Phase 27.2 (ADR-023 §UX) — host screen for the data-wipe flow.
 *
 * Mounts the [WipeDataViewModel] with the locale-active required
 * phrase captured at first composition via
 * [stringResource(Res.string.phrase_wipe_data_confirm)] and renders
 * one of two surfaces based on [WipeDataState.step]:
 *
 * - [WipeDataStep.Modal] → [WipeDataExplanationDialog] over a blank
 *   Scaffold (the dialog itself is the user-visible surface; the
 *   Scaffold is a structural backdrop so back-navigation through
 *   "Keep my data" / system back lands cleanly on Settings).
 * - [WipeDataStep.PhraseEntry] → full-screen phrase-typing form with
 *   submit button gated on [WipeDataState.submitEnabled].
 *
 * **Nav contract**:
 *
 * - User taps "Keep my data" or system back at Modal: pop nav stack.
 * - User taps "Cancel" or system back at PhraseEntry: dispatch
 *   [WipeDataEvent.BackToModal] — flow returns to the modal.
 * - User submits and RPC succeeds: notify [WipeCompletionNotifier]
 *   (so PatternLibrary banner surfaces) + pop nav stack.
 *
 * **Locale invariant**: the required phrase is captured ONCE at
 * screen mount time and frozen for the VM's lifetime. The ADR §UX
 * explicitly defers mid-flow locale switching to post-beta.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WipeDataConfirmPhraseScreen(
    onCancel: () -> Unit,
    onWipeCompleted: () -> Unit,
    notifier: WipeCompletionNotifier = koinInject(),
) {
    // Resolve the locale-active phrase BEFORE the VM is created so
    // Koin's parametric factory receives the captured string. This
    // matches ADR-023 §UX: "the active locale at modal-open time
    // selects which phrase is required; mid-flow locale change is
    // not supported".
    val requiredPhrase = stringResource(Res.string.phrase_wipe_data_confirm)
    val viewModel: WipeDataViewModel =
        koinViewModel(
            key = "wipe-data-$requiredPhrase",
            parameters = { parametersOf(requiredPhrase) },
        )
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val errorText = state.error?.localized()

    LaunchedEffect(errorText) {
        errorText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(WipeDataEvent.ClearError)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.navEvents.collect { event ->
            when (event) {
                WipeDataNavEvent.WipeCompleted -> {
                    notifier.notify()
                    onWipeCompleted()
                }
            }
        }
    }

    // Translate system-back into the in-flow "back" semantic: at
    // PhraseEntry, system-back returns to Modal (matches the visible
    // Cancel button); at Modal, system-back exits the flow.
    SystemBackHandler(enabled = state.step == WipeDataStep.PhraseEntry) {
        viewModel.onEvent(WipeDataEvent.BackToModal)
    }

    Scaffold(
        modifier = Modifier.testTag("wipeDataConfirmPhraseScreen"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.title_wipe_data_confirm_phrase),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            when (state.step) {
                                WipeDataStep.Modal -> onCancel()
                                WipeDataStep.PhraseEntry ->
                                    viewModel.onEvent(WipeDataEvent.BackToModal)
                            }
                        },
                        modifier = Modifier.testTag("wipeDataBackButton"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { LiveSnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            when (state.step) {
                WipeDataStep.Modal ->
                    WipeDataExplanationDialog(
                        onContinue = { viewModel.onEvent(WipeDataEvent.Continue) },
                        onKeep = onCancel,
                    )
                WipeDataStep.PhraseEntry ->
                    PhraseEntryContent(
                        state = state,
                        requiredPhrase = requiredPhrase,
                        onPhraseChange = { viewModel.onEvent(WipeDataEvent.UpdatePhrase(it)) },
                        onSubmit = { viewModel.onEvent(WipeDataEvent.Submit) },
                    )
            }
        }
    }
}

@Composable
private fun PhraseEntryContent(
    state: WipeDataState,
    requiredPhrase: String,
    onPhraseChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 24.dp),
    ) {
        Text(
            text = stringResource(Res.string.body_wipe_data_confirm_phrase_helper),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = state.phraseInput,
            onValueChange = onPhraseChange,
            singleLine = true,
            enabled = !state.isSubmitting,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("wipeDataPhraseField"),
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onSubmit,
            enabled = state.submitEnabled(requiredPhrase),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("wipeDataSubmitButton"),
        ) {
            if (state.isSubmitting) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.height(20.dp),
                )
            } else {
                Text(stringResource(Res.string.action_wipe_data_submit))
            }
        }
    }
}
