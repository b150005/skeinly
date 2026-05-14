package io.github.b150005.skeinly.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

/**
 * Phase 27.1 (ADR-023 §3.1) — write-only port for the
 * `public.wipe_own_data()` SECURITY DEFINER RPC that
 * [io.github.b150005.skeinly.data.repository.WipeDataRepositoryImpl]
 * consumes.
 *
 * Exists as an interface so the repository test can inject an in-memory
 * fake without standing up Supabase. Same precedent as
 * [io.github.b150005.skeinly.data.remote.DeviceTokenRemoteOperations]
 * (ADR-017 §3.5) and
 * [io.github.b150005.skeinly.data.remote.SubscriptionRemoteOperations]
 * (ADR-016 §4.2).
 */
interface WipeDataRemoteOperations {
    /**
     * Calls `public.wipe_own_data()` under the caller's authenticated
     * session. Throws on transport / RPC failure; the repository wraps
     * the exception in
     * [io.github.b150005.skeinly.domain.usecase.UseCaseError].
     *
     * No parameters — the RPC reads `auth.uid()` from the JWT
     * exclusively. The session token attached by supabase-kt's Postgrest
     * plugin is the only credential.
     */
    suspend fun wipeOwnData()
}

/**
 * Phase 27.1 (ADR-023 §3.1) — Supabase implementation of
 * [WipeDataRemoteOperations].
 *
 * Calls the `wipe_own_data()` RPC via Postgrest. The function returns
 * `void`, so we discard the response body and treat absence of a thrown
 * exception as success. Postgrest surfaces non-2xx responses (RPC raised
 * `28000` when `auth.uid()` was NULL, RLS rejection, transport error)
 * as a thrown `RestException` / `HttpRequestException`, which the
 * repository maps via
 * [io.github.b150005.skeinly.domain.usecase.toUseCaseError].
 *
 * Mirrors the call pattern of
 * [io.github.b150005.skeinly.data.repository.AuthRepositoryImpl.deleteAccount]
 * (`client.postgrest.rpc("delete_own_account")`) verbatim, except the
 * RPC name + the SECURITY DEFINER body. Idempotency lives at the RPC
 * level (migration 033's `PERFORM ... FOR UPDATE` on auth.users); the
 * data source has no client-side retry loop.
 */
class RemoteWipeDataDataSource(
    private val supabaseClient: SupabaseClient,
) : WipeDataRemoteOperations {
    override suspend fun wipeOwnData() {
        supabaseClient.postgrest.rpc(RPC_NAME)
    }

    internal companion object {
        // Migration 033 line creating the function. Test anchor in
        // WipeDataRepositoryImplTest locks the expected string so a
        // renamed RPC must be intentionally co-edited at both call
        // sites + the migration.
        internal const val RPC_NAME = "wipe_own_data"
    }
}
