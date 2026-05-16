package io.github.b150005.skeinly.data.remote

import io.github.b150005.skeinly.domain.model.DataExportBundle
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Pre-Phase-40 A20 Option B (docs/en/ops/data-export-sop.md §Scope
 * deferrals) — read-only port for the `export-my-data` Edge Function.
 *
 * Exists as an interface so
 * [io.github.b150005.skeinly.data.repository.DataExportRepositoryImpl]
 * tests can inject an in-memory fake without standing up Supabase. Same
 * precedent as [WipeDataRemoteOperations] (Phase 27.1) and
 * [UgcModerationRemoteOperations] (Phase 39).
 */
interface DataExportRemoteOperations {
    /**
     * Calls the `export-my-data` Edge Function. supabase-kt's Functions
     * plugin auto-attaches the caller's session JWT (`verify_jwt =
     * true`) — the Edge Function derives the caller id from the JWT
     * `sub` exclusively (no client-supplied id; an IDOR is structurally
     * impossible). Throws [DataExportException] on any
     * application-level rejection (envelope `ok = false`) or
     * Supabase-platform failure (non-200) so the repository can map the
     * carried [DataExportException.code] to a typed `UseCaseError`.
     * Throws other exceptions on transport failure.
     */
    suspend fun exportOwnData(): DataExportBundle
}

/**
 * Carries the `export-my-data` Edge Function's closed error code
 * ([code]) so [io.github.b150005.skeinly.data.repository.DataExportRepositoryImpl]
 * maps `RATE_LIMITED` / `UNAUTHORIZED` to specific `UseCaseError`s
 * rather than a generic catch-all. [code] is null for a non-200
 * Supabase-platform failure (function undeployed / mid-rotation / 5xx).
 * Mirrors [UgcReportSubmissionException].
 */
class DataExportException(
    val code: String?,
    message: String,
) : Exception(message)

/**
 * Pre-Phase-40 A20 Option B — Supabase implementation of
 * [DataExportRemoteOperations].
 *
 * Uses supabase-kt's `functions.invoke()` (NOT a hand-rolled Ktor POST)
 * so the session JWT is auto-attached to match the Edge Function's
 * `verify_jwt = true` — same rationale as [RemoteUgcModerationDataSource].
 * The plugin returns Ktor's `HttpResponse` raw and does NOT throw on
 * non-2xx; we inspect the status + envelope explicitly.
 *
 * The envelope's `bundle` is arbitrary JSON (the server owns the
 * shape). We decode it as a [JsonElement] and re-encode it with a
 * pretty-printing [Json] so the file the user downloads is
 * human-readable, while the [injectedJson] used for envelope decoding
 * stays the compact Koin-shared instance. The body is empty `{}` — the
 * Edge Function ignores it (identity comes from the JWT only).
 */
class RemoteDataExportDataSource(
    private val supabaseClient: SupabaseClient,
    private val injectedJson: Json,
) : DataExportRemoteOperations {
    private val prettyJson = Json(from = injectedJson) { prettyPrint = true }

    override suspend fun exportOwnData(): DataExportBundle {
        val response: HttpResponse =
            supabaseClient.functions.invoke(EDGE_FUNCTION) {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }

        val bodyText =
            try {
                response.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw DataExportException(
                    code = null,
                    message = "export-my-data response body unreadable: ${e.message ?: e::class.simpleName.orEmpty()}",
                )
            }

        // Non-200 ⇒ Supabase-platform breakage (function undeployed,
        // mid-rotation, 5xx, or a verify_jwt 401 from an expired token
        // racing the repository's pre-flight check). A 401 is mapped to
        // UNAUTHORIZED so the repository routes it to SignInRequired;
        // anything else is code-less ⇒ generic Unknown.
        if (response.status.value != HTTP_OK) {
            throw DataExportException(
                code = if (response.status.value == HTTP_UNAUTHORIZED) CODE_UNAUTHORIZED else null,
                message = "export-my-data returned HTTP ${response.status.value}: ${bodyText.take(200)}",
            )
        }

        val envelope = injectedJson.decodeFromString(ExportResponse.serializer(), bodyText)
        if (!envelope.ok || envelope.bundle == null) {
            throw DataExportException(
                code = envelope.code,
                message = envelope.message ?: "export-my-data rejected the request",
            )
        }

        return DataExportBundle(
            bundleJson = prettyJson.encodeToString(JsonElement.serializer(), envelope.bundle),
            summary = envelope.summary ?: emptyMap(),
            totalRows = envelope.totalRows ?: 0,
        )
    }

    @Serializable
    private data class ExportResponse(
        val ok: Boolean,
        val bundle: JsonElement? = null,
        val summary: Map<String, Int>? = null,
        @SerialName("total_rows") val totalRows: Int? = null,
        val code: String? = null,
        val message: String? = null,
    )

    internal companion object {
        internal const val EDGE_FUNCTION = "export-my-data"
        private const val HTTP_OK = 200
        private const val HTTP_UNAUTHORIZED = 401

        // Edge Function closed error codes — single source of truth so
        // the repository's code→UseCaseError mapping cannot silently
        // drift from the wire contract (matches BugReportProxyClient's
        // constant-backed code surface).
        internal const val CODE_UNAUTHORIZED = "UNAUTHORIZED"
        internal const val CODE_RATE_LIMITED = "RATE_LIMITED"
    }
}
