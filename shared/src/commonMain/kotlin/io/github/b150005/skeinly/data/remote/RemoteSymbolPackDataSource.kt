package io.github.b150005.skeinly.data.remote

import io.github.b150005.skeinly.domain.model.SymbolPack
import io.github.b150005.skeinly.domain.model.SymbolPackPayload
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Phase 41.2b (ADR-016 §3.3, §4.3) — read surface contract for the symbol
 * pack catalog + Edge Function download mediation.
 *
 * The interface exists so the [io.github.b150005.skeinly.data.sync.SymbolPackSyncManager]
 * test can inject an in-memory fake without standing up Supabase. Same
 * precedent as [SubscriptionRemoteOperations] (Phase 41.2a) and
 * [io.github.b150005.skeinly.domain.repository.SuggestionMergeOperations]
 * (Phase 38.4).
 */
interface SymbolPackRemoteOperations {
    /**
     * GET `symbol_packs` — RLS open-read, returns every pack the catalog
     * knows about (free + pro). Throws on transport/decode failure.
     */
    suspend fun fetchManifest(): List<SymbolPack>

    /**
     * Calls the `request-pack-download` Edge Function with [packId], on 200
     * fetches the returned signed URL, parses the response body as
     * [SymbolPackPayload]. The 4 distinct HTTP error shapes the Edge Function
     * emits (401 / 403 / 404 / 429) plus transport / parse failures all map
     * to typed [SymbolPackDownloadResult.Failure] subtypes — no exceptions
     * leak to the caller (modulo [CancellationException]).
     */
    suspend fun requestDownload(packId: String): SymbolPackDownloadResult
}

/**
 * Supabase implementation of [SymbolPackRemoteOperations].
 *
 * **Why supabase-kt's `functions.invoke()` instead of a hand-rolled Ktor POST.**
 * The `Functions` plugin auto-attaches the current session JWT as
 * `Authorization: Bearer <user_jwt>` matching the Edge Function's
 * `verify_jwt: true` deploy flag — going hand-rolled would force us to
 * thread the JWT through manually + duplicate the URL construction logic.
 * The plugin returns Ktor's `HttpResponse` raw; we inspect the status code
 * directly (it does NOT throw on non-2xx by default).
 *
 * **Why a separate injected [HttpClient] for the signed-URL fetch.** The
 * underlying Ktor client supabase-kt holds is annotated `@SupabaseInternal`
 * (cannot be touched from commonMain), and supabase-kt's public
 * `httpClient.get(url)` helper prefixes the configured Supabase URL — wrong
 * shape for an absolute pre-signed Storage URL. A separately-injected
 * Ktor [HttpClient] keeps the path clean. The signed URL contains its own
 * `?token=...` so an absent Authorization header is correct (Storage's
 * signed-URL endpoint reads the query token only).
 */
class RemoteSymbolPackDataSource(
    private val supabaseClient: SupabaseClient,
    private val httpClient: HttpClient,
    private val json: Json,
) : SymbolPackRemoteOperations {
    private val table get() = supabaseClient.postgrest["symbol_packs"]

    override suspend fun fetchManifest(): List<SymbolPack> = table.select().decodeList()

    override suspend fun requestDownload(packId: String): SymbolPackDownloadResult {
        val response =
            try {
                supabaseClient.functions.invoke("request-pack-download") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(RequestPackDownloadBody(packId)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return SymbolPackDownloadResult.Failure.Network(e)
            }

        return mapEdgeResponse(response, packId)
    }

    private suspend fun mapEdgeResponse(
        response: HttpResponse,
        packId: String,
    ): SymbolPackDownloadResult =
        when (response.status.value) {
            200 -> handleSuccess(response, packId)
            401 -> SymbolPackDownloadResult.Failure.Unauthenticated
            403 -> SymbolPackDownloadResult.Failure.ProEntitlementRequired(packId)
            404 -> SymbolPackDownloadResult.Failure.PackNotFound(packId)
            429 -> handleRateLimited(response)
            else -> SymbolPackDownloadResult.Failure.Unknown(response.status.value, response.tryReadText())
        }

    private suspend fun handleSuccess(
        response: HttpResponse,
        packId: String,
    ): SymbolPackDownloadResult {
        val envelope =
            try {
                json.decodeFromString<RequestPackDownloadResponse>(response.bodyAsText())
            } catch (e: SerializationException) {
                return SymbolPackDownloadResult.Failure.Parse(e)
            }

        // Fetch the signed URL. A 403 here means the URL TTL elapsed
        // mid-flight or the token was rejected — surface as
        // ProEntitlementRequired so the caller re-mints (the orchestrator's
        // "skip silently, retry on next sync cycle" path is what we want;
        // a fresh `request-pack-download` call mints a new URL). Any other
        // non-2xx becomes Unknown.
        val payloadResponse =
            try {
                httpClient.get(envelope.payloadUrl) {
                    headers {
                        append(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return SymbolPackDownloadResult.Failure.Network(e)
            }

        when (payloadResponse.status.value) {
            in 200..299 -> Unit
            403 -> return SymbolPackDownloadResult.Failure.ProEntitlementRequired(packId)
            else ->
                return SymbolPackDownloadResult.Failure.Unknown(
                    statusCode = payloadResponse.status.value,
                    body = payloadResponse.tryReadText(),
                )
        }

        val payload =
            try {
                json.decodeFromString<SymbolPackPayload>(payloadResponse.bodyAsText())
            } catch (e: SerializationException) {
                return SymbolPackDownloadResult.Failure.Parse(e)
            }

        // Defense-in-depth: the Edge Function envelope's `current_version`
        // and the payload's own `version` SHOULD agree, but a server-side
        // bug could mint a stale signed URL pointing at an older payload
        // file. Trust the payload body (it's what we'll persist and serve
        // to the catalog) and surface the discrepancy as Parse so it lands
        // in Sentry.
        if (payload.version != envelope.currentVersion) {
            return SymbolPackDownloadResult.Failure.Parse(
                IllegalStateException(
                    "version mismatch for $packId: envelope=${envelope.currentVersion} payload=${payload.version}",
                ),
            )
        }

        return SymbolPackDownloadResult.Success(payload = payload, version = payload.version)
    }

    private suspend fun handleRateLimited(response: HttpResponse): SymbolPackDownloadResult {
        val retry =
            try {
                json.decodeFromString<RateLimitedResponse>(response.bodyAsText()).retryAfterSeconds
            } catch (e: SerializationException) {
                // The Edge Function should always surface retry_after_seconds,
                // but a future shape change shouldn't crash sync — fall back
                // to a conservative default.
                DEFAULT_RATE_LIMIT_RETRY_SECONDS
            }
        return SymbolPackDownloadResult.Failure.RateLimited(retry)
    }

    companion object {
        /**
         * Lower-bound retry hint when the rate-limit body fails to decode.
         * Matches the Edge Function's [RATE_LIMIT_WINDOW_MS] / 1000.
         */
        internal const val DEFAULT_RATE_LIMIT_RETRY_SECONDS: Int = 60
    }
}

private suspend fun HttpResponse.tryReadText(): String? =
    try {
        bodyAsText()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

@Serializable
private data class RequestPackDownloadBody(
    @SerialName("pack_id") val packId: String,
)

@Serializable
private data class RequestPackDownloadResponse(
    @SerialName("payload_url") val payloadUrl: String,
    @SerialName("payload_url_ttl") val payloadUrlTtl: String,
    @SerialName("current_version") val currentVersion: Int,
    @SerialName("payload_size") val payloadSize: Int,
)

@Serializable
private data class RateLimitedResponse(
    @SerialName("error") val error: String? = null,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Int = 60,
)
