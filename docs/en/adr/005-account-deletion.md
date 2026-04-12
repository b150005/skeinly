# ADR-005: Account Deletion via Database RPC

## Status
Accepted

## Context
Apple App Store Review Guideline 5.1.1(v) requires apps that support account creation to also offer account deletion. Google Play has a similar requirement. Knit Note needs a mechanism for authenticated users to permanently delete their accounts and all associated data.

## Decision
Implement account deletion using a **PostgreSQL RPC function** (`SECURITY DEFINER`) rather than a Supabase Edge Function.

### Approach
1. A SQL function `delete_own_account()` runs with elevated privileges (`SECURITY DEFINER`) and deletes the authenticated user from `auth.users` using `auth.uid()`.
2. The existing `ON DELETE CASCADE` chain handles all dependent data:
   - `auth.users` -> `profiles` -> `projects` -> `progress`
   - `profiles` -> `shares`, `comments`, `activities`
3. The client calls `postgrest.rpc("delete_own_account")` followed by a local sign-out.

### Security
- The function uses `auth.uid()` to identify the caller — no user-controllable parameters.
- `SECURITY DEFINER` is restricted to the `authenticated` role via `GRANT`.
- `search_path` is explicitly set to `auth, public` to prevent schema injection.
- This pattern is consistent with existing `SECURITY DEFINER` functions in the schema (`handle_new_user`, `set_progress_owner_id`).

## Alternatives Considered

### Supabase Edge Function
- Would call `auth.admin.deleteUser()` server-side with the service role key.
- Rejected because: the project has zero Edge Functions and zero TypeScript. Introducing a new language, runtime (Deno), and deployment pipeline for a single SQL `DELETE` is unjustified by the current requirements.

## Consequences
- **Storage objects** (chart images) in Supabase Storage buckets are not CASCADE-deleted. Orphaned files in private buckets are inaccessible without auth. A scheduled cleanup job can address this later if needed.
- **Local data** in SQLDelight is not automatically cleared. The client should wipe local state after account deletion.
- If pre-deletion hooks (farewell email, data export, webhook) are needed in the future, this approach should be reconsidered in favor of an Edge Function.
