# ADR-012: Phase 36 Chart Discovery + Fork (Structured Chart Extension)

## Status

Proposed

## Context

Phase 26 shipped Discovery as a list of public Patterns with metadata-only
fork (`ForkPublicPatternUseCase` deep-copies the `Pattern` row + creates a
linked `Project`, no `chart_document` involved). Phases 29–35 added the
structured chart on top of the same Pattern row but Discovery never
learned about it: a public Pattern with a hand-authored 30-row chart
forks today as a Pattern + empty Project, and the forker has to "Create
structured chart" from scratch.

This makes the entire 35.x editor investment invisible to non-author
users. With Phase 35 closed (35.2/35.2f/35.3 shipped, ADR-011), 36 is
the next strategic unlock: extend Discovery to surface charts and
upgrade the fork to a commit-rooted chart copy with author attribution.

Phase 36 also seeds the data shape for Phase 37 collaboration. The
`chart_documents` table already carries `revision_id` /
`parent_revision_id` / `content_hash` columns (migration 012, ADR-008
§7). Fork is the first writer of a non-null `parent_revision_id` — every
fork records the source's revision as its parent, so commit lineage is
queryable from day one. Phase 37's PR/branch/merge work attaches to the
same lineage; if 36 lands fork without populating `parent_revision_id`,
37 has to retroactively wire it through every existing fork.

Constraints carried forward:

- **ADR-008 §7:** `content_hash` is drawing-identity only. A byte-for-byte
  document copy + new envelope ids has the same `content_hash` as the
  source. This is correct: forking does not alter drawing identity, only
  ownership.
- **ADR-010 §4:** `ProjectSegment` rows are project-scoped. The forker's
  fresh `Project` starts with zero segments — fork does NOT copy progress.
  The source author's progress is theirs alone.
- **ADR-011 §1–§6:** Polar charts and edited charts both fork through the
  same path — fork is unaware of coordinate system or layer count.
- **Phase 26 invariants:** `ForkPublicPatternUseCase` is the single fork
  entry point. Discovery's existing fork affordance must keep working
  unchanged for Patterns without structured charts (the chart-clone step
  is conditional on source having one).
- **Phase 32 invariants:** `EditHistory` is per-editor-session and is not
  serialized. A forked chart starts with empty history — the forker
  cannot "undo" the source's edits.

## Agent team deliberation

Convened once for the full ADR. Four interacting topics: chart-clone
semantics, attribution data shape, Discovery surface, and fork failure
handling.

### Voices

- **architect:** Chart fork lives at the repository layer
  (`StructuredChartRepository.forkFor(sourceChart, newPatternId,
  newOwnerId)`), not in the use case. The use case orchestrates Pattern
  + Project + Chart writes; the repository owns the SQL/JSON
  envelope-id-rewrite. This keeps `ForkPublicPatternUseCase` testable
  with a fake repo and isolates the migration 014 risk to one file.
  `parent_revision_id` MUST be populated at fork time — retrofitting
  lineage in Phase 37 means walking every chart_document row to guess
  ancestry from `created_at` ordering, which is unsound.

- **product-manager:** Discovery card must show "has chart" affordance
  visibly. Without it the user can't tell metadata-only forks from
  full-chart forks until after they tap. A small chart-preview glyph
  on the card beats a text badge — knitters scan visually. Attribution
  shows on Project detail, not on the card; cards are for discovery,
  detail is for context. "Forked from" text deep-links back to source
  if still public — broken link if source went private or was deleted,
  fall back to the author's display name without a link.

- **knitter:** Crucial that fork preserves author intent. If the source
  author marked specific cells with parametric values, those parameters
  must round-trip into the fork untouched. The forker can edit afterwards
  but the initial state must be byte-identical to the source's last
  saved state. (This falls out naturally from byte-copying the document
  blob; called out so future readers don't try to "normalize" or
  "validate" during clone.)

- **implementer:** Failure handling matters. The fork sequence is
  `patternRepository.create(forkedPattern)` →
  `chartRepository.forkFor(...)` (NEW) → `projectRepository.create(...)`.
  If the chart clone fails after the pattern is created, we have a
  partial state. Two options: (a) wrap all three in a transaction (no
  KMP transactional API exists today; would need a new Supabase RPC),
  or (b) treat chart clone as best-effort with structured error
  reporting. (b) is consistent with how PendingSync coalescing handles
  transient failures elsewhere; (a) is a Phase 37+ concern when
  collaboration writes get more complex. Pick (b) for MVP.

- **ui-ux-designer:** Filter chip "Charts only" sits next to the existing
  Difficulty filter chip row. No new screen, no tab. Empty-state copy
  for "no patterns match" reuses the existing key when filter narrows
  to zero. Chart-preview thumbnail on PatternCard renders at fixed
  ~64dp/64pt size — small enough to not crowd the metadata, large
  enough to read structure. Long-press on the thumbnail opens
  ChartViewer in read-only mode (deep link to existing
  `ChartViewerScreen` with `projectId = null`). Tap on card body
  follows existing fork-CTA flow.

### Decision points resolved by the team

1. **Fork chains commit lineage** → cloned `chart_document.parent_revision_id` =
   source's `revision_id`. Always populated, never null on a fork
   (architect, strong).
2. **Pattern attribution shape** → new column `patterns.parent_pattern_id UUID NULL
   REFERENCES patterns(id) ON DELETE SET NULL` (knitter + PM agree;
   ON DELETE SET NULL preserves the fork after source deletion at the
   cost of losing the link).
3. **Chart clone is best-effort** → fork sequence on chart-clone failure
   completes Pattern + Project, surfaces error, leaves fork in
   no-chart state (implementer; PM yields). User can re-fork later or
   manually create chart.
4. **Discovery filter** → "Charts only" chip in existing filter row,
   no new screen (ui-ux-designer; PM agrees).
5. **Attribution UI** → Project detail row "Forked from: \<title\> by
   \<author\>", deep-link if source still public, fallback text-only
   otherwise (ui-ux-designer; knitter notes author display name only,
   no email or any other identifier).
6. **Chart-preview thumbnail** → on Discovery PatternCard, ~64dp/64pt,
   reuses `ChartCanvas` painting at fixed scale. Long-press opens
   read-only ChartViewer (ui-ux-designer).

## Decision

### 1. Pattern attribution column

Migration 014 adds `parent_pattern_id`:

```sql
ALTER TABLE public.patterns
    ADD COLUMN IF NOT EXISTS parent_pattern_id UUID REFERENCES public.patterns(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_patterns_parent_pattern_id ON public.patterns(parent_pattern_id)
    WHERE parent_pattern_id IS NOT NULL;
```

`Pattern` data class gains `parentPatternId: String?`. Existing rows
backfill as NULL (no historical attribution). Forward-only; no rollback
needed — column drop on rollback is clean.

### 2. Chart clone

New method on `StructuredChartRepository`:

```kotlin
interface StructuredChartRepository {
    // ... existing methods ...

    /**
     * Clone the chart document attached to [sourcePatternId] under [newPatternId]
     * with [newOwnerId] as the new owner. The cloned document has:
     *   - new envelope id, pattern_id, owner_id
     *   - new revision_id; parent_revision_id = source's revision_id (commit-rooted lineage)
     *   - byte-for-byte copy of `document` jsonb (drawing identity preserved)
     *   - content_hash unchanged (drawing identity per ADR-008 §7)
     *   - fresh created_at / updated_at
     *
     * Returns null if the source pattern has no structured chart attached.
     * Throws on storage error.
     */
    suspend fun forkFor(
        sourcePatternId: String,
        newPatternId: String,
        newOwnerId: String,
    ): StructuredChart?
}
```

Local implementation reuses the existing SQLDelight upsert path. Remote
implementation issues one INSERT against `chart_documents` with the
copied document blob and rewritten envelope ids. RLS path: the read
side hits the existing 012 policy ("Public chart documents readable")
since the source pattern is `visibility = 'public'`; the write side
hits the existing owner-CRUD policy since `owner_id = auth.uid()` for
the cloned row.

### 3. Use case orchestration

`ForkPublicPatternUseCase` is extended:

```kotlin
class ForkPublicPatternUseCase(
    private val patternRepository: PatternRepository,
    private val projectRepository: ProjectRepository,
    private val structuredChartRepository: StructuredChartRepository, // NEW
    private val authRepository: AuthRepository,
    private val createActivity: CreateActivityUseCase? = null,
) {
    suspend operator fun invoke(patternId: String): UseCaseResult<ForkedProject> {
        // ... existing validation + sourcePattern fetch ...

        val forkedPattern = sourcePattern.copy(
            id = Uuid.random().toString(),
            ownerId = userId,
            visibility = Visibility.PRIVATE,
            parentPatternId = sourcePattern.id, // NEW
            createdAt = now,
            updatedAt = now,
        )

        return try {
            patternRepository.create(forkedPattern)

            // NEW: best-effort chart clone. Failure does not roll back pattern/project.
            val chartCloneResult = runCatching {
                structuredChartRepository.forkFor(
                    sourcePatternId = sourcePattern.id,
                    newPatternId = forkedPattern.id,
                    newOwnerId = userId,
                )
            }
            // chartCloneResult.exceptionOrNull() surfaced as soft warning, not failure

            val forkedProject = Project(/* ... */)
            projectRepository.create(forkedProject)

            // ... activity ...

            UseCaseResult.Success(
                ForkedProject(
                    pattern = forkedPattern,
                    project = forkedProject,
                    chartCloned = chartCloneResult.getOrNull() != null,
                    chartCloneError = chartCloneResult.exceptionOrNull()?.toUseCaseError(),
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }
}

data class ForkedProject(
    val pattern: Pattern,
    val project: Project,
    val chartCloned: Boolean = false,    // NEW
    val chartCloneError: UseCaseError? = null,  // NEW
)
```

`DiscoveryViewModel` consumes the new fields: success Snackbar reads
"Forked successfully" if `chartCloned == true`, "Forked (chart copy
failed — try re-forking from project detail)" otherwise. The latter
surfaces the issue without blocking navigation.

### 4. Discovery extension

`PublicPatternDataSource` grows a `hasStructuredChart` filter param:

```kotlin
interface PublicPatternDataSource {
    suspend fun getPublic(
        searchQuery: String = "",
        limit: Int = 100,
        chartsOnly: Boolean = false, // NEW
    ): List<Pattern>
}
```

Remote implementation joins `chart_documents` to filter:

```sql
SELECT p.*
FROM patterns p
INNER JOIN chart_documents cd ON cd.pattern_id = p.id  -- only when chartsOnly=true
WHERE p.visibility = 'public'
  AND p.title ILIKE '%' || $1 || '%'
ORDER BY p.created_at DESC
LIMIT $2;
```

`GetPublicPatternsUseCase.invoke` adds `chartsOnly: Boolean = false`.
`DiscoveryState.chartsOnlyFilter: Boolean`. New `DiscoveryEvent.ToggleChartsOnly`.
Filter chip "Charts only" sits between the search field and the
Difficulty chip row.

### 5. PatternCard chart-preview thumbnail

`Pattern` data class does NOT learn about chart presence — fetching
chart data inline would make the Discovery list query expensive. Instead
the data source exposes a `hasStructuredChart: Set<String>` companion
result (pattern ids that have charts), populated by a single secondary
query on the same call:

```sql
SELECT pattern_id FROM chart_documents
WHERE pattern_id = ANY($1::uuid[]);
```

`DiscoveryState.patternsWithCharts: Set<String>` carries the result.
PatternCard checks membership and renders a preview thumbnail (~64dp/64pt)
when present. The thumbnail itself is rendered live via a new
`ChartThumbnail` composable that calls `ChartCanvas` at fixed scale with
no gestures and no overlays — this avoids fetching the full chart JSON
upfront. Tap on thumbnail opens read-only ChartViewer (deep link to
existing `ChartViewer(patternId, projectId = null)` route).

**Tradeoff: live-render vs. cached preview image.** Live-rendering is
correct (always matches current chart state) but means each Discovery
list emit triggers N chart fetches (one per visible pattern card). For
N=20 visible cards on a typical Discovery scroll, that's 20 doc fetches.
A cached PNG preview column on `chart_documents` would be O(1) but
introduces cache-invalidation work (regenerate on every chart save).
MVP picks live-render with viewport-based lazy loading (Compose
`LazyVerticalGrid` already lazy-instantiates off-screen items;
SwiftUI `LazyVGrid` parallel). If perf shows up, Phase 36.x adds a
generated thumbnail column.

### 6. Attribution UI

`ProjectDetailScreen` adds a row in the pattern-info section when
`pattern.parentPatternId != null`:

```
Forked from: <source pattern title> by <author displayName>
              ^ tappable if source still public, plain text otherwise
```

Resolution: `GetPatternByIdUseCase(parentPatternId)` + author lookup via
existing `UserRepository`. If the source is now private or deleted, the
fetch returns null — render plain text "Forked from a deleted pattern"
fallback. Author name lookup follows the existing "Someone" fallback
convention from Phase 33.1.7.

### 7. Fork failure semantics

Best-effort chart clone (decision §3 of the agent team). On failure:

- Pattern + Project still land (already-created rows are not rolled back).
- `ForkedProject.chartCloned = false`, `chartCloneError = <error>`.
- Discovery shows fallback Snackbar copy.
- Project detail shows "Create structured chart" CTA as if no chart
  existed (existing Phase 32 flow).
- A retry path is **explicitly not added** in MVP — the user can delete
  the project and re-fork, or import manually. Phase 36.x can add a
  "retry chart copy" button on Project detail if telemetry shows
  non-trivial failure rate.

### 8. Explicitly NOT in Phase 36 MVP

- **Chart fork from a private/shared (non-public) source.** Discovery
  surfaces public patterns only. Direct sharing already exists (Phase 4b)
  and could grow a "fork" affordance, but that's separate scope.
- **Forking with progress.** Per ADR-010 §4, segments are project-scoped.
  Carrying source progress would imply a "team progress" semantic that
  the data model doesn't have.
- **Cached chart-preview thumbnails.** See §5 tradeoff. Live-render in
  MVP.
- **Multi-level fork chain UI.** Attribution surfaces one hop ("forked
  from X"). Walking the full chain ("X is a fork of Y is a fork of Z")
  is a Phase 37+ collaboration concern.
- **Retry chart-copy button.** See §7.
- **Attribution analytics.** Counting "how many forks does pattern X
  have" would require an aggregate query; useful for Phase 36.x
  popularity sort but not needed for fork mechanics.
- **Conflict resolution at fork time.** Source pattern updates after
  fork are NOT propagated to the fork. Once forked, the chart is
  independent. Phase 37 PR/merge addresses upstream sync.

## Consequences

### Positive

- The 35.x editor investment becomes observable to non-author users for
  the first time. Public patterns with structured charts are
  fork-with-chart, restoring the "Discover → fork → start knitting"
  loop that Phase 26 began.
- Phase 37 collaboration starts with a populated `parent_revision_id`
  graph from day one. No retroactive ancestry inference.
- Attribution preserves source-author credit, which is a baseline
  expectation for any pattern-sharing platform (Ravelry, Etsy, etc.)
  and meaningfully reduces the "chart theft" risk that public-fork
  features otherwise raise.
- `chartsOnly` filter and chart-preview thumbnail make the Discovery
  surface visibly upgraded — the "Discovery looks like a toy" gap that
  PM raised in §3 of the deliberation is closed.

### Negative

- Live-render chart thumbnails couple Discovery scroll perf to chart-doc
  fetch latency. Lazy grid mitigates but doesn't eliminate. Phase 36.x
  has a defined remediation (cached thumbnails) if telemetry shows it.
- Best-effort chart clone introduces a "fork looks complete but chart
  is missing" failure mode. Surfaced explicitly via Snackbar copy and
  Project detail "Create structured chart" CTA but is still a degraded
  user experience.
- `parent_pattern_id` is the first cross-row reference within `patterns`
  itself. The `ON DELETE SET NULL` choice means cascade deletes won't
  walk the fork chain — operationally simpler but means orphaned forks
  exist by design.
- Discovery list query gains a JOIN when `chartsOnly = true`. Index on
  `chart_documents.pattern_id` already exists (migration 012), so
  perf cost is bounded.

### Neutral

- `chart_documents` schema unchanged. `revision_id` /
  `parent_revision_id` / `content_hash` columns finally get used as
  designed (ADR-008 §7).
- `Project` schema unchanged. Forks are regular Projects whose Pattern
  carries attribution.
- Existing `ForkedProject` callers (Phase 26 Discovery only) get two
  new fields with safe defaults; no behavior change for chartless
  patterns.
- `ProjectSegment` schema unchanged. Fork starts with zero segments by
  ADR-010 §4 default.

## Considered alternatives

| Alternative | Pros | Cons | Why not chosen |
|---|---|---|---|
| Cached PNG thumbnail column on `chart_documents` | O(1) Discovery scroll cost | Cache invalidation on every chart save; storage cost; regen on schema migration | MVP lazy-render is good enough; revisit if perf surfaces |
| Transactional fork (RPC) | All-or-nothing semantics | Requires new Supabase RPC; couples KMP repos to Postgres-side semantics; fights existing PendingSync model | Best-effort clone matches existing failure-tolerance idioms |
| Copy progress at fork | Forker starts from same row count as source | Implies team-progress semantic that ADR-010 explicitly rejects | Fork is a fresh project; segments stay project-scoped |
| Skip `parent_pattern_id`, derive attribution from chart_documents `parent_revision_id` | One less column | Patterns without charts have no attribution path; chart-side ancestry is per-document not per-pattern | Pattern-level attribution is a separate concern from chart-level lineage |
| Auto-propagate source updates to forks | Forks always current | Defeats the purpose of fork (independent ownership); Phase 37 PR is the right shape for upstream sync | Fork = snapshot, PR = sync, distinct features |
| Discovery "Charts only" as a separate screen / tab | Cleaner navigation | Doubles the screen surface; charts and metadata-only patterns share metadata structure | Filter chip on existing screen matches mental model |
| Chart-preview thumbnail rendered as static SVG export | Self-contained, no live render | Requires SVG export pipeline that doesn't exist; doesn't match polar/segment overlay future work | Live ChartCanvas reuses existing render path |
| Attribution as a `forked_from_user_id` column | Skips the Pattern join for author lookup | Denormalizes; goes stale if user renames | Author display name resolves at render time via existing UserRepository |
| Walk full fork chain in attribution UI ("X ← Y ← Z") | Full provenance | Phase 37 collaboration concern; for Phase 36 MVP one hop is enough | Defer to collaboration era |

## References

- ADR-007: Pivot to chart authoring (Phase 36 framed as Discovery + fork extension)
- ADR-008: Structured chart data model (§7 `content_hash` invariant; `revision_id` lineage scaffold)
- ADR-010: Per-segment progress (segments are project-scoped; fork does not copy)
- ADR-011: Phase 35 advanced editor (closed; Phase 36 starts here)
- Phase 26 Completed notes: original `ForkPublicPatternUseCase` design
- Phase 32 Completed notes: editor MVP invariants (`EditHistory` not serialized)
- `supabase/migrations/012_structured_chart.sql`: existing RLS for chart documents
