package io.github.b150005.skeinly.ui.packmanagement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.domain.model.SymbolPackTier
import io.github.b150005.skeinly.domain.symbol.PackRow
import io.github.b150005.skeinly.domain.symbol.PackStatus
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_back
import io.github.b150005.skeinly.generated.resources.action_refresh_packs
import io.github.b150005.skeinly.generated.resources.action_unlock_with_pro
import io.github.b150005.skeinly.generated.resources.body_pack_locked_inline
import io.github.b150005.skeinly.generated.resources.label_pack_size_kb
import io.github.b150005.skeinly.generated.resources.label_pack_size_mb
import io.github.b150005.skeinly.generated.resources.label_pack_status_downloaded
import io.github.b150005.skeinly.generated.resources.label_pack_status_locked
import io.github.b150005.skeinly.generated.resources.label_pack_status_not_downloaded
import io.github.b150005.skeinly.generated.resources.label_pack_status_update_available
import io.github.b150005.skeinly.generated.resources.label_pack_symbol_count
import io.github.b150005.skeinly.generated.resources.label_pack_tier_free
import io.github.b150005.skeinly.generated.resources.label_pack_tier_pro
import io.github.b150005.skeinly.generated.resources.label_pack_total_size
import io.github.b150005.skeinly.generated.resources.label_pack_version_x
import io.github.b150005.skeinly.generated.resources.state_no_packs
import io.github.b150005.skeinly.generated.resources.state_no_packs_body
import io.github.b150005.skeinly.generated.resources.title_pack_management
import io.github.b150005.skeinly.ui.components.LiveSnackbarHost
import io.github.b150005.skeinly.ui.components.localized
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Phase 41.4 (ADR-016 §5.2 §6 §41.4) — Settings → "Manage Symbol Packs"
 * surface. Read-only list of every catalog pack with per-row status badge,
 * total downloaded-disk-size, and a Refresh affordance.
 *
 * "Free up storage" + per-pack "Update / Download" buttons are deferred
 * to Phase 41.5+ — see [PackManagementViewModel] KDoc for rationale.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackManagementScreen(
    onBack: () -> Unit,
    onUnlockWithPro: () -> Unit,
    viewModel: PackManagementViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val errorText = state.error?.localized()
    LaunchedEffect(errorText) {
        if (errorText != null) {
            snackbarHostState.showSnackbar(errorText)
            viewModel.onEvent(PackManagementEvent.ClearError)
        }
    }

    Scaffold(
        modifier = Modifier.testTag("packManagementScreen"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.title_pack_management),
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
                actions = {
                    IconButton(
                        onClick = { viewModel.onEvent(PackManagementEvent.Refresh) },
                        enabled = !state.isRefreshing,
                        modifier = Modifier.testTag("refreshPacksButton"),
                    ) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.height(20.dp),
                            )
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(Res.string.action_refresh_packs),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { LiveSnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            state.isLoading -> LoadingView(padding)
            state.rows.isEmpty() -> EmptyView(padding)
            else ->
                LoadedView(
                    padding = padding,
                    state = state,
                    onUnlockWithPro = onUnlockWithPro,
                )
        }
    }
}

@Composable
private fun LoadingView(padding: PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyView(padding: PaddingValues) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(Res.string.state_no_packs),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.state_no_packs_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadedView(
    padding: PaddingValues,
    state: PackManagementState,
    onUnlockWithPro: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding),
    ) {
        Text(
            text =
                stringResource(
                    Res.string.label_pack_total_size,
                    formatPackSize(state.totalDownloadedBytes),
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.rows, key = { it.packId }) { row ->
                PackCard(row = row, onUnlockWithPro = onUnlockWithPro)
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackCard(
    row: PackRow,
    onUnlockWithPro: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("packCard_${row.packId}"),
        colors = CardDefaults.cardColors(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).testTag("packTitle_${row.packId}"),
                )
                TierBadge(tier = row.tier)
            }
            row.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text =
                        listOf(
                            stringResource(Res.string.label_pack_version_x, row.serverVersion),
                            stringResource(Res.string.label_pack_symbol_count, row.symbolCount),
                            formatPackSize(row.payloadSize.toLong()),
                        ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            StatusChip(status = row.status, packId = row.packId)
            if (row.status == PackStatus.Locked) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.body_pack_locked_inline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onUnlockWithPro,
                    modifier = Modifier.testTag("unlockWithProButton_${row.packId}"),
                ) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(stringResource(Res.string.action_unlock_with_pro))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TierBadge(tier: SymbolPackTier) {
    val (key, testTagSuffix) =
        when (tier) {
            SymbolPackTier.FREE -> Res.string.label_pack_tier_free to "Free"
            SymbolPackTier.PRO -> Res.string.label_pack_tier_pro to "Pro"
        }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(stringResource(key)) },
        modifier = Modifier.testTag("packTierBadge$testTagSuffix"),
        colors =
            AssistChipDefaults.assistChipColors(
                disabledLabelColor = MaterialTheme.colorScheme.onSurface,
            ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusChip(
    status: PackStatus,
    packId: String,
) {
    val key: StringResource =
        when (status) {
            PackStatus.Downloaded -> Res.string.label_pack_status_downloaded
            PackStatus.UpdateAvailable -> Res.string.label_pack_status_update_available
            PackStatus.NotDownloaded -> Res.string.label_pack_status_not_downloaded
            PackStatus.Locked -> Res.string.label_pack_status_locked
        }
    val tagSuffix =
        when (status) {
            PackStatus.Downloaded -> "Downloaded"
            PackStatus.UpdateAvailable -> "UpdateAvailable"
            PackStatus.NotDownloaded -> "NotDownloaded"
            PackStatus.Locked -> "Locked"
        }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(stringResource(key)) },
        modifier = Modifier.testTag("packStatus${tagSuffix}_$packId"),
        colors =
            AssistChipDefaults.assistChipColors(
                disabledLabelColor = MaterialTheme.colorScheme.onSurface,
            ),
    )
}

/**
 * Format a payload size into a localized "X KB" / "X MB" string. The
 * label format strings carry only the formatted decimal as a `%1$s`
 * placeholder so the call site owns the locale-specific decimal point;
 * this helper renders the integer for KB and one decimal for MB.
 */
@Composable
internal fun formatPackSize(bytes: Long): String {
    if (bytes < KB_MB_THRESHOLD) {
        val kb = (bytes + 512) / 1024
        return stringResource(Res.string.label_pack_size_kb, kb.toString())
    }
    // Render to one decimal place. Locale-specific decimal point is the
    // String.toString() default for Double on the platform — sufficient
    // for v1 since both en and ja use "." as the decimal separator at
    // the App-Store-marketing-copy level.
    val mbInt = bytes / 1_048_576L
    val mbTenths = ((bytes % 1_048_576L) * 10L) / 1_048_576L
    val rendered = "$mbInt.$mbTenths"
    return stringResource(Res.string.label_pack_size_mb, rendered)
}

/** Switch threshold from KB to MB rendering — 1 MB exact. */
private const val KB_MB_THRESHOLD = 1_048_576L
