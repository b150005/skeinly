package io.github.b150005.skeinly.di

import io.github.b150005.skeinly.biometric.BiometricAuthenticator
import io.github.b150005.skeinly.biometric.BiometricGuardian
import io.github.b150005.skeinly.biometric.BiometricPromptCopy
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.body_biometric_account_deletion_reason
import io.github.b150005.skeinly.generated.resources.body_biometric_mfa_disable_reason
import io.github.b150005.skeinly.generated.resources.body_biometric_reauth_reason
import io.github.b150005.skeinly.generated.resources.title_biometric_settings
import org.jetbrains.compose.resources.getString
import org.koin.dsl.module

/**
 * Phase 26.6 (ADR-022 §6.5) — biometric re-auth + sensitive-action gate.
 *
 * The Guardian is a process-wide singleton: the application-scope
 * coroutine [io.github.b150005.skeinly.biometric.startBiometricLifecycleBridge]
 * collects from [io.github.b150005.skeinly.platform.AppLifecycleObserver]
 * and drives the Guardian's `onBackgrounded` / `requireForResume`
 * calls. Sensitive-action call sites (DeleteAccountUseCase,
 * SettingsViewModel) resolve the same singleton and call
 * `requireForAction`.
 *
 * Lambda-seam wiring: the Guardian doesn't hold a direct reference to
 * [BiometricAuthenticator] (which is an `expect class` and surfaces
 * `final` to commonTest). Same insulation strategy applied to CMP
 * string resolution via [BiometricPromptCopy] — the Guardian stays
 * pure commonMain, testable with simple lambda stubs.
 */
val biometricModule =
    module {
        single {
            val authenticator: BiometricAuthenticator = get()
            BiometricGuardian(
                queryAvailability = { authenticator.canAuthenticate() },
                performAuthentication = { title, reason -> authenticator.authenticate(title, reason) },
                preferences = get(),
                resolveString = { copy ->
                    when (copy) {
                        BiometricPromptCopy.Title -> getString(Res.string.title_biometric_settings)
                        BiometricPromptCopy.ResumeReason -> getString(Res.string.body_biometric_reauth_reason)
                        BiometricPromptCopy.AccountDeletionReason ->
                            getString(Res.string.body_biometric_account_deletion_reason)
                        BiometricPromptCopy.MfaDisableReason -> getString(Res.string.body_biometric_mfa_disable_reason)
                    }
                },
            )
        }
    }
