# ADR-003: Offline-First Sync Strategy

## Status

Accepted

## Context

Knitters often work in environments with unreliable connectivity (living rooms, coffee shops, knitting groups). The app must remain functional offline for core activities (viewing patterns, tracking progress), while syncing data to Supabase when connectivity is available.

Key questions:
- What is the source of truth: local database or Supabase?
- How do we handle conflicts when offline changes sync?
- Which data needs to be available offline vs. fetched on demand?

## Decision

Adopt a **Supabase-first with local cache** strategy using SQLDelight for offline persistence.

### Architecture

```
┌─────────────────────────────────────────────┐
│                  UI Layer                    │
├─────────────────────────────────────────────┤
│              Repository Layer               │
│  ┌─────────────┐     ┌──────────────────┐   │
│  │  SQLDelight  │◄───►│  Supabase Client │   │
│  │ (local cache)│     │ (source of truth)│   │
│  └─────────────┘     └──────────────────┘   │
├─────────────────────────────────────────────┤
│         Sync Manager (Coroutines)           │
│  - Monitors connectivity                    │
│  - Queues offline mutations                 │
│  - Resolves conflicts (last-write-wins)     │
└─────────────────────────────────────────────┘
```

### Rules

1. **Source of truth**: Supabase is the source of truth for all shared/public data. SQLDelight is the source of truth for offline drafts until synced.
2. **Read path**: Repository reads from SQLDelight first (instant UI), then fetches from Supabase to refresh. Supabase Realtime subscriptions push updates to the local cache.
3. **Write path**: Mutations write to SQLDelight immediately (optimistic UI), then queue a sync to Supabase. If online, sync is immediate. If offline, mutations are queued and retried when connectivity returns.
4. **Conflict resolution**: Last-write-wins based on `updated_at` timestamp. For progress tracking (row counter), the higher row number wins (knitters don't un-knit rows in normal usage).
5. **Offline-available data**: User's own patterns, active projects, and recent progress. Shared patterns are cached on first view.
6. **Online-only data**: Activity feed, comments, search results, other users' profiles.

### SQLDelight Local Schema

A subset of the Supabase schema, stored locally:

- `Pattern` (owned and cached shared patterns)
- `Project` (user's active projects)
- `Progress` (recent progress entries)
- `PendingSync` (queue of offline mutations awaiting sync)

The `PendingSync` table tracks:
| Field | Type | Description |
|-------|------|-------------|
| id | INTEGER (PK) | Auto-increment |
| entity_type | TEXT | `pattern` / `project` / `progress` |
| entity_id | TEXT | UUID of the entity |
| operation | TEXT | `insert` / `update` / `delete` |
| payload | TEXT | JSON-serialized entity data |
| created_at | INTEGER | Epoch milliseconds |
| retry_count | INTEGER | Number of sync attempts |

## Consequences

### Positive

- **Responsive UI**: Reads from local cache ensure instant display regardless of network state.
- **Offline progress tracking**: Knitters can increment rows and add notes without connectivity.
- **Resilient sync**: Queued mutations survive app restarts and sync when connectivity returns.
- **Realtime updates**: Supabase Realtime subscriptions keep the local cache fresh for shared content.

### Negative

- **Dual storage complexity**: Maintaining consistency between SQLDelight and Supabase requires careful implementation of the sync manager.
- **Conflict edge cases**: Last-write-wins may lose data if two users edit the same shared pattern offline simultaneously. Acceptable for MVP given the usage patterns (patterns are typically edited by one person).
- **Storage overhead**: Local database duplicates some server data, increasing app storage usage.

### Neutral

- SQLDelight schemas are defined in `.sq` files under `shared/src/commonMain/sqldelight/`.
- The sync manager will be implemented as a Kotlin Coroutines-based service in the shared module.
- Initial MVP may simplify by not implementing the full sync queue (online-only mutations with local read cache), adding offline write support in a later iteration.

## Alternatives Considered

| Alternative | Pros | Cons | Why Not Chosen |
|------------|------|------|----------------|
| Supabase-only (no local cache) | Simpler architecture, single source of truth | App is unusable offline, slow UI on poor connections | Unacceptable UX for knitters who work in varied connectivity environments |
| Local-first with eventual sync (CRDTs) | Strong offline support, automatic conflict resolution | Significant implementation complexity, no established KMP CRDT library, overkill for this data model | Complexity not justified; last-write-wins is sufficient for knitting data |
| SQLDelight as sole source of truth + periodic backup | Simple, fully offline | No real-time sharing, no multi-device sync, defeats the social purpose | Contradicts the app's core value proposition of sharing and interaction |
