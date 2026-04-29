package io.github.b150005.knitnote.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.b150005.knitnote.domain.model.User
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_back
import io.github.b150005.knitnote.generated.resources.action_cancel
import io.github.b150005.knitnote.generated.resources.action_edit
import io.github.b150005.knitnote.generated.resources.action_save
import io.github.b150005.knitnote.generated.resources.label_avatar
import io.github.b150005.knitnote.generated.resources.label_bio
import io.github.b150005.knitnote.generated.resources.label_display_name
import io.github.b150005.knitnote.generated.resources.message_profile_updated
import io.github.b150005.knitnote.generated.resources.state_profile_load_failed
import io.github.b150005.knitnote.generated.resources.title_profile
import io.github.b150005.knitnote.ui.components.localized
import kotlinx.coroutines.flow.collect
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    viewModel: ProfileViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val profileUpdatedMessage = stringResource(Res.string.message_profile_updated)

    val errorText = state.error?.localized()

    LaunchedEffect(errorText) {
        errorText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(ProfileEvent.ClearError)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.saveSuccess.collect {
            snackbarHostState.showSnackbar(profileUpdatedMessage)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.title_profile)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("backButton")) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back),
                        )
                    }
                },
                actions = {
                    if (!state.isEditing && state.user != null) {
                        IconButton(
                            onClick = { viewModel.onEvent(ProfileEvent.StartEditing) },
                            modifier = Modifier.testTag("editButton"),
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(Res.string.action_edit),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val user = state.user
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .testTag("profileScreen"),
        ) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                user == null -> {
                    Text(
                        text = stringResource(Res.string.state_profile_load_failed),
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                state.isEditing -> {
                    EditProfileContent(
                        state = state,
                        onEvent = viewModel::onEvent,
                    )
                }
                else -> {
                    ViewProfileContent(user = user)
                }
            }
        }
    }
}

@Composable
private fun ProfileAvatar() {
    Surface(
        modifier = Modifier.size(96.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = stringResource(Res.string.label_avatar),
            modifier = Modifier.padding(24.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun ViewProfileContent(user: User) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ProfileAvatar()

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = user.displayName,
            style = MaterialTheme.typography.headlineSmall,
        )

        if (user.bio != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = user.bio,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EditProfileContent(
    state: ProfileState,
    onEvent: (ProfileEvent) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ProfileAvatar()

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = state.editDisplayName,
            onValueChange = { onEvent(ProfileEvent.UpdateDisplayName(it)) },
            label = { Text(stringResource(Res.string.label_display_name)) },
            singleLine = true,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("displayNameField"),
            enabled = !state.isSaving,
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = state.editBio,
            onValueChange = { onEvent(ProfileEvent.UpdateBio(it)) },
            label = { Text(stringResource(Res.string.label_bio)) },
            maxLines = 4,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("bioField"),
            enabled = !state.isSaving,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = { onEvent(ProfileEvent.CancelEditing) },
                enabled = !state.isSaving,
                modifier = Modifier.testTag("cancelButton"),
            ) {
                Text(stringResource(Res.string.action_cancel))
            }
            TextButton(
                onClick = { onEvent(ProfileEvent.SaveProfile) },
                enabled = !state.isSaving && state.editDisplayName.isNotBlank(),
                modifier = Modifier.testTag("saveButton"),
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                } else {
                    Text(stringResource(Res.string.action_save))
                }
            }
        }
    }
}
