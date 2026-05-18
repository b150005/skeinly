package io.github.b150005.skeinly.ui.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.domain.model.FriendInvite
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_back
import io.github.b150005.skeinly.generated.resources.action_cancel
import io.github.b150005.skeinly.generated.resources.action_connections_accept
import io.github.b150005.skeinly.generated.resources.action_connections_create_invite
import io.github.b150005.skeinly.generated.resources.action_connections_disconnect
import io.github.b150005.skeinly.generated.resources.action_connections_reject
import io.github.b150005.skeinly.generated.resources.action_friend_invite_add_by_code
import io.github.b150005.skeinly.generated.resources.body_connections_invite_explanation
import io.github.b150005.skeinly.generated.resources.dialog_connections_disconnect_body
import io.github.b150005.skeinly.generated.resources.dialog_connections_disconnect_title
import io.github.b150005.skeinly.generated.resources.state_connections_invite_expires_in_days
import io.github.b150005.skeinly.generated.resources.state_connections_no_friends
import io.github.b150005.skeinly.generated.resources.state_connections_no_invite
import io.github.b150005.skeinly.generated.resources.state_connections_no_pending
import io.github.b150005.skeinly.generated.resources.state_connections_request_from
import io.github.b150005.skeinly.generated.resources.state_connections_request_to
import io.github.b150005.skeinly.generated.resources.state_connections_unknown_user
import io.github.b150005.skeinly.generated.resources.tab_connections_friends
import io.github.b150005.skeinly.generated.resources.tab_connections_invite
import io.github.b150005.skeinly.generated.resources.tab_connections_pending
import io.github.b150005.skeinly.generated.resources.title_connections
import io.github.b150005.skeinly.ui.components.LiveSnackbarHost
import io.github.b150005.skeinly.ui.components.localized
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock

/**
 * Phase 25.3 (ADR-024 §(e)) — Settings → Privacy → Connections screen.
 *
 * Three-tab layout (Friends / Pending / Invite). The TabRow drives a
 * `when (state.activeTab)` switch over the body composables — no
 * Pager / HorizontalPager because the tab content is heterogeneous
 * (lists vs invite-card) and swipe-between-tabs would conflict with
 * the disconnect-row swipe affordance on Friends tab.
 *
 * **Disconnect confirmation**: tapping "Disconnect" on a friend row
 * surfaces an [AlertDialog] with the other user's display name in the
 * body. Confirms via [ConnectionsEvent.Disconnect]; dismisses without
 * dispatch on cancel / system back.
 *
 * **Per-row spinners**: row-level "in flight" state (Accept / Reject /
 * Disconnect) replaces the trailing button with a [CircularProgressIndicator]
 * — readers see immediately which row is mid-action while other rows
 * stay tap-able.
 *
 * **Empty states**: each tab renders a centered empty-state message
 * when its list is empty AND the screen is not loading. The Invite
 * tab's empty state is replaced by the create-invite card itself once
 * any invite exists.
 *
 * **Phase 25.4 sequencing**: this screen surfaces existing invites
 * (code-only display) and the "Create invite" button. The redemption
 * flow + Universal Link tap-handling lands in Phase 25.4. The "Share"
 * action via system share sheet is also deferred — Phase 25.3 ships
 * the visible code so testers can manually relay it for end-to-end
 * smoke testing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    onBack: () -> Unit,
    // Phase 25.4 (ADR-024 §Phase 25.4) — "Add by code" TopAppBar
    // action routes to FriendInviteConfirm in code mode. Default no-op
    // so test mounts / older NavGraph wiring stay valid.
    onAddByCode: () -> Unit = {},
    viewModel: ConnectionsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val errorText = state.error?.localized()

    LaunchedEffect(errorText) {
        errorText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(ConnectionsEvent.ClearError)
        }
    }

    var pendingDisconnect: PendingDisconnect? by remember { mutableStateOf(null) }

    Scaffold(
        modifier = Modifier.testTag("connectionsScreen"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.title_connections),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("connectionsBackButton")) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back),
                        )
                    }
                },
                actions = {
                    // Phase 25.4 — "Add by code" entry. Routes to the
                    // FriendInviteConfirm screen in code mode (the
                    // receiving side of an invite; distinct from the
                    // Invite tab which GENERATES codes).
                    IconButton(
                        onClick = onAddByCode,
                        modifier = Modifier.testTag("connectionsAddByCodeButton"),
                    ) {
                        Icon(
                            Icons.Filled.PersonAdd,
                            contentDescription =
                                stringResource(Res.string.action_friend_invite_add_by_code),
                        )
                    }
                },
            )
        },
        snackbarHost = { LiveSnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            PrimaryTabRow(
                selectedTabIndex = state.activeTab.ordinal,
                modifier = Modifier.testTag("connectionsTabRow"),
            ) {
                ConnectionsTab.entries.forEach { tab ->
                    Tab(
                        selected = state.activeTab == tab,
                        onClick = {
                            viewModel.onEvent(ConnectionsEvent.SelectTab(tab))
                        },
                        text = { Text(stringResource(tab.titleKey())) },
                        modifier = Modifier.testTag("connectionsTab_${tab.name}"),
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (state.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    when (state.activeTab) {
                        ConnectionsTab.Friends ->
                            FriendsTabContent(
                                state = state,
                                onDisconnect = { otherUserId, displayName ->
                                    pendingDisconnect = PendingDisconnect(otherUserId, displayName)
                                },
                            )
                        ConnectionsTab.Pending ->
                            PendingTabContent(
                                state = state,
                                onAccept = { otherUserId ->
                                    viewModel.onEvent(ConnectionsEvent.AcceptRequest(otherUserId))
                                },
                                onReject = { otherUserId ->
                                    viewModel.onEvent(ConnectionsEvent.RejectRequest(otherUserId))
                                },
                                onCancelOutbound = { otherUserId ->
                                    viewModel.onEvent(
                                        ConnectionsEvent.CancelOutboundRequest(otherUserId),
                                    )
                                },
                            )
                        ConnectionsTab.Invite ->
                            InviteTabContent(
                                state = state,
                                onCreateInvite = {
                                    viewModel.onEvent(ConnectionsEvent.CreateInvite)
                                },
                            )
                    }
                }
            }
        }
    }

    pendingDisconnect?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingDisconnect = null },
            title = { Text(stringResource(Res.string.dialog_connections_disconnect_title)) },
            text = {
                Text(
                    stringResource(
                        Res.string.dialog_connections_disconnect_body,
                        pending.displayName,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onEvent(ConnectionsEvent.Disconnect(pending.otherUserId))
                        pendingDisconnect = null
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    modifier = Modifier.testTag("connectionsConfirmDisconnect"),
                ) {
                    Text(stringResource(Res.string.action_connections_disconnect))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDisconnect = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

private data class PendingDisconnect(
    val otherUserId: String,
    val displayName: String,
)

@Composable
private fun FriendsTabContent(
    state: ConnectionsState,
    onDisconnect: (otherUserId: String, displayName: String) -> Unit,
) {
    val callerId = state.callerId
    if (callerId == null || state.friends.isEmpty()) {
        EmptyState(
            messageKey = Res.string.state_connections_no_friends,
            testTag = "connectionsFriendsEmpty",
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().testTag("connectionsFriendsList")) {
        // Key is the sorted-pair composite (userA < userB by schema
        // invariant) joined with a separator that cannot appear in a
        // UUID — unambiguous, no collision across rows.
        items(state.friends, key = { "${it.userA}|${it.userB}" }) { connection ->
            val otherUserId = connection.otherUserId(callerId)
            val displayName = state.displayNameOf(otherUserId)
            ListItem(
                headlineContent = { Text(displayName) },
                trailingContent = {
                    if (state.isActionInFlight(otherUserId)) {
                        InlineSpinner()
                    } else {
                        OutlinedButton(
                            onClick = { onDisconnect(otherUserId, displayName) },
                            colors =
                                ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            modifier =
                                Modifier.testTag(
                                    "connectionsDisconnectButton_$otherUserId",
                                ),
                        ) {
                            Text(stringResource(Res.string.action_connections_disconnect))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("connectionsFriendRow_$otherUserId"),
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun PendingTabContent(
    state: ConnectionsState,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit,
    onCancelOutbound: (String) -> Unit,
) {
    val callerId = state.callerId
    if (callerId == null || state.pending.isEmpty()) {
        EmptyState(
            messageKey = Res.string.state_connections_no_pending,
            testTag = "connectionsPendingEmpty",
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().testTag("connectionsPendingList")) {
        items(state.pending, key = { "${it.userA}|${it.userB}" }) { connection ->
            val otherUserId = connection.otherUserId(callerId)
            val displayName = state.displayNameOf(otherUserId)
            val inbound = state.isInbound(connection)
            val labelKey =
                if (inbound) {
                    Res.string.state_connections_request_from
                } else {
                    Res.string.state_connections_request_to
                }
            ListItem(
                headlineContent = { Text(stringResource(labelKey, displayName)) },
                trailingContent = {
                    if (state.isActionInFlight(otherUserId)) {
                        InlineSpinner()
                    } else if (inbound) {
                        Column(horizontalAlignment = Alignment.End) {
                            Button(
                                onClick = { onAccept(otherUserId) },
                                modifier =
                                    Modifier.testTag(
                                        "connectionsAcceptButton_$otherUserId",
                                    ),
                            ) {
                                Text(stringResource(Res.string.action_connections_accept))
                            }
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = { onReject(otherUserId) },
                                modifier =
                                    Modifier.testTag(
                                        "connectionsRejectButton_$otherUserId",
                                    ),
                            ) {
                                Text(stringResource(Res.string.action_connections_reject))
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onCancelOutbound(otherUserId) },
                            modifier =
                                Modifier.testTag(
                                    "connectionsCancelOutboundButton_$otherUserId",
                                ),
                        ) {
                            Text(stringResource(Res.string.action_cancel))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("connectionsPendingRow_$otherUserId"),
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun InviteTabContent(
    state: ConnectionsState,
    onCreateInvite: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(Res.string.body_connections_invite_explanation),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = onCreateInvite,
            enabled = !state.isCreatingInvite,
            modifier = Modifier.fillMaxWidth().testTag("connectionsCreateInviteButton"),
        ) {
            if (state.isCreatingInvite) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Text(stringResource(Res.string.action_connections_create_invite))
            }
        }

        if (state.invites.isEmpty()) {
            EmptyState(
                messageKey = Res.string.state_connections_no_invite,
                testTag = "connectionsInviteEmpty",
            )
        } else {
            // `weight(1f)` (NOT fillMaxSize) so the parent Column
            // hands the LazyColumn a bounded height. A LazyColumn with
            // fillMaxSize inside a fillMaxSize Column receives an
            // unbounded vertical constraint, which defeats laziness
            // (all items measured upfront) and throws on some Compose
            // versions ("Vertically scrollable component was measured
            // with an infinity maximum height").
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("connectionsInviteList"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.invites, key = { it.id }) { invite ->
                    InviteCard(invite = invite)
                }
            }
        }
    }
}

@Composable
private fun InviteCard(invite: FriendInvite) {
    // Keyed on expiresAt only: the displayed day count is computed once
    // when the card first composes and won't tick down within a session
    // (cosmetic drift on a 14-day invite; not worth a clock dependency
    // that would complicate the KMP compile). Do NOT add Clock.System
    // to the remember key — it would recompute every recomposition.
    val daysRemaining = remember(invite.expiresAt) { invite.daysRemaining() }
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth().testTag("connectionsInviteCard_${invite.id}"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = invite.code,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag("connectionsInviteCode_${invite.id}"),
            )
            Text(
                text =
                    stringResource(
                        Res.string.state_connections_invite_expires_in_days,
                        daysRemaining,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(
    messageKey: org.jetbrains.compose.resources.StringResource,
    testTag: String,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(messageKey),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(testTag),
        )
    }
}

@Composable
private fun InlineSpinner() {
    CircularProgressIndicator(
        strokeWidth = 2.dp,
        modifier = Modifier.size(20.dp),
    )
}

private fun ConnectionsTab.titleKey(): org.jetbrains.compose.resources.StringResource =
    when (this) {
        ConnectionsTab.Friends -> Res.string.tab_connections_friends
        ConnectionsTab.Pending -> Res.string.tab_connections_pending
        ConnectionsTab.Invite -> Res.string.tab_connections_invite
    }

/** Resolves a UUID to the cached display name OR a localized fallback
 *  via inline string-resource lookup at the call site. */
@Composable
private fun ConnectionsState.displayNameOf(otherUserId: String): String =
    displayNames[otherUserId] ?: stringResource(Res.string.state_connections_unknown_user)

/** Days until the invite expires, floored to 0 for already-expired
 *  invites (defensive — UI should still render those as "0 days"
 *  rather than negative). */
private fun FriendInvite.daysRemaining(): Int {
    val now = Clock.System.now()
    val remaining = expiresAt - now
    return remaining.inWholeDays.coerceAtLeast(0L).toInt()
}
