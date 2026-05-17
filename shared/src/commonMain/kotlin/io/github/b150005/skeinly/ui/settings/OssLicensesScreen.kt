package io.github.b150005.skeinly.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.domain.model.OssLibrary
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_back
import io.github.b150005.skeinly.generated.resources.action_open_source_licenses
import io.github.b150005.skeinly.generated.resources.action_retry
import io.github.b150005.skeinly.generated.resources.state_oss_licenses_load_failed
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Pre-Phase-40 A33 — in-app "Open Source Licenses" attribution screen
 * (Settings → About → Open Source Licenses).
 *
 * Renders the `OssLibrary` list parsed from the build-time-generated
 * `aboutlibraries.json`. The list is rendered from the SAME shared model
 * the SwiftUI `OssLicensesView` consumes — Android uses a plain Material
 * 3 `LazyColumn` rather than `aboutlibraries-compose-m3`'s
 * `LibrariesContainer` so the two platforms stay visually in parity and
 * the dependency surface stays at `-core` only.
 *
 * Scaffold / TopAppBar / back-button shape mirrors [DataExportScreen].
 * Three states: a centered spinner on first load, a centered
 * message + Retry on parse/resource failure, otherwise the scrolling
 * list. Tapping a row with a license URL opens it in the system browser
 * (the static `docs/public/licenses/` page remains the reviewer-facing
 * surface for those without the app installed).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OssLicensesScreen(
    onBack: () -> Unit,
    viewModel: OssLicensesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val uriHandler = LocalUriHandler.current

    Scaffold(
        modifier = Modifier.testTag("ossLicensesScreen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.action_open_source_licenses)) },
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
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            // Branch order is significant: `hasError` is checked before
            // `isLoading` so a failed reload surfaces the error (not a
            // stale list); the terminal "no error, not loading, empty"
            // case (a should-never-happen broken/empty bundled JSON)
            // renders a message WITHOUT a Retry button — retrying a
            // deterministic empty parse would just loop.
            when {
                state.hasError ->
                    ErrorState(
                        message = stringResource(Res.string.state_oss_licenses_load_failed),
                        onRetry = { viewModel.onEvent(OssLicensesEvent.Retry) },
                        modifier = Modifier.align(Alignment.Center),
                    )

                state.isLoading ->
                    CircularProgressIndicator(
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .testTag("ossLicensesLoading"),
                    )

                state.libraries.isNotEmpty() ->
                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .testTag("ossLicensesList"),
                    ) {
                        items(
                            items = state.libraries,
                            key = { it.uniqueId },
                        ) { library ->
                            OssLibraryRow(
                                library = library,
                                onLicenseClick = uriHandler::openUri,
                            )
                            HorizontalDivider()
                        }
                    }

                else ->
                    // Unreachable with a correctly-generated bundle (the
                    // committed JSON always has entries; a missing/broken
                    // resource throws → `hasError`). The load-failed
                    // string is intentionally reused rather than minting
                    // a parity-checked `state_oss_licenses_empty` key for
                    // a branch that cannot occur in production (YAGNI) —
                    // from the user's POV "no license data" reads the
                    // same as "couldn't load it".
                    Text(
                        text = stringResource(Res.string.state_oss_licenses_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .padding(24.dp)
                                .testTag("ossLicensesEmpty"),
                    )
            }
        }
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .padding(24.dp)
                .testTag("ossLicensesError"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.testTag("ossLicensesRetryButton"),
        ) {
            Text(stringResource(Res.string.action_retry))
        }
    }
}

@Composable
private fun OssLibraryRow(
    library: OssLibrary,
    onLicenseClick: (String) -> Unit,
) {
    val licenseUrl = library.licenseUrl
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (licenseUrl != null) {
                        Modifier.clickable(role = Role.Button) { onLicenseClick(licenseUrl) }
                    } else {
                        Modifier
                    },
                ).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = library.name,
            style = MaterialTheme.typography.titleSmall,
        )
        val meta = listOfNotNull(library.version, library.license).joinToString(" • ")
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
