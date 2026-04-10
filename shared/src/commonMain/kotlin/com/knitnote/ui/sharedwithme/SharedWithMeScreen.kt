package com.knitnote.ui.sharedwithme

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
import androidx.compose.ui.unit.dp
import com.knitnote.domain.model.Share
import com.knitnote.domain.model.ShareStatus
import com.knitnote.ui.util.formatFull
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

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(SharedWithMeEvent.ClearError)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shared With Me") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
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
                            text = "No shared projects yet",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "Projects shared with you will appear here",
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

    val statusText = when (share.status) {
        ShareStatus.PENDING -> " | Pending"
        ShareStatus.ACCEPTED -> ""
        ShareStatus.DECLINED -> " | Declined"
    }

    val fromText = sharerName?.let { "From $it | " } ?: ""

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = patternTitle ?: "Unknown Pattern",
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = "${fromText}Shared on $dateText | ${share.permission.name}$statusText",
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
                            Text("Accept")
                        }
                        OutlinedButton(
                            onClick = onDecline,
                        ) {
                            Text("Decline")
                        }
                    }
                }
            }
        },
    )
}
