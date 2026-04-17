# ADR-007: Pivot from Row Counter to Structured Chart Authoring

## Status

Accepted

## Context

Phases 1 through 27b delivered a feature-complete row counter application with
Supabase sync, sharing, patterns with photo-based chart images, progress
photos, discovery of public patterns, and store-submission scaffolding. A v1.0
store release was staged in Phase 27c.

Two observations forced a product re-evaluation before tagging v1.0:

1. **Critical iOS bug discovered during real-device verification** (fixed in
   commit `f65e308`): the Koin `factory`-resolved ViewModels were re-created
   on every SwiftUI View `init`, while `@StateObject` observers remained bound
   to the first instance's state flow. Button taps on the onboarding screen
   dispatched events to orphan ViewModels and the UI appeared frozen. The
   existing E2E suite bypassed the onboarding via a launch argument and
   therefore never exercised the `Next`/`Skip` tap path — a quality gate gap.

2. **The row-counter framing does not match the product vision.** The stated
   core value is *knitting chart (編み図) authoring and management*, including:
   - Programmatic chart creation at the symbol level (not just photo uploads)
   - Layer-based chart structure with per-segment progress tracking
   - Git-like collaboration (commits, branches, pull requests) on charts
   - Symbol reference dictionary for beginners
   - Japanese + English localization

   The current data model represents a chart as an optional image URL attached
   to a `Pattern` entity. None of the above capabilities are expressible over
   that schema; they require a structured representation of stitches, layers,
   and edit history.

Shipping v1.0 with the row-counter model would commit the app to a data
contract that cannot be extended without breaking migrations. Every production
install would need to migrate forward through several schema rewrites, and
public-facing names (Play Console package, App Store Connect bundle ID) would
be locked before the product identity is finalized.

## Decision

1. **Defer the v1.0 public release indefinitely.** Phase 27c (store submission)
   is removed from the active roadmap.
2. **Reframe the product around structured chart authoring.** The row counter
   becomes a secondary feature — a progress pointer *within* a chart — rather
   than the application's primary surface.
3. **Add a new phased roadmap (Phase 28–40)** covering bundle ID rename, a
   structured chart data model, a symbol library, chart viewer and editor,
   per-segment progress, discovery and fork, Git-like collaboration (commit,
   branch, pull request), closed beta, and finally public v1.0 release.
4. **Use native dual Canvas rendering** for chart visuals — Compose Canvas on
   Android, SwiftUI Canvas on iOS — rather than a shared SVG generator or a
   WebView host.
5. **Pre-release phase treats the local database as throwaway.** Schema
   migrations between pre-release phases may be destructive; no production
   users exist yet, so backward compatibility is not required until the Phase
   39 closed beta.

## Consequences

### Positive

- Product identity (authoring, collaboration, open-source charts) is codified
  before an App Store/Play Console listing locks naming and bundle IDs.
- No production users means no migration debt during the largest schema and
  rendering rework of the project's life.
- The structured chart data model enables the Git-like collaboration vision
  (ADR-TBD) without a second rewrite.
- Native rendering gives best-in-class performance on each platform for what
  will be the most visually complex screen in the app.

### Negative

- Calendar delay of 4–6 part-time months before a public release.
- Phase 27 prep work (signing CI, privacy policy, screenshot scaffolding,
  feature graphic) sits idle. It is retained — none of it is wasted — but
  does not deliver user value during the pivot.
- Two-platform native Canvas implementation doubles UI work for the chart
  viewer and editor compared to a shared renderer.

### Neutral

- The existing row counter, project CRUD, progress notes, photo-based chart
  images, sharing, and Supabase sync stay in the codebase. They will be
  re-framed around the structured chart model rather than removed.
- Bundle ID change (`com.knitnote.*` → `io.github.b150005.knitnote`) is
  scoped as Phase 28 to decouple naming from the rename-heavy chart work.

## Alternatives Considered

| Alternative | Pros | Cons | Why Not Chosen |
|------------|------|------|----------------|
| Ship v1.0 with the row-counter model, then add chart authoring in v2.0 | Gets the app in stores sooner, starts accumulating users and feedback | Locks public naming and bundle IDs before product identity settles; forces breaking schema migrations on real user data; v2.0 feels like a different app | Migration burden and identity lock-in outweigh early-user value for a pre-product-market-fit project |
| Shared SVG renderer (Compose Multiplatform Canvas) | Single implementation of chart drawing | CMP's graphics layer is still maturing (CMP 1.10.x); SwiftUI users get a non-native feel; future symbol interactions (tap-to-edit) harder to wire on non-native canvas | Chart is the most visible surface; native fidelity matters more than implementation economy |
| WebView + HTML Canvas (ammies-style) | Maximum code reuse; existing web examples to port | Non-native scroll/zoom gestures on mobile; higher memory footprint; hostile to future offline-first requirement | Defeats the "native KMP" strategy committed to in ADR-001 |
| Drop Git-like collaboration from v1.0 and target a simpler authoring-only release | Smaller scope, earlier launch | The Git model is the differentiator that justifies the app's existence relative to Ammies + KnitCompanion; releasing without it is a weaker product story | Collaboration can be phased (Phase 37–38) but must be in v1.0 scope |
