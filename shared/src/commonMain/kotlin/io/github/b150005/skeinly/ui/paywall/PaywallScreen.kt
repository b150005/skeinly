package io.github.b150005.skeinly.ui.paywall

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.data.analytics.PaywallTrigger
import io.github.b150005.skeinly.domain.subscription.PaywallPackage
import io.github.b150005.skeinly.domain.subscription.PaywallPeriod
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_close_paywall
import io.github.b150005.skeinly.generated.resources.action_dismiss_paywall_error
import io.github.b150005.skeinly.generated.resources.action_privacy_policy
import io.github.b150005.skeinly.generated.resources.action_restore_purchase
import io.github.b150005.skeinly.generated.resources.action_subscribe
import io.github.b150005.skeinly.generated.resources.action_subscribe_annual
import io.github.b150005.skeinly.generated.resources.action_subscribe_monthly
import io.github.b150005.skeinly.generated.resources.action_terms_of_service
import io.github.b150005.skeinly.generated.resources.body_paywall_pitch
import io.github.b150005.skeinly.generated.resources.body_paywall_trial_disclosure
import io.github.b150005.skeinly.generated.resources.state_paywall_unavailable
import io.github.b150005.skeinly.generated.resources.state_purchase_failed
import io.github.b150005.skeinly.generated.resources.state_purchase_in_progress
import io.github.b150005.skeinly.generated.resources.state_restoring
import io.github.b150005.skeinly.generated.resources.title_paywall
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Phase 41.3b (ADR-016 §5.1) — paywall sheet over the app.
 *
 * Layout:
 * - Header: "Skeinly Pro" title + pitch line.
 * - Package rows: monthly + annual side-by-side, each rendered as a tappable
 *   row. The selected row carries a "selected" outline; tapping a row updates
 *   [PaywallState.selectedPackageId] without yet purchasing.
 * - Trial disclosure line (App Store §3.1.2(a) + Play Console rule).
 * - Filled-prominent "Subscribe" button (acts on the currently selected row);
 *   bordered "Restore Purchases" below.
 * - Footer: ToS + Privacy text-button links opening external browser.
 *
 * Loading + error are inline. The screen never throws — every error path
 * surfaces an inline label with a "Dismiss" affordance.
 *
 * Nav events: success messages dispatch through the host's `onPaywallResult`
 * callback so the host can surface a Snackbar / toast on the parent surface
 * (the sheet is dismissed by the time the message lands).
 */
sealed interface PaywallResult {
    data object PurchaseConfirmed : PaywallResult

    data object RestoredWithPro : PaywallResult

    data object RestoredEmpty : PaywallResult
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallScreen(
    trigger: PaywallTrigger,
    onDismiss: () -> Unit,
    onPaywallResult: (PaywallResult) -> Unit,
    viewModel: PaywallViewModel = koinViewModel { parametersOf(trigger) },
) {
    val state by viewModel.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            // Hide the sheet first, then notify the host so the toast lands
            // on the parent surface (Snackbar/overlay anchored on the host
            // doesn't overlap a half-dismissed sheet).
            val result =
                when (event) {
                    is PaywallNavEvent.PurchaseConfirmed -> PaywallResult.PurchaseConfirmed
                    PaywallNavEvent.RestoredWithPro -> PaywallResult.RestoredWithPro
                    PaywallNavEvent.RestoredEmpty -> PaywallResult.RestoredEmpty
                    PaywallNavEvent.Dismissed -> null
                }
            scope.launch { sheetState.hide() }.invokeOnCompletion {
                onDismiss()
                if (result != null) onPaywallResult(result)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { viewModel.onEvent(PaywallEvent.Dismiss) },
        sheetState = sheetState,
        modifier = Modifier.testTag("paywallScreen"),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Text(
                text = stringResource(Res.string.title_paywall),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(Res.string.body_paywall_pitch),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            // Hoist offering into a local val so smart-cast lands inside the
            // else branch — `when { state.offering == null -> ... else -> ... }`
            // does not promote `state.offering` to non-null inside `else`.
            val offering = state.offering
            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                offering == null -> {
                    // Either fetch failed (state.error non-null) or RevenueCat
                    // returned an empty offering — surface the same recovery
                    // affordance for both paths.
                    Text(
                        text = stringResource(Res.string.state_paywall_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    // Package rows. Iterate `packages` in order so a future
                    // OTHER-period addition (lifetime, weekly) renders without
                    // needing a layout edit — falls back to a generic label.
                    offering.packages.forEach { pkg ->
                        PackageRow(
                            pkg = pkg,
                            selected = state.selectedPackageId == pkg.identifier,
                            onSelect = {
                                viewModel.onEvent(PaywallEvent.SelectPackage(pkg.identifier))
                            },
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = stringResource(Res.string.body_paywall_trial_disclosure),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Subscribe button. Disabled while a purchase / restore is
                    // in flight; CircularProgressIndicator embedded for visual
                    // feedback. The state.selectedPackageId guard short-
                    // circuits the (rare) case where every package was removed
                    // mid-render — defense-in-depth, the offering check above
                    // already pretty much rules it out.
                    val selectedPkg = state.selectedPackageId
                    Button(
                        onClick = {
                            if (selectedPkg != null) {
                                viewModel.onEvent(PaywallEvent.ConfirmPurchase(selectedPkg))
                            }
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("confirmPurchaseButton"),
                        enabled = selectedPkg != null && !state.isPurchasing && !state.isRestoring,
                    ) {
                        if (state.isPurchasing) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Text(
                                text = stringResource(Res.string.state_purchase_in_progress),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        } else {
                            Text(stringResource(Res.string.action_subscribe))
                        }
                    }

                    OutlinedButton(
                        onClick = { viewModel.onEvent(PaywallEvent.RestorePurchases) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("restorePurchasesButton"),
                        enabled = !state.isPurchasing && !state.isRestoring,
                    ) {
                        if (state.isRestoring) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = stringResource(Res.string.state_restoring),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        } else {
                            Text(stringResource(Res.string.action_restore_purchase))
                        }
                    }
                }
            }

            // Inline error label. Surfaces both fetch + purchase + restore
            // errors via the same slot. Has its own Dismiss button so the
            // user can recover without leaving the sheet.
            state.error?.let { errorText ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().testTag("paywallErrorLabel"),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(Res.string.state_purchase_failed),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = errorText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = { viewModel.onEvent(PaywallEvent.ClearError) },
                            modifier = Modifier.testTag("dismissPaywallErrorButton"),
                        ) {
                            Text(stringResource(Res.string.action_dismiss_paywall_error))
                        }
                    }
                }
            }

            HorizontalDivider()

            // Legal footer — the same URLs used by SettingsScreen. Required
            // surface per App Store Review §3.1.2(a) + Play Console subscription
            // disclosure rules ("must show terms and privacy policy on the
            // subscription purchase screen").
            //
            // Note: `LocalUriHandler` is the standard Compose surface for
            // opening external URLs. Inside a `ModalBottomSheet`, taps on
            // these links route through the system browser and the sheet
            // remains in the foreground when the user returns.
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TextButton(
                    onClick = { uriHandler.openUri(URL_TERMS_OF_SERVICE) },
                    modifier = Modifier.testTag("paywallTermsLink"),
                ) {
                    Text(
                        text = stringResource(Res.string.action_terms_of_service),
                        textAlign = TextAlign.Center,
                    )
                }
                TextButton(
                    onClick = { uriHandler.openUri(URL_PRIVACY_POLICY) },
                    modifier = Modifier.testTag("paywallPrivacyLink"),
                ) {
                    Text(
                        text = stringResource(Res.string.action_privacy_policy),
                        textAlign = TextAlign.Center,
                    )
                }
                TextButton(
                    onClick = { viewModel.onEvent(PaywallEvent.Dismiss) },
                    modifier = Modifier.testTag("closePaywallButton"),
                ) {
                    Text(stringResource(Res.string.action_close_paywall))
                }
            }
        }
    }
}

@Composable
private fun PackageRow(
    pkg: PaywallPackage,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val labelKey =
        when (pkg.period) {
            PaywallPeriod.MONTHLY -> Res.string.action_subscribe_monthly
            PaywallPeriod.ANNUAL -> Res.string.action_subscribe_annual
            // OTHER falls back to the priceString alone — no period-specific
            // copy. Future weekly / lifetime offers should add a key here.
            PaywallPeriod.OTHER -> Res.string.action_subscribe_monthly
        }
    val testTagSuffix =
        when (pkg.period) {
            PaywallPeriod.MONTHLY -> "monthly"
            PaywallPeriod.ANNUAL -> "annual"
            PaywallPeriod.OTHER -> "other"
        }

    val containerColor =
        if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    val contentColor =
        if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .testTag("subscribe${testTagSuffix.replaceFirstChar { it.uppercase() }}Button"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(labelKey, pkg.priceString),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private const val URL_PRIVACY_POLICY = "https://b150005.github.io/skeinly/privacy-policy/"
private const val URL_TERMS_OF_SERVICE = "https://b150005.github.io/skeinly/terms-of-service/"
