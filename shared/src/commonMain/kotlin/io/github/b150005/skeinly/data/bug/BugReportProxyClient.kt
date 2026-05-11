package io.github.b150005.skeinly.data.bug

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.util.network.UnresolvedAddressException
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Phase 39 W5b (ADR-020 §3) — KMP client for the `submit-bug-report`
 * Supabase Edge Function. POSTs the title + body to the Edge
 * Function, which authenticates as the Skeinly Feedback GitHub App
 * and creates an Issue on b150005/skeinly server-side.
 *
 * Auth: sends `apikey: <publishable_key>` (NOT `Authorization: Bearer`)
 * because the Edge Function runs with `verify_jwt = false` — see
 * ADR-020 §Q4 for the auth-model rationale. Abuse prevention is the
 * Edge Function's per-source rate limit (ADR-020 §2).
 *
 * Empty `supabaseUrl` or `supabasePublishableKey` (local-only dev
 * builds) short-circuits to [BugReportProxyException.ConfigMissing].
 * Application errors come back as a HTTP 200 `{ok: false, code,
 * message}` envelope; non-200 means Supabase-platform breakage and
 * maps to [BugReportProxyException.Server]. [CancellationException]
 * is always rethrown per Kotlin coroutine discipline.
 */
class BugReportProxyClient(
    private val httpClient: HttpClient,
    private val supabaseUrl: String,
    private val supabasePublishableKey: String,
    private val json: Json,
) {
    /**
     * POST the report to the Edge Function. Returns [Result.success]
     * with [SubmitOutcome] on a successful Issue creation, or
     * [Result.failure] wrapping a [BugReportProxyException] subclass.
     *
     * The split between [Result] and [BugReportProxyException]'s sealed
     * hierarchy lets ViewModels do `result.fold(onSuccess = …,
     * onFailure = { e -> when (e as BugReportProxyException) … })` for
     * exhaustive branch handling without a separate result-state class
     * at the data layer.
     */
    suspend fun submit(
        title: String,
        body: String,
    ): Result<SubmitOutcome> {
        if (supabaseUrl.isBlank() || supabasePublishableKey.isBlank()) {
            return Result.failure(
                BugReportProxyException.ConfigMissing(
                    "Supabase URL or publishable key is empty — bug reporting requires a production build",
                ),
            )
        }
        val url = "$supabaseUrl/functions/v1/submit-bug-report"
        val response: HttpResponse =
            try {
                httpClient.post(url) {
                    contentType(ContentType.Application.Json)
                    headers {
                        // `apikey` header (NOT `Authorization: Bearer`)
                        // — ADR-020 §Q4. Edge Function runs with
                        // verify_jwt = false; a future
                        // user-attribution build could add
                        // `Authorization: Bearer <user_jwt>` alongside
                        // without touching this header.
                        append("apikey", supabasePublishableKey)
                    }
                    setBody(json.encodeToString(SubmitRequestWire.serializer(), SubmitRequestWire(title, body)))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (offline: UnresolvedAddressException) {
                return Result.failure(BugReportProxyException.Offline(offline.message ?: "DNS resolution failed"))
            } catch (network: IOException) {
                return Result.failure(BugReportProxyException.Offline(network.message ?: "Network unavailable"))
            } catch (other: Throwable) {
                return Result.failure(BugReportProxyException.Unknown(other.message ?: other::class.simpleName.orEmpty()))
            }

        val bodyText: String =
            try {
                response.body()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (other: Throwable) {
                return Result.failure(
                    BugReportProxyException.Unknown("response body unreadable: ${other.message ?: other::class.simpleName.orEmpty()}"),
                )
            }

        // Supabase-platform failure: non-200 from the edge means the
        // function itself is unreachable / undeployed / mid-rotation,
        // NOT an application-level rejection. Map to Server with the
        // status code for diagnostics.
        if (response.status != HttpStatusCode.OK) {
            return Result.failure(
                BugReportProxyException.Server(
                    "edge function returned HTTP ${response.status.value}: ${bodyText.take(200)}",
                ),
            )
        }

        val envelope =
            try {
                json.decodeFromString(SubmitResponseWire.serializer(), bodyText)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (other: Throwable) {
                return Result.failure(
                    BugReportProxyException.Unknown("response envelope unparseable: ${other.message ?: other::class.simpleName.orEmpty()}"),
                )
            }

        return if (envelope.ok && envelope.issueNumber != null && envelope.htmlUrl != null) {
            Result.success(SubmitOutcome(issueNumber = envelope.issueNumber, htmlUrl = envelope.htmlUrl))
        } else {
            Result.failure(mapErrorCode(envelope.code, envelope.message))
        }
    }

    private fun mapErrorCode(
        code: String?,
        message: String?,
    ): BugReportProxyException {
        val safeMessage = message.orEmpty()
        return when (code) {
            "RATE_LIMITED" -> BugReportProxyException.RateLimited(safeMessage)
            "VALIDATION_FAILED" -> BugReportProxyException.ValidationFailed(safeMessage)
            "GITHUB_AUTH_FAILED",
            "GITHUB_API_FAILED",
            -> BugReportProxyException.Server(safeMessage)
            "CONFIG_MISSING" -> BugReportProxyException.ConfigMissing(safeMessage)
            else -> BugReportProxyException.Unknown("unknown code: $code | message: $safeMessage")
        }
    }
}

/**
 * Discriminated result wrapped in [Result] by [BugReportProxyClient.submit].
 *
 * `issueNumber` and `htmlUrl` together let the UI link the user to
 * their submitted Issue ("Bug report submitted: #123 — tap to open").
 */
data class SubmitOutcome(
    val issueNumber: Int,
    val htmlUrl: String,
)

/**
 * Sealed failure hierarchy. ViewModels map each subclass to a
 * localized error message; the `when` over the sealed type is
 * exhaustive so adding a new failure mode is a compile-time prompt to
 * extend the UI mapping.
 *
 * Subclass-by-subclass meaning:
 * - [Offline]: the device could not reach Supabase (DNS failure, no
 *   network, captive portal). Retryable by the user.
 * - [RateLimited]: per-source quota exhausted on the Edge Function
 *   side. Retryable after the embedded wait window.
 * - [ValidationFailed]: title / body / labels failed Edge Function
 *   validation (length cap, newline in title, etc.). NOT retryable
 *   without editing the description — ViewModel should surface the
 *   embedded message.
 * - [ConfigMissing]: Edge Function deployed but missing GitHub App
 *   secrets. Non-retryable until the operator finishes setup.
 * - [Server]: Supabase or GitHub-side failure (5xx, auth-failed,
 *   API-failed). Retryable with backoff.
 * - [Unknown]: catch-all for unparseable responses or unexpected
 *   exception types. Logged for diagnostics.
 */
sealed class BugReportProxyException(
    message: String,
) : Exception(message) {
    class Offline(
        message: String,
    ) : BugReportProxyException(message)

    class RateLimited(
        message: String,
    ) : BugReportProxyException(message)

    class ValidationFailed(
        message: String,
    ) : BugReportProxyException(message)

    class ConfigMissing(
        message: String,
    ) : BugReportProxyException(message)

    class Server(
        message: String,
    ) : BugReportProxyException(message)

    class Unknown(
        message: String,
    ) : BugReportProxyException(message)
}

// ---------------------------------------------------------------------
// Wire types — private to keep the public API focused on SubmitOutcome.
// ---------------------------------------------------------------------

@Serializable
private data class SubmitRequestWire(
    val title: String,
    val body: String,
)

@Serializable
private data class SubmitResponseWire(
    val ok: Boolean,
    @SerialName("issue_number") val issueNumber: Int? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val code: String? = null,
    val message: String? = null,
)
