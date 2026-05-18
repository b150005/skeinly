package io.github.b150005.skeinly.ui.settings

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
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.config.BuildFlags
import io.github.b150005.skeinly.domain.model.AuthProviderKind
import io.github.b150005.skeinly.domain.model.LinkedIdentity
import io.github.b150005.skeinly.domain.model.MfaEnrollmentStatus
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_back
import io.github.b150005.skeinly.generated.resources.action_cancel
import io.github.b150005.skeinly.generated.resources.action_change_email
import io.github.b150005.skeinly.generated.resources.action_change_password
import io.github.b150005.skeinly.generated.resources.action_contact_support
import io.github.b150005.skeinly.generated.resources.action_delete
import io.github.b150005.skeinly.generated.resources.action_delete_account
import io.github.b150005.skeinly.generated.resources.action_disable_mfa
import io.github.b150005.skeinly.generated.resources.action_help_faq
import io.github.b150005.skeinly.generated.resources.action_manage_subscription
import io.github.b150005.skeinly.generated.resources.action_open_source_licenses
import io.github.b150005.skeinly.generated.resources.action_privacy_policy
import io.github.b150005.skeinly.generated.resources.action_save
import io.github.b150005.skeinly.generated.resources.action_send_feedback
import io.github.b150005.skeinly.generated.resources.action_sign_out
import io.github.b150005.skeinly.generated.resources.action_terms_of_service
import io.github.b150005.skeinly.generated.resources.body_blocked_users_settings_row
import io.github.b150005.skeinly.generated.resources.body_connections_settings_row
import io.github.b150005.skeinly.generated.resources.body_contact_support_helper
import io.github.b150005.skeinly.generated.resources.body_delete_account_warning
import io.github.b150005.skeinly.generated.resources.body_diagnostic_data_explanation
import io.github.b150005.skeinly.generated.resources.body_export_data_settings_row
import io.github.b150005.skeinly.generated.resources.body_manage_subscription_helper
import io.github.b150005.skeinly.generated.resources.body_mfa_disable_warning
import io.github.b150005.skeinly.generated.resources.body_notifications_disabled_hint
import io.github.b150005.skeinly.generated.resources.body_notifications_setting_explanation
import io.github.b150005.skeinly.generated.resources.body_send_feedback_explanation
import io.github.b150005.skeinly.generated.resources.body_wipe_data_settings_row
import io.github.b150005.skeinly.generated.resources.dialog_change_email_title
import io.github.b150005.skeinly.generated.resources.dialog_change_password_title
import io.github.b150005.skeinly.generated.resources.dialog_delete_account_body
import io.github.b150005.skeinly.generated.resources.dialog_delete_account_title
import io.github.b150005.skeinly.generated.resources.label_about_section
import io.github.b150005.skeinly.generated.resources.label_account_section
import io.github.b150005.skeinly.generated.resources.label_beta_section
import io.github.b150005.skeinly.generated.resources.label_danger_zone
import io.github.b150005.skeinly.generated.resources.label_diagnostic_data_sharing
import io.github.b150005.skeinly.generated.resources.label_linked_identities
import io.github.b150005.skeinly.generated.resources.label_new_email
import io.github.b150005.skeinly.generated.resources.label_new_password
import io.github.b150005.skeinly.generated.resources.label_notifications_settings_row
import io.github.b150005.skeinly.generated.resources.label_pack_management
import io.github.b150005.skeinly.generated.resources.label_paywall_section
import io.github.b150005.skeinly.generated.resources.label_privacy_section
import io.github.b150005.skeinly.generated.resources.label_security_section
import io.github.b150005.skeinly.generated.resources.label_signed_in_via
import io.github.b150005.skeinly.generated.resources.label_subscribe_to_pro
import io.github.b150005.skeinly.generated.resources.label_two_factor_auth
import io.github.b150005.skeinly.generated.resources.message_email_change_pending
import io.github.b150005.skeinly.generated.resources.message_password_changed
import io.github.b150005.skeinly.generated.resources.state_biometric_disabled
import io.github.b150005.skeinly.generated.resources.state_biometric_enabled
import io.github.b150005.skeinly.generated.resources.state_deleting_account
import io.github.b150005.skeinly.generated.resources.state_mfa_disabled
import io.github.b150005.skeinly.generated.resources.state_mfa_enabled
import io.github.b150005.skeinly.generated.resources.state_notifications_disabled
import io.github.b150005.skeinly.generated.resources.state_notifications_enabled
import io.github.b150005.skeinly.generated.resources.state_signed_in_via_apple
import io.github.b150005.skeinly.generated.resources.state_signed_in_via_apple_relay
import io.github.b150005.skeinly.generated.resources.state_signed_in_via_email
import io.github.b150005.skeinly.generated.resources.state_signed_in_via_google
import io.github.b150005.skeinly.generated.resources.title_biometric_settings
import io.github.b150005.skeinly.generated.resources.title_blocked_users
import io.github.b150005.skeinly.generated.resources.title_connections
import io.github.b150005.skeinly.generated.resources.title_export_data_settings_row
import io.github.b150005.skeinly.generated.resources.title_mfa_disable_confirm
import io.github.b150005.skeinly.generated.resources.title_settings
import io.github.b150005.skeinly.generated.resources.title_wipe_data_settings_row
import io.github.b150005.skeinly.ui.components.LiveSnackbarHost
import io.github.b150005.skeinly.ui.components.localized
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

private const val URL_PRIVACY_POLICY = "https://b150005.github.io/skeinly/privacy-policy/"
private const val URL_TERMS_OF_SERVICE = "https://b150005.github.io/skeinly/terms-of-service/"

// Pre-alpha A35 — Help / FAQ page. GitHub Pages serves the EN page at
// `/help/` and the JA mirror at `/ja/help/`. Skeinly does not actively
// route based on device locale; users see EN by default and can click
// the "日本語" link in the page header to switch — same pattern as the
// Privacy Policy + ToS pages.
private const val URL_HELP = "https://b150005.github.io/skeinly/help/"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAccountDeleted: () -> Unit,
    // Phase 39.4 (ADR-015 §6) — invoked when the user taps "Send Feedback".
    // Phase 39.5 will wire this to the BugReportPreviewScreen + GitHub
    // Issue prefill flow; today the binding from NavGraph is a no-op so
    // the row renders correctly under the beta-gated section without
    // surfacing a half-built UX.
    onSendFeedback: () -> Unit = {},
    // Phase 41.3b (ADR-016 §5.1) — invoked when the user taps "Subscribe
    // to Pro". Always-on entry, NOT beta-gated; F1 monetization is a
    // production feature, not a beta-only experiment. NavGraph wires this
    // to the `Paywall(trigger = Settings)` route.
    onSubscribeToProClick: () -> Unit = {},
    // Phase 41.4 (ADR-016 §5.2) — invoked when the user taps "Manage
    // Symbol Packs". Always-on, NOT beta-gated. NavGraph wires this to
    // the `PackManagement` route.
    onManagePacksClick: () -> Unit = {},
    // Phase 26.5 (ADR-022 §6.4) — invoked when the user taps "Enable
    // two-factor authentication" in the Security section. NavGraph wires
    // this to the `MfaEnrollment` route. Default no-op for test mounts.
    onEnableMfaClick: () -> Unit = {},
    // Phase 26.6 (ADR-022 §6.5) — invoked when the user taps
    // "Biometric authentication" in the Security section. NavGraph
    // wires this to the `BiometricSettings` route.
    onBiometricSettingsClick: () -> Unit = {},
    // Phase 27.2 (ADR-023 §UX) — invoked when the user taps "Delete
    // all my data" in the Danger Zone section. NavGraph wires this to
    // the `WipeDataConfirmPhrase` route which mounts the WipeData VM
    // with the locale-active required phrase.
    onWipeDataClick: () -> Unit = {},
    // Phase 25.3 (ADR-024 §(e)) — invoked when the user taps
    // "Connections" in the new Privacy section. NavGraph wires this to
    // the `Connections` route which renders the 3-tab Friends /
    // Pending / Invite screen.
    onConnectionsClick: () -> Unit = {},
    // Phase 39 (ADR-021 §D4) — invoked when the user taps "Blocked
    // users" in the Privacy section. NavGraph wires this to the
    // `BlockedUsers` route. Default no-op keeps test mounts / older
    // NavGraph wiring valid.
    onBlockedUsersClick: () -> Unit = {},
    // Pre-Phase-40 A20 Option B — invoked when the user taps "Export
    // My Data" in the Privacy section. NavGraph wires this to the
    // `DataExport` route. Default no-op keeps test mounts / older
    // NavGraph wiring valid.
    onExportDataClick: () -> Unit = {},
    // Pre-Phase-40 A33 — invoked when the user taps "Open Source
    // Licenses" in the About section. NavGraph wires this to the
    // `OssLicenses` route (an in-app screen rendering the generated
    // attribution list); previously this row opened the static web
    // page in the system browser. Default no-op keeps test mounts /
    // older NavGraph wiring valid.
    onOssLicensesClick: () -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel(),
    // Phase 24.2c (ADR-017 §3.6) — push notification consent VM. Drives
    // the Settings → Notifications row + the in-app pre-permission
    // explainer dialog. Default-injected via Koin for production; tests
    // can pass a stub via the parameter.
    notificationViewModel: io.github.b150005.skeinly.ui.notifications.NotificationPermissionViewModel = koinViewModel(),
    // Phase 26.6 (ADR-022 §6.5) — biometric settings VM. Used here
    // only to drive the Settings → Security row's trailing state
    // label ("Enabled" / "Disabled"); the BiometricSettingsScreen
    // itself resolves its own koinViewModel() instance because Koin's
    // factory scoping returns the same singleton-scoped VM regardless
    // of the call site.
    biometricSettingsViewModel: io.github.b150005.skeinly.ui.biometric.BiometricSettingsViewModel = koinViewModel(),
    // Pre-alpha A30 — opens the platform's subscription management UI
    // (Play Store → Subscriptions / App Store → Subscriptions). Default-
    // injected via Koin for production; tests can pass a stub. Same DI
    // pattern as `storeLauncher` in `ForceUpdateGate`.
    subscriptionLauncher: io.github.b150005.skeinly.platform.SubscriptionManagementLauncher = koinInject(),
    // Pre-alpha A34 — opens mailto: support composer with diagnostic
    // pre-fill. Same DI pattern.
    supportContactLauncher: io.github.b150005.skeinly.platform.SupportContactLauncher = koinInject(),
    // Pre-alpha A34 — needed to pull the diagnostic context fields into
    // the mailto pre-fill.
    deviceContext: io.github.b150005.skeinly.platform.DeviceContextProvider = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val notificationState by notificationViewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showDisableMfaConfirmation by remember { mutableStateOf(false) }

    // state.error is still rendered raw here — ViewModel error-message
    // localization is deferred per Tech Debt Backlog.
    val errorText = state.error?.localized()

    LaunchedEffect(errorText) {
        errorText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(SettingsEvent.ClearError)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.accountDeleted.collect {
            onAccountDeleted()
        }
    }

    val passwordChangedMessage = stringResource(Res.string.message_password_changed)
    val emailChangePendingMessage = stringResource(Res.string.message_email_change_pending)
    LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { event ->
            val message =
                when (event) {
                    SettingsToastEvent.PasswordChanged -> passwordChangedMessage
                    SettingsToastEvent.EmailChangePending -> emailChangePendingMessage
                }
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = Modifier.testTag("settingsScreen"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.title_settings),
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
        snackbarHost = { LiveSnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
            ) {
                // B3 (Phase 39.1): Account section is gated on
                // `state.isSignedIn`. Apple Reviewers may reject UIs that
                // imply features the user can't access (e.g. showing
                // "Sign Out" / "Delete Account" while never signed in).
                if (state.isSignedIn) {
                    // Account section
                    Text(
                        text = stringResource(Res.string.label_account_section),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )

                    state.email?.let { email ->
                        Text(
                            text = email,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Phase 26.6 (ADR-022 §6.6) — primary identity
                    // discriminator + linked-accounts list. Shown only
                    // when the AuthRepository surfaced at least one
                    // identity (the empty default keeps signed-out and
                    // unconfigured states clean).
                    if (state.linkedIdentities.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        val primary = state.linkedIdentities.first()
                        Text(
                            text =
                                stringResource(Res.string.label_signed_in_via) +
                                    ": " +
                                    stringResource(providerLabelKey(primary)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("settingsSignedInVia"),
                        )
                        if (state.linkedIdentities.size > 1) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(Res.string.label_linked_identities),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            state.linkedIdentities.drop(1).forEach { extra ->
                                Text(
                                    text = "• " + stringResource(providerLabelKey(extra)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("settingsLinkedIdentity"),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = { viewModel.onEvent(SettingsEvent.SignOut) },
                        modifier = Modifier.fillMaxWidth().testTag("signOutButton"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(stringResource(Res.string.action_sign_out))
                    }

                    Spacer(Modifier.height(8.dp))

                    // Phase B3: Change password / change email
                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.action_change_password)) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) {
                                    viewModel.onEvent(SettingsEvent.RequestChangePassword)
                                }.testTag("changePasswordButton"),
                    )

                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.action_change_email)) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) {
                                    viewModel.onEvent(SettingsEvent.RequestChangeEmail)
                                }.testTag("changeEmailButton"),
                    )

                    // Pre-alpha A30 — Manage subscription deep link. Required
                    // by Google Play subscription policy ("provide an in-app
                    // path to manage / cancel") and recommended by Apple HIG.
                    // Opens Play Store / App Store native subscription
                    // management UI. Shown to all signed-in users regardless
                    // of Pro subscription state — non-subscribers see an
                    // empty subscriptions list which is informative, not an
                    // error.
                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.action_manage_subscription)) },
                        supportingContent = {
                            Text(stringResource(Res.string.body_manage_subscription_helper))
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) {
                                    subscriptionLauncher.open()
                                }.testTag("manageSubscriptionButton"),
                    )

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(24.dp))

                    // Phase 26.5 (ADR-022 §6.4) — Security section. Houses
                    // the 2FA enable/disable row + (future) biometric +
                    // session-management entries. Gated on `isSignedIn`
                    // because all security controls require an active
                    // session; nothing here makes sense for the unauth
                    // surface.
                    Text(
                        text = stringResource(Res.string.label_security_section),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    val mfaEnrolled = state.mfaStatus is MfaEnrollmentStatus.Enrolled
                    ListItem(
                        headlineContent = {
                            Text(stringResource(Res.string.label_two_factor_auth))
                        },
                        trailingContent = {
                            Text(
                                stringResource(
                                    if (mfaEnrolled) {
                                        Res.string.state_mfa_enabled
                                    } else {
                                        Res.string.state_mfa_disabled
                                    },
                                ),
                            )
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) {
                                    if (mfaEnrolled) {
                                        showDisableMfaConfirmation = true
                                    } else {
                                        onEnableMfaClick()
                                    }
                                }.testTag("twoFactorAuthRow"),
                    )

                    // Phase 26.6 (ADR-022 §6.5) — biometric authentication
                    // entry. Routes to the BiometricSettings screen which
                    // owns the toggle + threshold picker. Trailing state
                    // label reads the preferences flow directly here so
                    // the row reflects truth without re-entering the
                    // screen.
                    val biometricEnabled by biometricSettingsViewModel.state.collectAsState()
                    ListItem(
                        headlineContent = {
                            Text(stringResource(Res.string.title_biometric_settings))
                        },
                        trailingContent = {
                            Text(
                                stringResource(
                                    if (biometricEnabled.enabled) {
                                        Res.string.state_biometric_enabled
                                    } else {
                                        Res.string.state_biometric_disabled
                                    },
                                ),
                            )
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) {
                                    onBiometricSettingsClick()
                                }.testTag("biometricSettingsRow"),
                    )

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(24.dp))
                }

                // About / Legal section — Phase E2
                Text(
                    text = stringResource(Res.string.label_about_section),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                val uriHandler = LocalUriHandler.current

                ListItem(
                    headlineContent = { Text(stringResource(Res.string.action_privacy_policy)) },
                    leadingContent = { Icon(Icons.Filled.Lock, contentDescription = null) },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) {
                                uriHandler.openUri(URL_PRIVACY_POLICY)
                            }.testTag("privacyPolicyButton"),
                )

                ListItem(
                    headlineContent = { Text(stringResource(Res.string.action_terms_of_service)) },
                    leadingContent = { Icon(Icons.Filled.Description, contentDescription = null) },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) {
                                uriHandler.openUri(URL_TERMS_OF_SERVICE)
                            }.testTag("termsOfServiceButton"),
                )

                // Pre-alpha A35 — Help & FAQ page. Opens the static
                // HTML page (`docs/public/help/`) in the system browser.
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.action_help_faq)) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.Help, contentDescription = null) },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) {
                                uriHandler.openUri(URL_HELP)
                            }.testTag("helpFaqButton"),
                )

                // Pre-Phase-40 A33 — Open Source Licenses. Now an in-app
                // screen (NavGraph `OssLicenses` route) rendering the
                // build-time-generated AboutLibraries attribution list
                // instead of opening the static HTML page in the browser.
                // No `OpenInNew` trailing icon — this no longer leaves
                // the app. The static `docs/public/licenses/` page is
                // retained as the reviewer-facing (no-install) surface.
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.action_open_source_licenses)) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.Help, contentDescription = null) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) {
                                onOssLicensesClick()
                            }.testTag("openSourceLicensesButton"),
                )

                // Pre-alpha A34 — Contact Support row. Opens the user's
                // default mail composer with a mailto: URL pre-filled
                // with the support email + diagnostic context block
                // (app version / OS / device / locale). Distinct from
                // the Beta-section "Send Feedback" entry: this is the
                // general-purpose support channel (account questions,
                // refunds, DMCA notices), Send Feedback is for
                // structured bug reports.
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.action_contact_support)) },
                    supportingContent = {
                        Text(stringResource(Res.string.body_contact_support_helper))
                    },
                    leadingContent = { Icon(Icons.Filled.Email, contentDescription = null) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) {
                                supportContactLauncher.openSupportEmail(deviceContext)
                            }.testTag("contactSupportButton"),
                )

                // Phase 41.3b (ADR-016 §5.1) — Skeinly Pro section. Always-on
                // entry to the paywall, NOT gated on `BuildFlags.isBeta`
                // unlike the Beta section below. F1 monetization is a
                // production feature available on every release channel.
                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(24.dp))

                Text(
                    text = stringResource(Res.string.label_paywall_section),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                ListItem(
                    headlineContent = { Text(stringResource(Res.string.label_subscribe_to_pro)) },
                    leadingContent = { Icon(Icons.Filled.Star, contentDescription = null) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) {
                                viewModel.onEvent(SettingsEvent.SubscribeToProTapped)
                                onSubscribeToProClick()
                            }.testTag("subscribeToProButton"),
                )

                // Phase 41.4 (ADR-016 §5.2) — Manage Symbol Packs entry.
                // Always-on, NOT beta-gated. Sits inside the Skeinly Pro
                // section because the pack-management surface is the
                // primary place a non-Pro user discovers what subscribing
                // would unlock.
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.label_pack_management)) },
                    leadingContent = { Icon(Icons.Filled.Download, contentDescription = null) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { onManagePacksClick() }
                            .testTag("managePacksButton"),
                )

                // Beta section — Phase 39.4 (ADR-015 §6). Holds diagnostic
                // data sharing toggle + Send Feedback. Gated on
                // [BuildFlags.isBeta] so production binaries surface
                // neither — Phase 27a no-tracking stance for v1.0+. The
                // pre-39.4 "Privacy" header / `label_allow_analytics`
                // copy was renamed because what we collect on beta is
                // diagnostic data, not "usage analytics" in general.
                if (BuildFlags.isBeta) {
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(24.dp))

                    Text(
                        text = stringResource(Res.string.label_beta_section),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    ListItem(
                        headlineContent = {
                            Text(stringResource(Res.string.label_diagnostic_data_sharing))
                        },
                        trailingContent = {
                            // `onCheckedChange = null` so the Switch is purely
                            // visual — the row's `clickable` is the single
                            // dispatch site. With both wired, a tap on the
                            // Switch thumb fires onCheckedChange (with the
                            // new value) AND the row's clickable (toggling
                            // the already-flipped value), cancelling intent.
                            Switch(
                                checked = state.analyticsOptIn,
                                onCheckedChange = null,
                                modifier = Modifier.testTag("analyticsOptInSwitch"),
                            )
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Switch) {
                                    viewModel.onEvent(
                                        SettingsEvent.SetAnalyticsOptIn(!state.analyticsOptIn),
                                    )
                                },
                    )
                    Text(
                        text = stringResource(Res.string.body_diagnostic_data_explanation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )

                    Spacer(Modifier.height(8.dp))

                    // Phase 24.2c (ADR-017 §3.6) — Notifications row.
                    // Trailing label reflects current OS permission state
                    // (read on init via NotificationPermissionViewModel
                    // refreshStatus). Tap routes to OS app-notification
                    // settings so denied users can re-enable. The
                    // pre-permission explainer dialog is wired below at
                    // the screen scope; entry-point invocation lands in
                    // 24.2c-3.
                    val notificationStatusLabel =
                        when (notificationState.osStatus) {
                            io.github.b150005.skeinly.notifications.NotificationPermissionStatus.GRANTED ->
                                stringResource(Res.string.state_notifications_enabled)
                            io.github.b150005.skeinly.notifications.NotificationPermissionStatus.DENIED,
                            io.github.b150005.skeinly.notifications.NotificationPermissionStatus.NOT_DETERMINED,
                            ->
                                stringResource(Res.string.state_notifications_disabled)
                        }
                    ListItem(
                        headlineContent = {
                            Text(stringResource(Res.string.label_notifications_settings_row))
                        },
                        supportingContent = {
                            Text(stringResource(Res.string.body_notifications_setting_explanation))
                        },
                        trailingContent = { Text(notificationStatusLabel) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) {
                                    notificationViewModel.onEvent(
                                        io.github.b150005.skeinly.ui.notifications
                                            .NotificationPermissionEvent
                                            .OpenOsSettingsRequested,
                                    )
                                }.testTag("notificationsSettingsRow"),
                    )
                    Text(
                        text = stringResource(Res.string.body_notifications_disabled_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )

                    Spacer(Modifier.height(8.dp))

                    // Phase 39.5 wires this to the BugReportPreviewScreen
                    // + GitHub Issue prefill launcher. Today the callback
                    // is a no-op (default `onSendFeedback = {}` at the
                    // screen signature), so the entry renders correctly
                    // and a tester can discover the feature without an
                    // observable broken UX behind the row.
                    ListItem(
                        headlineContent = {
                            Text(stringResource(Res.string.action_send_feedback))
                        },
                        leadingContent = {
                            Icon(Icons.Filled.Feedback, contentDescription = null)
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { onSendFeedback() }
                                .testTag("sendFeedbackButton"),
                    )
                    Text(
                        text = stringResource(Res.string.body_send_feedback_explanation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                // Phase 25.3 (ADR-024 §(e)) — Privacy section. Sits
                // ABOVE Danger Zone so the destructiveness gradient
                // reads top-to-bottom: Connections (non-destructive) →
                // Wipe (content-destructive) → Delete Account
                // (identity-destructive). Auth-only because friend graph
                // is auth-scoped — there's nothing to manage when
                // signed out.
                if (state.isSignedIn) {
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(24.dp))

                    Text(
                        text = stringResource(Res.string.label_privacy_section),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    ListItem(
                        headlineContent = {
                            Text(stringResource(Res.string.title_connections))
                        },
                        supportingContent = {
                            Text(stringResource(Res.string.body_connections_settings_row))
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { onConnectionsClick() }
                                .testTag("connectionsSettingsRow"),
                    )

                    // Phase 39 (ADR-021 §D4) — Blocked Users row. Sits
                    // directly below Connections (both are privacy
                    // graph-management entries; non-destructive, so
                    // above the Danger Zone gradient).
                    ListItem(
                        headlineContent = {
                            Text(stringResource(Res.string.title_blocked_users))
                        },
                        supportingContent = {
                            Text(stringResource(Res.string.body_blocked_users_settings_row))
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { onBlockedUsersClick() }
                                .testTag("blockedUsersSettingsRow"),
                    )

                    // Pre-Phase-40 A20 Option B — "Export My Data" row.
                    // Sits below Blocked Users in the Privacy section:
                    // it only READS the user's data (GDPR Art. 20 /
                    // CCPA), so it is non-destructive and must NOT be in
                    // the Danger Zone with Wipe / Delete Account.
                    ListItem(
                        headlineContent = {
                            Text(stringResource(Res.string.title_export_data_settings_row))
                        },
                        supportingContent = {
                            Text(stringResource(Res.string.body_export_data_settings_row))
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { onExportDataClick() }
                                .testTag("exportDataSettingsRow"),
                    )
                }

                // B3 (Phase 39.1): Danger zone is auth-only — same gating
                // as the Account section above.
                if (state.isSignedIn) {
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(24.dp))

                    // Danger zone
                    Text(
                        text = stringResource(Res.string.label_danger_zone),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    // Phase 27.2 (ADR-023 §UX) — "Delete all my data" row.
                    // Sits ABOVE "Delete Account" — less-destructive option
                    // listed first per Material + HIG sectioning. Routes to
                    // the WipeData confirmation flow which prompts twice
                    // (preservation-matrix modal + phrase typing) before
                    // firing the RPC.
                    ListItem(
                        headlineContent = {
                            Text(stringResource(Res.string.title_wipe_data_settings_row))
                        },
                        supportingContent = {
                            Text(stringResource(Res.string.body_wipe_data_settings_row))
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { onWipeDataClick() }
                                .testTag("wipeDataRow"),
                    )

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = stringResource(Res.string.body_delete_account_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )

                    Button(
                        onClick = { showDeleteConfirmation = true },
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        enabled = !state.isDeletingAccount,
                        modifier = Modifier.fillMaxWidth().testTag("deleteAccountButton"),
                    ) {
                        if (state.isDeletingAccount) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                color = MaterialTheme.colorScheme.onError,
                                strokeWidth = 2.dp,
                            )
                        }
                        Text(
                            if (state.isDeletingAccount) {
                                stringResource(Res.string.state_deleting_account)
                            } else {
                                stringResource(Res.string.action_delete_account)
                            },
                        )
                    }
                }
            }
        }
    }

    // Phase 24.2c (ADR-017 §3.6) — pre-permission explainer. Surfaces
    // when the ViewModel flips isExplainerVisible (entry-point trigger
    // wires lands in 24.2c-3; today only mounted from this screen so
    // no surface ever sets it true unless the entry-point screens
    // dispatch TriggerEncountered explicitly).
    io.github.b150005.skeinly.ui.notifications.NotificationPermissionExplainerDialog(
        isVisible = notificationState.isExplainerVisible,
        isRequestingPermission = notificationState.isRequestingPermission,
        onAccept = {
            notificationViewModel.onEvent(
                io.github.b150005.skeinly.ui.notifications
                    .NotificationPermissionEvent
                    .UserAcceptedExplainer,
            )
        },
        onDismiss = {
            notificationViewModel.onEvent(
                io.github.b150005.skeinly.ui.notifications
                    .NotificationPermissionEvent
                    .UserDismissedExplainer,
            )
        },
    )

    if (state.pendingChangePasswordDialog) {
        ChangePasswordDialog(
            isSubmitting = state.isChangingPassword,
            onConfirm = { newPassword ->
                viewModel.onEvent(SettingsEvent.ConfirmChangePassword(newPassword))
            },
            onDismiss = { viewModel.onEvent(SettingsEvent.DismissChangePassword) },
        )
    }

    if (state.pendingChangeEmailDialog) {
        ChangeEmailDialog(
            isSubmitting = state.isChangingEmail,
            onConfirm = { newEmail ->
                viewModel.onEvent(SettingsEvent.ConfirmChangeEmail(newEmail))
            },
            onDismiss = { viewModel.onEvent(SettingsEvent.DismissChangeEmail) },
        )
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(Res.string.dialog_delete_account_title)) },
            text = { Text(stringResource(Res.string.dialog_delete_account_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.onEvent(SettingsEvent.DeleteAccountConfirmed)
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Text(stringResource(Res.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    if (showDisableMfaConfirmation) {
        AlertDialog(
            onDismissRequest = { showDisableMfaConfirmation = false },
            title = { Text(stringResource(Res.string.title_mfa_disable_confirm)) },
            text = { Text(stringResource(Res.string.body_mfa_disable_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisableMfaConfirmation = false
                        viewModel.onEvent(SettingsEvent.DisableMfaConfirmed)
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    modifier = Modifier.testTag("disableMfaConfirmButton"),
                ) {
                    Text(stringResource(Res.string.action_disable_mfa))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableMfaConfirmation = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ChangePasswordDialog(
    isSubmitting: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newPassword by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("changePasswordDialog"),
        title = { Text(stringResource(Res.string.dialog_change_password_title)) },
        text = {
            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it },
                label = { Text(stringResource(Res.string.label_new_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth().testTag("newPasswordField"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(newPassword) },
                enabled = !isSubmitting && newPassword.isNotBlank(),
                modifier = Modifier.testTag("confirmChangePasswordButton"),
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
                } else {
                    Text(stringResource(Res.string.action_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ChangeEmailDialog(
    isSubmitting: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newEmail by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("changeEmailDialog"),
        title = { Text(stringResource(Res.string.dialog_change_email_title)) },
        text = {
            OutlinedTextField(
                value = newEmail,
                onValueChange = { newEmail = it },
                label = { Text(stringResource(Res.string.label_new_email)) },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done,
                    ),
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth().testTag("newEmailField"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(newEmail) },
                enabled = !isSubmitting && newEmail.isNotBlank(),
                modifier = Modifier.testTag("confirmChangeEmailButton"),
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
                } else {
                    Text(stringResource(Res.string.action_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

/**
 * Phase 26.6 (ADR-022 §6.6) — closed-set mapping from [LinkedIdentity]
 * to the user-facing copy key. Apple relay split (the
 * `@privaterelay.appleid.com` email surface) routes to a distinct
 * key so privacy-aware users see the relay disclosure verbatim
 * rather than the bare "Apple" label.
 */
private fun providerLabelKey(identity: LinkedIdentity): org.jetbrains.compose.resources.StringResource =
    when (identity.provider) {
        AuthProviderKind.Apple ->
            if (identity.isAppleRelay) {
                Res.string.state_signed_in_via_apple_relay
            } else {
                Res.string.state_signed_in_via_apple
            }
        AuthProviderKind.Google -> Res.string.state_signed_in_via_google
        AuthProviderKind.Email -> Res.string.state_signed_in_via_email
    }
