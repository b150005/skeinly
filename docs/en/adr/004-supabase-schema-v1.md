# ADR-004: Supabase Schema V1

## Status

Accepted

## Context

Phase 3b introduces Supabase as the backend (ADR-001). We need a PostgreSQL schema that:
- Mirrors the local SQLDelight schema (Project, Progress entities)
- Adds user profiles linked to Supabase Auth
- Enforces data isolation via Row Level Security (RLS)
- Supports the offline-first sync strategy (ADR-003)

## Decision

### Tables

1. **profiles**: Auto-created from `auth.users` via trigger. Stores display name, avatar, bio.
2. **projects**: Matches local ProjectEntity schema. `owner_id` references `profiles.id`.
3. **progress**: Matches local ProgressEntity schema. `project_id` references `projects.id` with CASCADE delete.

### Key Decisions

- **UUID primary keys**: All tables use UUID PKs with `gen_random_uuid()` default. The client generates UUIDs locally for offline-first compatibility.
- **TIMESTAMPTZ**: All timestamps use `TIMESTAMPTZ` (vs. local SQLDelight which stores ISO strings). The Kotlin `Instant` type maps cleanly to both.
- **RLS policies**: Every table has RLS enabled. Users can only access their own data. Progress access is derived from project ownership (subquery).
- **Auto-updated_at trigger**: The `updated_at` column on projects is automatically maintained by a trigger, supporting last-write-wins conflict resolution (ADR-003).
- **Profile auto-creation**: A trigger creates a profile row when a new user signs up, pre-populating `display_name` from auth metadata.

### Column Mapping (Local ↔ Remote)

| Local (SQLDelight) | Remote (Supabase) | Notes |
|---|---|---|
| `id TEXT` | `id UUID` | Client generates UUID string, Supabase stores as UUID |
| `owner_id TEXT` | `owner_id UUID` | "local-user" → actual auth.uid() |
| Timestamps as `TEXT` (ISO) | `TIMESTAMPTZ` | kotlinx.serialization handles conversion |

## Consequences

### Positive

- Schema is simple, mirrors the domain model 1:1
- RLS provides database-level security (no client-side enforcement needed)
- UUID PKs enable offline creation without server roundtrip
- Triggers reduce application code for profile creation and timestamp management

### Negative

- Progress RLS uses a subquery (checking project ownership), which may be slower for large datasets. Acceptable for MVP scale.
- Local `id` is `TEXT` while remote is `UUID` — need consistent UUID format generation on client

## Migration File

`supabase/migrations/001_initial_schema.sql`
