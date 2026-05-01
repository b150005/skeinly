package io.github.b150005.skeinly.data.analytics

/**
 * Phase F.5+ — typed analytics event hierarchy.
 *
 * Replaces the Phase F.4 stringly-typed `capture(eventName, properties)`
 * call shape that combined a string-const event name from `AnalyticsEvents`
 * with a hand-built `Map<String, Any>?`. The previous design relied on
 * code-review discipline to keep call sites in sync with the wire contract;
 * the sealed-interface variant enforces the per-event property shape at
 * compile time so:
 *
 * - Event-name typos become impossible (each variant is its own type).
 * - Property-key typos are unreachable (each variant builds its own
 *   `properties` map from a fixed set of constructor params).
 * - Property-value cardinality is gated by enum types ([ChartFormat],
 *   [SegmentVia]) so a string-typo in a value (e.g. `"polar"` vs
 *   `"Polar"`) cannot drift between call sites.
 *
 * Wire contract preserved: every variant exposes the same
 * `name: String` + `properties: Map<String, Any>?` interface that the
 * Application-layer collectors (Android `SkeinlyApplication` +
 * iOS `iOSApp.observeAnalyticsEvents`) read to forward into PostHog —
 * downstream consumers do not need to know about the sealed hierarchy.
 *
 * **Cardinality contract (devops invariant)**: property *values* must be
 * discrete enums, booleans, or small integers. Never include row ids,
 * pattern.id, project.id, user emails, or user-authored titles. The
 * sealed-type design enforces this structurally — no constructor accepts
 * a free-form `String` property at the wire boundary.
 */
sealed interface AnalyticsEvent {
    val name: String
    val properties: Map<String, Any>?

    // ----- Phase F.3: priority A (alpha1 learning loop) -----

    /** Onboarding carousel completed (Skip or Get Started). */
    data object OnboardingCompleted : AnalyticsEvent {
        override val name: String = "onboarding_completed"
        override val properties: Map<String, Any>? = null
    }

    /** A new project row was created. No properties — count alone is the signal. */
    data object ProjectCreated : AnalyticsEvent {
        override val name: String = "project_created"
        override val properties: Map<String, Any>? = null
    }

    /** Project's currentRow was incremented. Burst-friendly; hot collector buffers. */
    data object RowIncremented : AnalyticsEvent {
        override val name: String = "row_incremented"
        override val properties: Map<String, Any>? = null
    }

    /** A new pattern row was created. */
    data object PatternCreated : AnalyticsEvent {
        override val name: String = "pattern_created"
        override val properties: Map<String, Any>? = null
    }

    /**
     * Chart editor save (create-new vs update-existing differentiated via
     * [isNew]; coordinate system via [chartFormat]).
     */
    data class ChartEditorSave(
        val isNew: Boolean,
        val chartFormat: ChartFormat,
    ) : AnalyticsEvent {
        // `data class` variants declare `name` and `properties` in the
        // class body (not in the primary constructor), so they are
        // automatically excluded from the compiler-generated
        // `equals` / `hashCode` / `componentN` / `copy` methods —
        // structural equality is driven only by the user-facing
        // constructor params (`isNew`, `chartFormat`, etc.). The
        // additional `get()` form on `properties` ensures the wire map
        // is rebuilt fresh on each access rather than stored as a
        // potentially-stale snapshot — `name` is a constant string so
        // either `val =` or `val get() =` would behave identically
        // there, and we prefer the `get()` form for symmetry with
        // `properties` and consistency across every parametric variant
        // below.
        override val name: String get() = "chart_editor_save"
        override val properties: Map<String, Any>
            get() =
                mapOf(
                    PROP_IS_NEW to isNew,
                    PROP_CHART_FORMAT to chartFormat.wireValue,
                )
    }

    // ----- Phase F.4: priority B -----

    /**
     * A segment transitioned to DONE state. [via] discriminates the gesture
     * path (single-cell tap vs long-press vs row-batch sweep) so we can
     * tell whether the row-batch affordance is the dominant DONE path.
     */
    data class SegmentMarkedDone(
        val via: SegmentVia,
    ) : AnalyticsEvent {
        override val name: String get() = "segment_marked_done"
        override val properties: Map<String, Any>
            get() = mapOf(PROP_VIA to via.wireValue)
    }

    /**
     * A public pattern was forked. [hadChart] discriminates the metadata-only
     * fork branch from the chart-cloned branch — partial cloud failures land
     * in the metadata-only state per ADR-012 §7.
     */
    data class PatternForked(
        val hadChart: Boolean,
    ) : AnalyticsEvent {
        override val name: String get() = "pattern_forked"
        override val properties: Map<String, Any>
            get() = mapOf(PROP_HAD_CHART to hadChart)
    }

    /** A pull request was opened. [chartFormat] tracks adoption by chart type. */
    data class PullRequestOpened(
        val chartFormat: ChartFormat,
    ) : AnalyticsEvent {
        override val name: String get() = "pull_request_opened"
        override val properties: Map<String, Any>
            get() = mapOf(PROP_CHART_FORMAT to chartFormat.wireValue)
    }

    /** A pull request was closed (loop completion vs abandonment). No properties. */
    data object PullRequestClosed : AnalyticsEvent {
        override val name: String = "pull_request_closed"
        override val properties: Map<String, Any>? = null
    }

    /**
     * A pull request was merged. [hadConflicts] discriminates the
     * auto-clean fast-forward path (`false`) from the conflict-resolution
     * path (`true`) so we can measure conflict frequency in production.
     */
    data class PullRequestMerged(
        val hadConflicts: Boolean,
    ) : AnalyticsEvent {
        override val name: String get() = "pull_request_merged"
        override val properties: Map<String, Any>
            get() = mapOf(PROP_HAD_CONFLICTS to hadConflicts)
    }

    /** A pull request received a comment. */
    data object PullRequestCommented : AnalyticsEvent {
        override val name: String = "pull_request_commented"
        override val properties: Map<String, Any>? = null
    }

    // ----- Phase 39.3 (ADR-015 §6): generic screen + click instrumentation -----

    /**
     * A user-visible screen became active. Emitted by the Android
     * `MainActivity` `NavController.OnDestinationChangedListener` and the
     * iOS `.trackScreen(_:)` ViewModifier (see `iosApp/iosApp/Core/`).
     *
     * Sits **alongside** the typed-action variants above, not in place of
     * them — engagement-funnel signal is a different question from
     * outcome-event signal. PostHog dashboards group by `screen` to derive
     * per-screen DAU and tap-through rates without exposing pattern /
     * project ids at the wire boundary.
     */
    data class ScreenViewed(
        val screen: Screen,
    ) : AnalyticsEvent {
        override val name: String get() = "screen_viewed"
        override val properties: Map<String, Any>
            get() = mapOf(PROP_SCREEN to screen.wireValue)
    }

    /**
     * A user clicked a tracked action. Carries both the action [action] and
     * the originating [screen] so per-screen breakdowns and per-action
     * cross-screen comparisons (e.g. CreateProject from ProjectList vs
     * empty-state CTA — though Phase 39.0.2 Sprint B M4 collapsed the
     * empty-state CTA on Android) are both expressible in PostHog.
     *
     * Fires on **intent**, not outcome — see [ClickActionId] KDoc for the
     * intent-vs-outcome distinction with the existing typed variants.
     */
    data class ClickAction(
        val action: ClickActionId,
        val screen: Screen,
    ) : AnalyticsEvent {
        override val name: String get() = "click_action"
        override val properties: Map<String, Any>
            get() =
                mapOf(
                    PROP_ACTION to action.wireValue,
                    PROP_SCREEN to screen.wireValue,
                )
    }
}

/**
 * Coordinate system for chart-related events. Property value is the
 * `wireValue`, which matches the migration-013 / ADR-008 storage taxonomy.
 */
enum class ChartFormat(
    val wireValue: String,
) {
    Rect("rect"),
    Polar("polar"),
}

/**
 * Gesture path that drove a [AnalyticsEvent.SegmentMarkedDone] transition.
 * Phase F.4 vocabulary; values are wire-stable so PostHog reports stay
 * comparable across releases.
 */
enum class SegmentVia(
    val wireValue: String,
) {
    Tap("tap"),
    LongPress("long_press"),
    RowBatch("row_batch"),
}

// Wire-format property keys. file-private to keep the sealed-type
// surface minimal — call sites construct typed variants and never
// touch these keys directly.
private const val PROP_IS_NEW = "is_new"
private const val PROP_CHART_FORMAT = "chart_format"
private const val PROP_VIA = "via"
private const val PROP_HAD_CHART = "had_chart"
private const val PROP_HAD_CONFLICTS = "had_conflicts"
private const val PROP_SCREEN = "screen"
private const val PROP_ACTION = "action"
