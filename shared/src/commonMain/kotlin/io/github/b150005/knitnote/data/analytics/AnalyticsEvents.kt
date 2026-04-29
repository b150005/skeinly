package io.github.b150005.knitnote.data.analytics

/**
 * Phase F.4: centralized event-name and property-key constants for
 * [AnalyticsTracker]. Replaces the Phase F.3 string-literal call sites
 * scattered across 5 ViewModels. New events should land here first; new
 * property keys should land in the nested [Props] companion.
 *
 * Cardinality contract (devops invariant): property *values* must be
 * discrete enums, booleans, or small integers. Never include row ids,
 * pattern.id, project.id, user emails, or user-authored titles.
 */
object AnalyticsEvents {
    // Phase F.3 — priority A (alpha1 learning loop)
    const val ONBOARDING_COMPLETED = "onboarding_completed"
    const val PROJECT_CREATED = "project_created"
    const val ROW_INCREMENTED = "row_incremented"
    const val PATTERN_CREATED = "pattern_created"
    const val CHART_EDITOR_SAVE = "chart_editor_save"

    // Phase F.4 — priority B
    const val SEGMENT_MARKED_DONE = "segment_marked_done"
    const val PATTERN_FORKED = "pattern_forked"
    const val PULL_REQUEST_OPENED = "pull_request_opened"
    const val PULL_REQUEST_CLOSED = "pull_request_closed"
    const val PULL_REQUEST_MERGED = "pull_request_merged"
    const val PULL_REQUEST_COMMENTED = "pull_request_commented"

    /**
     * Property keys. Value-side enums for string properties live as
     * sibling consts under each `*_VIA_` / `*_FORMAT_` family.
     */
    object Props {
        // chart_editor_save: was this the create-new branch?
        const val IS_NEW = "is_new"

        // chart_editor_save / pull_request_opened: which coordinate
        // system did the chart use?
        const val CHART_FORMAT = "chart_format"
        const val CHART_FORMAT_RECT = "rect"
        const val CHART_FORMAT_POLAR = "polar"

        // segment_marked_done: which gesture path triggered the
        // segment to reach DONE?
        const val SEGMENT_VIA = "via"
        const val SEGMENT_VIA_TAP = "tap"
        const val SEGMENT_VIA_LONG_PRESS = "long_press"
        const val SEGMENT_VIA_ROW_BATCH = "row_batch"

        // pattern_forked: did the source pattern have a structured
        // chart that was successfully cloned?
        const val HAD_CHART = "had_chart"

        // pull_request_merged: did the merge require user-facing
        // conflict resolution? (false = auto-clean fast-forward path)
        const val HAD_CONFLICTS = "had_conflicts"
    }
}
