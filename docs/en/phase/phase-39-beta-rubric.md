# Phase 39 — Closed Beta Tester Rubric

Related: [ADR-007 — Pivot to Chart Authoring](../adr/007-pivot-to-chart-authoring.md), [ADR-013 — Collaboration Core](../adr/013-phase-37-collaboration-core.md), [ADR-014 — Pull Request Workflow](../adr/014-phase-38-pull-request-workflow.md)

## Purpose

Closed beta validates the Phase 32→38 collaboration loop with knitters
outside the design conversation. Every design decision since Phase 32
has been informed by knitter advisory plus the agent team — beta is the
first non-author signal we get on whether the merge / open-PR / branch
flows read as "knitter editing" or as foreign Git ceremony.

Data-migration compatibility freezes at Phase 39. Schema changes
shipped during beta must be backward-compatible against the
1.0.0-beta1 client. Phase 35.2 polar editor and Phase 24 push
notifications stay deferred until beta closes.

## Tester profile

Target 5–10 invitees covering at least:

- 1 round-chart author (amigurumi / doily) — Phase 35.x signal source.
  Must use a forked public pattern at least once to exercise the
  Phase 36 + 37 + 38 chain end-to-end.
- 1 collaborative knitter (works on patterns shared by friends) —
  exercises the receive side of share + comment + activity feed.
- 2–3 solo knitters with diverse pattern complexity (lace, cable,
  colorwork) — exercises the per-segment progress loop on flat charts.
- At least 1 Android tester and at least 1 iOS tester.
- At least 1 Japanese-locale tester (`ja-JP`) — the per-app locale
  picker on Android 13+ shipped in Phase 33.3 and the dialog
  cross-window i18n closed in Phase 33.4 are unverified outside the
  agent team.

## Distribution

- **iOS**: TestFlight Internal Testing. Invite via Apple ID email.
  Build artifact: `iosApp.ipa` from the `release.yml` workflow run
  triggered by tag `v1.0.0-beta1`.
- **Android**: Google Play Internal Testing. Invite via Google
  account email. Build artifact: `androidApp-release.apk` from the
  same workflow run.

Per-tag CURRENT_PROJECT_VERSION (iOS) is `${{ github.run_number }}`,
auto-monotonic. Android VERSION_CODE is static in `version.properties`
and must bump on every fix-forward beta tag (current: `2`; next beta
build: `3`, etc.).

## Pre-launch verification (HARD GATES — invites do not go out until all pass)

Each item below is a blocking gate: a tester invite sent before the
gate is satisfied will produce false bug reports or non-functional
test paths. Verify in the same session that pushes
`v1.0.0-beta1` so the state is observably current.

1. **HARD GATE: Migration 016 applied to prod Supabase.** Run
   `supabase migration list --linked` and confirm `016_pull_requests`
   appears in the `Remote` column. Without this, `merge_pull_request`
   RPC and `pull_requests` / `pull_request_comments` tables do not
   exist server-side — every Phase 38 test path in this rubric
   becomes a systemic crash that is not a real regression. Do not
   send invites until this is verified. If the CLI times out (as
   happened during the version-bump session), retry from a stable
   network or run `gh run list` against the Supabase deploy workflow.
2. **HARD GATE: Tag CI green.** The workflow run triggered by
   `v1.0.0-beta1` must complete with `build-android`, `build-ios`,
   and `release` jobs all green. Verify via
   `gh run list --workflow release.yml --limit 1`.
3. **HARD GATE: Android VERSION_CODE strictly greater than prior
   Play upload.** The Android release path consumes
   `version.properties` directly (no tag-derived override). Before
   pushing the tag, open Play Console → Internal Testing → Releases
   and confirm the `VERSION_CODE` in `version.properties` is
   strictly greater than the highest previously-uploaded code. Play
   Console rejects duplicate `versionCode` values silently from the
   workflow's perspective, so this is the only way to catch the
   error before invite blast. Current value: `2`. Bump to `3` for
   the next fix-forward beta build, etc.
4. **HARD GATE: TestFlight + Play artifacts visible.** Confirm the
   IPA and APK appear in the workflow artifact list for tag
   `v1.0.0-beta1`. The release job auto-uploads to TestFlight when
   `APP_STORE_CONNECT_*` secrets are configured. If TestFlight
   rejects the `MARKETING_VERSION` pre-release string `1.0.0-beta1`
   at upload time (some `xcrun altool` / `notarytool` versions
   reject hyphen-suffixed semver in `CFBundleShortVersionString`),
   re-tag as `v1.0.0.1` (dot-separated only) and bump VERSION_CODE
   per gate 3.
5. **HARD GATE: Beta-bug reporting channel live.** The
   `.github/ISSUE_TEMPLATE/beta-bug.md` template must be live on
   the default branch so the URL `https://github.com/<owner>/<repo>/issues/new?template=beta-bug.md`
   resolves. The rubric's "Reporting issues" section links to it.
6. **Sign-up flow smoke-tested on a fresh device** — full
   email-confirmation + first-launch onboarding round-trip on at
   least one Android emulator and one iOS simulator. Not strictly a
   hard gate but standard practice before invite blast.

## Test rubric — what testers should exercise

Group flows by phase so feedback can be triaged by subsystem.

### Phase 32 — Chart editor (flat / rect)
- Open a public pattern from Discover, fork it, edit the structured
  chart by tapping cells.
- Use the parametric symbol input (e.g. `ch-space` chain count) and
  re-edit a parametric cell.
- Trigger the discard-guard dialog by editing then tapping
  Android system back / iOS toolbar back.
- Save a chart with no symbol changes — should not flag dirty.

### Phase 34 — Per-segment progress
- On a forked chart, tap cells to cycle todo → wip → done → todo.
- Long-press a cell to mark done directly. Haptic feedback should
  fire on both platforms.
- Tap "Reset progress" in the project detail; confirm the dialog
  surfaces and resets the overlay.
- **Polar charts**: tap cells to cycle todo → wip → done → todo
  (same as flat charts — polar tap-to-toggle shipped in Phase 35.1d).
  Confirm haptic feedback fires on both platforms. The "ships in
  Phase 35" deferred notice has been removed.

### Phase 36 — Discovery + fork
- Toggle the "Charts only" filter in Discover and verify thumbnails
  render on Android (iOS shows static placeholder by design).
- Fork a chartful public pattern; verify Snackbar reads
  "Forked successfully" (not the fallback "chart copy failed"
  message) when the source has a chart.
- On the forked project's detail view, verify the "Forked from
  [title] by [author]" row tappable when the source is still public.

### Phase 37 — Collaboration core
- View chart history (overflow → "View history") on a forked chart.
- Open the diff view by tapping a past revision; pan and zoom one
  pane and verify the other pane stays in sync.
- Long-press a past revision and "Restore as new commit" — the
  restored payload should append a new commit rather than overwrite
  the existing tip.
- Create a branch via the branch picker. Switch branches; the
  rendered chart should swap atomically.

### Phase 38 — Pull request
- From a forked chart's overflow → "Open pull request", fill in
  title and description, submit. Verify the success Snackbar.
- On the upstream pattern's owner device, open Pull Requests
  (toolbar icon), verify the incoming PR appears, post a comment.
- On the original PR detail, verify the comment appears in real
  time without manual refresh.
- **Merge happy path**: as the upstream owner, tap Merge on a
  conflict-free PR. The merged revision should appear in the
  upstream's history; the PR status should flip to MERGED.
- **Conflict resolution**: open a PR where both upstream and source
  edited the same cell (set up by editing on both sides between
  fork and PR-open). Tap Merge → conflict screen. Pick Take theirs
  / Keep mine / Skip per cell. Apply and merge.
- Close a PR (either party). Verify the closed state echoes through
  Realtime to the other party's device.

## What's known-broken — testers should NOT report these

These are documented Tech Debt or scope cuts; reporting them adds
noise without surfacing new information.

- **iOS Discovery thumbnail is a static placeholder**, not a live
  mini-render of the chart. Tracked as Tech Debt; deferred to
  Phase 36.4.1 polish.
- **Polar chart authoring (round charts)** does not exist in v1.
  Round-chart readers can view, mark progress, but not create or
  edit the structure. Phase 35.2 polar editor is sequenced after
  closed beta closes based on signal from this rubric.
- **Three-pane canvas preview on conflict resolution** is row-based
  picker only in v1. Side-by-side ancestor / theirs / mine canvas
  preview is post-beta polish.
- **iOS toast for chart-clone failure** during fork is silent
  (Compose surfaces a Snackbar). Tracked as Tech Debt.
- **ViewModel error messages** can render in English on a Japanese
  device when surfaced from a Kotlin exception. Tracked as Tech Debt.
- **Maestro `text:` selectors on dialog content** previously failed
  on `ja-JP` runs but were fixed in Phase 33.4. If a tester sees a
  Japanese label rendered as English inside a dialog, that is a
  regression worth reporting.
- **iOS only** — iOS NavigationStack edge-swipe on the chart editor
  does not surface the discard-guard dialog. Documented in CI Known
  Limitations. On iOS, use the toolbar back button for guarded
  discard. Android predictive-back is correctly guarded via
  `BackHandler` (Phase 32.4) — testers should exercise it.
- **CodeQL CI step** sometimes fails with `continue-on-error: true`
  due to upstream Kotlin 2.3.20 support gap. Not a beta blocker.

## Reporting issues

Use the **Phase 39 Beta Bug Report** GitHub issue template, which
prompts for everything below in a structured form:

- **Severity** — self-tag per the classes below.
- **Phase / subsystem** — which rubric section does this fall under?
- **Device + OS** (e.g. "Pixel 8 / Android 14", "iPhone 15 Pro / iOS 18.0").
- **App version + build number** (Settings → About, or the App Store
  Connect / Play Console build label).
- **Locale** (en-US or ja-JP).
- **Reproduction steps** as a numbered list.
- **Expected vs actual behavior**.
- **Screenshot or screen recording** if visual.

Issue template lives at `.github/ISSUE_TEMPLATE/beta-bug.yml`. Direct
filing URL once GitHub indexes the template:
`https://github.com/<owner>/<repo>/issues/new?template=beta-bug.yml`.

### Severity classes (testers self-tag)

- **Crash / data loss / incorrect merge result** → CRITICAL. Stop
  testing the affected flow until acknowledged.
- **UX confusion / unclear copy / missing affordance** → HIGH.
- **Visual polish / typography / spacing** → MEDIUM.
- **Translation refinement / minor i18n divergence** → LOW.

## Beta exit criteria

Phase 39 closes when:

1. ≥ 5 testers complete at least the Phase 32 + 34 + 38 happy paths.
2. Zero open CRITICAL issues.
3. ≤ 3 open HIGH issues, each with a remediation plan.
4. Round-chart author has submitted at least one structured Phase
   35.2 priority signal (e.g. "I would author X round-chart pattern
   if Y existed").
5. Migration 016 has not required any backward-incompatible change
   during beta.

Exit triggers Phase 40 (v1.0 public release) per the roadmap. If
exit criteria slip past two months, convene the agent team to
re-scope.

## Fix-forward policy during beta

- Polish slices that don't change schemas land mid-beta as
  `1.0.0-beta2`, `1.0.0-beta3`, etc. Bump VERSION_CODE in
  `version.properties` per beta.
- Schema changes (anything beyond migration 016) require a beta
  reset: invalidate prior testers' data, re-invite. Avoid unless
  CRITICAL.
- Release notes for each beta build go in
  `docs/en/phase/phase-39-beta-changelog.md` (create on first
  fix-forward) so testers see what changed.
