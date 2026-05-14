package io.github.b150005.skeinly.auth

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference

/**
 * Phase 26.2 (ADR-022 §6.2) — Android actual for [OAuthClient].
 *
 * Wires `androidx.credentials.CredentialManager` + Google Identity
 * for Android (`GetGoogleIdOption` + `GoogleIdTokenCredential`).
 *
 * Activity context lifecycle (mirrors Phase 24.2e `PushTokenRegistrar`):
 *   - `MainActivity.onCreate` calls [attachActivity] with `this`.
 *   - `MainActivity.onDestroy` calls [detachActivity].
 *   - In-flight `acquireGoogleIdToken()` callers seeing an empty
 *     Activity reference get [OAuthIdTokenResult.Failure] (typically
 *     a config-change race during a stale view-model job).
 *
 * Web Client ID source: `R.string.default_web_client_id` is generated
 * automatically by the `google-services` Gradle plugin from
 * `androidApp/src/<variant>/google-services.json`. The Web Client ID
 * is the audience field of the Google ID token; Supabase verifies
 * the token against the same value configured on the Supabase Auth
 * Google provider in the Dashboard.
 *
 * The mutex prevents two concurrent `getCredential` calls — the
 * Credential Manager UI is modal so a second concurrent call would
 * fail at the OS level; the mutex short-circuits before that.
 *
 * @property appContext Application context used as fallback for the
 *                      Credential Manager when no Activity is
 *                      attached (e.g. background-init call from
 *                      Koin). Actual `getCredential(...)` requires an
 *                      Activity for the UI; the fallback path
 *                      surfaces Failure.
 * @property webClientIdProvider Closure that returns the Web Client
 *                               ID. Lazy so Koin can wire it from a
 *                               `Context.getString(R.string.default_web_client_id)`
 *                               at use-time (the R class lives on
 *                               androidApp, not shared).
 */
actual class OAuthClient(
    private val appContext: Context,
    private val webClientIdProvider: () -> String,
) {
    private var activityRef: WeakReference<Activity> = WeakReference(null)
    private val callMutex = Mutex()

    fun attachActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun detachActivity() {
        activityRef.clear()
    }

    actual suspend fun acquireGoogleIdToken(): OAuthIdTokenResult =
        callMutex.withLock {
            val activity = activityRef.get()
            val context: Context = activity ?: appContext
            val webClientId =
                runCatching { webClientIdProvider() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@withLock OAuthIdTokenResult.Failure(
                        message =
                            "Google Web Client ID is empty (R.string.default_web_client_id). " +
                                "Verify google-services.json contains an oauth_client entry of " +
                                "client_type 3 (web). On debug variant builds this typically " +
                                "means the Skeinly-Dev Firebase project lacks an OAuth Client " +
                                "registration — Tech Debt: 'Skeinly-Dev OAuth Sign-In setup " +
                                "deferred' in CLAUDE.md.",
                    )

            val googleOption =
                GetGoogleIdOption
                    .Builder()
                    .setServerClientId(webClientId)
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .build()
            val request =
                GetCredentialRequest
                    .Builder()
                    .addCredentialOption(googleOption)
                    .build()

            val credentialManager = CredentialManager.create(context)
            try {
                val response = credentialManager.getCredential(context, request)
                val credential = response.credential
                if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    return@withLock OAuthIdTokenResult.Failure(
                        message = "Unexpected credential type: ${credential.type}",
                    )
                }
                val googleCredential =
                    try {
                        GoogleIdTokenCredential.createFrom(credential.data)
                    } catch (e: GoogleIdTokenParsingException) {
                        return@withLock OAuthIdTokenResult.Failure(
                            message = "Failed to parse Google ID token: ${e.message.orEmpty()}",
                        )
                    }
                // Phase 26.2 ships without an explicit nonce — Google
                // accepts nonceless ID tokens. Phase 26+ may revisit
                // if Supabase logs reveal replay-risk patterns; the
                // GetGoogleIdOption.Builder().setNonce(...) seam stays
                // open as a one-line forward-compat hook.
                OAuthIdTokenResult.Success(
                    idToken = googleCredential.idToken,
                    nonce = null,
                )
            } catch (_: GetCredentialCancellationException) {
                OAuthIdTokenResult.UserCancelled
            } catch (e: NoCredentialException) {
                OAuthIdTokenResult.Failure(
                    message =
                        "No Google account available on this device. " +
                            "Add one in Settings → Google → Add account.",
                )
            } catch (e: GetCredentialException) {
                OAuthIdTokenResult.Failure(message = e.message.orEmpty())
            } catch (e: Throwable) {
                OAuthIdTokenResult.Failure(message = e.message.orEmpty())
            }
        }
}
