# ADR-002: Data Model with Share as First-Class Entity

## Status

Accepted

## Context

Knit Note's differentiator is user-to-user sharing and interaction. The data model must treat sharing as a core concept, not a bolted-on feature. Key requirements:

- Users create patterns and track project progress
- Users share patterns with specific users or publicly
- Users can comment on patterns and projects
- An activity feed shows relevant events (shares, comments, completions)
- Patterns have visibility controls (private, shared, public)

The backend is Supabase/PostgreSQL (see ADR-001), so we use a relational model with foreign keys and row-level security.

## Decision

Adopt the following core data model with six entities. `Share` and `Activity` are first-class entities, not secondary features.

### Entity Definitions

```
┌──────────┐     ┌──────────┐     ┌──────────┐
│   User   │────<│ Pattern  │────<│ Project  │
└──────────┘     └──────────┘     └──────────┘
                      │                │
                      │                │
                 ┌────┴────┐     ┌────┴────┐
                 │  Share  │     │ Progress │
                 ├─────────┤     └──────────┘
                 │ Comment │
                 ├─────────┤
                 │Activity │
                 └─────────┘
```

#### User
| Field | Type | Description |
|-------|------|-------------|
| id | UUID (PK) | Supabase Auth user ID |
| display_name | TEXT | Display name |
| avatar_url | TEXT? | Profile image URL |
| bio | TEXT? | Short bio |
| created_at | TIMESTAMPTZ | Registration date |

#### Pattern
| Field | Type | Description |
|-------|------|-------------|
| id | UUID (PK) | Auto-generated |
| owner_id | UUID (FK → User) | Creator |
| title | TEXT | Pattern name |
| description | TEXT? | Notes, instructions |
| difficulty | TEXT? | beginner / intermediate / advanced |
| gauge | TEXT? | Gauge information |
| yarn_info | TEXT? | Yarn type and weight |
| needle_size | TEXT? | Needle size |
| chart_image_urls | TEXT[] | Chart photo URLs (Supabase Storage) |
| visibility | TEXT | `private` / `shared` / `public` |
| created_at | TIMESTAMPTZ | Creation date |
| updated_at | TIMESTAMPTZ | Last modified |

#### Project
| Field | Type | Description |
|-------|------|-------------|
| id | UUID (PK) | Auto-generated |
| owner_id | UUID (FK → User) | Who is knitting |
| pattern_id | UUID (FK → Pattern) | Which pattern |
| title | TEXT | Project name |
| status | TEXT | `not_started` / `in_progress` / `completed` |
| current_row | INT | Current row/round number |
| total_rows | INT? | Total rows (if known) |
| started_at | TIMESTAMPTZ? | When knitting began |
| completed_at | TIMESTAMPTZ? | When finished |
| created_at | TIMESTAMPTZ | Record creation |

#### Progress
| Field | Type | Description |
|-------|------|-------------|
| id | UUID (PK) | Auto-generated |
| project_id | UUID (FK → Project) | Which project |
| row_number | INT | Row completed |
| photo_url | TEXT? | Progress photo URL |
| note | TEXT? | Session notes |
| created_at | TIMESTAMPTZ | When this row was completed |

#### Share
| Field | Type | Description |
|-------|------|-------------|
| id | UUID (PK) | Auto-generated |
| pattern_id | UUID (FK → Pattern) | Shared pattern |
| from_user_id | UUID (FK → User) | Who shared |
| to_user_id | UUID (FK → User)? | Recipient (null for link shares) |
| permission | TEXT | `view` / `fork` |
| status | TEXT | `pending` / `accepted` / `declined` |
| share_token | TEXT? | Token for link-based sharing |
| shared_at | TIMESTAMPTZ | When shared |

#### Comment
| Field | Type | Description |
|-------|------|-------------|
| id | UUID (PK) | Auto-generated |
| author_id | UUID (FK → User) | Who commented |
| target_type | TEXT | `pattern` / `project` |
| target_id | UUID | Pattern or Project ID |
| body | TEXT | Comment text |
| created_at | TIMESTAMPTZ | When posted |

#### Activity
| Field | Type | Description |
|-------|------|-------------|
| id | UUID (PK) | Auto-generated |
| user_id | UUID (FK → User) | Who performed the action |
| type | TEXT | `shared` / `commented` / `forked` / `completed` / `started` |
| target_type | TEXT | `pattern` / `project` |
| target_id | UUID | Target entity ID |
| metadata | JSONB? | Extra context (e.g., comment preview) |
| created_at | TIMESTAMPTZ | When it happened |

### Row-Level Security Strategy

- **Patterns**: Owner has full access. Public patterns readable by all. Shared patterns readable by share recipients.
- **Projects**: Owner has full access. Visible to others only if linked pattern is public or shared.
- **Shares**: Both from_user and to_user can read. Only from_user can create. Only to_user can update status.
- **Comments**: Readable by anyone who can see the target. Creatable by authenticated users. Deletable by author only.
- **Activity**: Readable by the user and their followers (future feature). Insertable by system triggers only.

## Consequences

### Positive

- **Share is explicit**: Every share has provenance (who, to whom, when, with what permission), enabling permission management and analytics.
- **Activity feed ready**: The Activity table enables a social feed from day 1 without retrofitting.
- **Fork support**: The `fork` permission on Share allows users to copy and modify patterns, creating a pattern ecosystem.
- **Flexible visibility**: Three-tier visibility (private/shared/public) lets users control their content.
- **Link sharing**: share_token enables sharing outside the app (to social media, messaging apps).

### Negative

- **More tables to maintain**: Six core entities plus RLS policies is more complex than a local-only SQLite schema.
- **Activity table can grow large**: Will need indexing strategy and eventual archival/pagination.
- **RLS complexity**: Row-level security policies for shared content (especially transitive access through shares) require careful testing.

### Neutral

- The Kotlin domain models in the shared module will mirror these tables using kotlinx.serialization data classes.
- SQLDelight will be used for local caching (see ADR-003), with a subset of these tables replicated locally.

## Alternatives Considered

| Alternative | Pros | Cons | Why Not Chosen |
|------------|------|------|----------------|
| Share as a flag on Pattern (no Share table) | Simpler schema | No permission control, no provenance tracking, no link sharing | Insufficient for the social features that differentiate the app |
| Document-based model (Firestore) | Flexible schema, easy nesting | Denormalization for many-to-many relationships, complex queries for feeds and shared content | Relational model is a better fit (see ADR-001) |
| Separate social microservice | Decoupled, independently scalable | Over-engineering for MVP, deployment complexity | Not justified until scale demands it |
