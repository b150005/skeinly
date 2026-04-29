package io.github.b150005.knitnote.ui.sharedwithme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import io.github.b150005.knitnote.domain.model.Share
import io.github.b150005.knitnote.domain.model.SharePermission
import io.github.b150005.knitnote.domain.model.ShareStatus
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_accept
import io.github.b150005.knitnote.generated.resources.action_back
import io.github.b150005.knitnote.generated.resources.action_decline
import io.github.b150005.knitnote.generated.resources.label_from_user
import io.github.b150005.knitnote.generated.resources.label_permission_fork
import io.github.b150005.knitnote.generated.resources.label_permission_view
import io.github.b150005.knitnote.generated.resources.label_share_status_declined
import io.github.b150005.knitnote.generated.resources.label_share_status_pending
import io.github.b150005.knitnote.generated.resources.label_shared_on
import io.github.b150005.knitnote.generated.resources.label_someone
import io.github.b150005.knitnote.generated.resources.label_unknown_pattern
import io.github.b150005.knitnote.generated.resources.state_no_shares
import io.github.b150005.knitnote.generated.resources.state_no_shares_body
import io.github.b150005.knitnote.generated.resources.title_shared_with_me
import io.github.b150005.knitnote.ui.components.localized
import io.github.b150005.knitnote.ui.util.formatFull
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedWithMeScreen(
    onBack: () -> Unit,
    onShareClick: (shareId: String) -> Unit = {},
    viewModel: SharedWithMeViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val errorText = state.error?.localized()

    LaunchedEffect(errorText) {
        errorText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(SharedWithMeEvent.ClearError)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.title_shared_with_me)) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .testTag("sharedWithMeScreen"),
        ) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                state.shares.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(Res.string.state_no_shares),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(Res.string.state_no_shares_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.shares, key = { it.id }) { share ->
                            ShareListItem(
                                share = share,
                                patternTitle = state.patternTitles[share.patternId],
                                sharerName = state.sharers[share.fromUserId]?.displayName,
                                onClick = { onShareClick(share.id) },
                                onAccept = {
                                    viewModel.onEvent(SharedWithMeEvent.AcceptShare(share.id))
                                },
                                onDecline = {
                                    viewModel.onEvent(SharedWithMeEvent.DeclineShare(share.id))
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareListItem(
    share: Share,
    patternTitle: String?,
    sharerName: String?,
    onClick: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val dateText = share.sharedAt.formatFull()

    // Resolve sharer → localized "From {name}" prefix; falls back to `label_someone`
    // (reused from 33.1.7 ActivityFeed) when the user record is absent.
    val resolvedSharer = sharerName ?: stringResource(Res.string.label_someone)
    val fromText = stringResource(Res.string.label_from_user, resolvedSharer)
    val sharedOnText = stringResource(Res.string.label_shared_on, dateText)

    val permissionLabel =
        stringResource(
            when (share.permission) {
                SharePermission.VIEW -> Res.string.label_permission_view
                SharePermission.FORK -> Res.string.label_permission_fork
            },
        )

    val statusSuffix =
        when (share.status) {
            ShareStatus.PENDING ->
                " | " + stringResource(Res.string.label_share_status_pending)
            ShareStatus.ACCEPTED -> ""
            ShareStatus.DECLINED ->
                " | " + stringResource(Res.string.label_share_status_declined)
        }

    val resolvedTitle = patternTitle ?: stringResource(Res.string.label_unknown_pattern)

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = resolvedTitle,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = "$fromText | $sharedOnText | $permissionLabel$statusSuffix",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (share.status == ShareStatus.PENDING) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Button(
                            onClick = onAccept,
                        ) {
                            Text(stringResource(Res.string.action_accept))
                        }
                        OutlinedButton(
                            onClick = onDecline,
                        ) {
                            Text(stringResource(Res.string.action_decline))
                        }
                    }
                }
            }
        },
    )
}
