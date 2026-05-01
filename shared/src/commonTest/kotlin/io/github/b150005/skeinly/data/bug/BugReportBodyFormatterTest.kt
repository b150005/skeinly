package io.github.b150005.skeinly.data.bug

import io.github.b150005.skeinly.data.analytics.AnalyticsEvent
import io.github.b150005.skeinly.data.analytics.ChartFormat
import io.github.b150005.skeinly.data.analytics.ClickActionId
import io.github.b150005.skeinly.data.analytics.Screen
import kotlin.test.Test
import kotlin.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 39.5 (ADR-015 §3) — coverage for the pure Markdown body
 * formatter. Tests assert on the literal string output so future
 * formatting changes (e.g. moving a section, adding a column) flag
 * here before they reach the GitHub Issue body.
 */
class BugReportBodyFormatterTest {
    private val fixedTimestamp = Instant.parse("2026-05-02T10:30:45Z")

    private fun formatWithDefaults(
        description: String = "Sample description",
        events: List<AnalyticsEvent> = emptyList(),
        posthogDistinctId: String? = null,
    ): String =
        formatBugReportBody(
            description = description,
            events = events,
            posthogDistinctId = posthogDistinctId,
            appVersion = "1.0.0-beta1 (3)",
            osVersion = "Android 14 (API 34)",
            deviceModel = "Google Pixel 8",
            locale = "en-US",
            platformName = "Android",
            timestamp = fixedTimestamp,
        )

    @Test
    fun renders_all_four_section_headers_in_order() {
        val body = formatWithDefaults()
        val descriptionIdx = body.indexOf("## Description")
        val contextIdx = body.indexOf("## Reproduction context")
        val actionsIdx = body.indexOf("## Recent actions")
        val telemetryIdx = body.indexOf("## Telemetry session ID")
        assertTrue(descriptionIdx in 0 until contextIdx)
        assertTrue(contextIdx in 0 until actionsIdx)
        assertTrue(actionsIdx in 0 until telemetryIdx)
    }

    @Test
    fun description_section_carries_user_supplied_text_verbatim() {
        val body = formatWithDefaults(description = "Pattern editor froze after undo & redo")
        assertTrue(body.contains("Pattern editor froze after undo & redo"))
    }

    @Test
    fun empty_description_renders_blank_line_under_header_so_section_remains_visible() {
        val body = formatWithDefaults(description = "")
        // The Description section's body is intentionally a blank line —
        // triagers can tell the user-authored portion was empty rather
        // than missing entirely.
        val descriptionThenContext =
            body.substring(
                body.indexOf("## Description"),
                body.indexOf("## Reproduction context"),
            )
        assertTrue(descriptionThenContext.contains("## Description\n\n\n"))
    }

    @Test
    fun reproduction_context_block_lists_every_field() {
        val body = formatWithDefaults()
        assertTrue(body.contains("- App version: 1.0.0-beta1 (3)"))
        assertTrue(body.contains("- Platform: Android Android 14 (API 34)"))
        assertTrue(body.contains("- Device: Google Pixel 8"))
        assertTrue(body.contains("- Locale: en-US"))
        assertTrue(body.contains("- Timestamp: 2026-05-02T10:30:45Z"))
    }

    @Test
    fun empty_events_renders_no_actions_captured_disclosure() {
        val body = formatWithDefaults(events = emptyList())
        assertTrue(body.contains("_No actions captured (diagnostic data sharing may be off)._"))
        // The table chrome must not render when the list is empty so
        // triagers don't see an empty | --- | --- | --- | grid.
        assertTrue(!body.contains("| # | Type | Screen | Action |"))
    }

    @Test
    fun populated_events_render_table_with_headers_and_rows() {
        val events =
            listOf(
                AnalyticsEvent.ScreenViewed(Screen.ProjectList),
                AnalyticsEvent.ClickAction(
                    action = ClickActionId.CreateProject,
                    screen = Screen.ProjectList,
                ),
            )
        val body = formatWithDefaults(events = events)
        assertTrue(body.contains("| # | Type | Screen | Action |"))
        assertTrue(body.contains("|---|------|--------|--------|"))
        assertTrue(body.contains("| 1 | screen_viewed | projectListScreen | — |"))
        assertTrue(body.contains("| 2 | click_action | projectListScreen | create_project |"))
    }

    @Test
    fun outcome_event_with_no_screen_property_renders_em_dash_columns() {
        // ProjectCreated is a `data object` outcome event — `properties`
        // is null so neither `screen` nor `action` is in the wire map.
        val body = formatWithDefaults(events = listOf(AnalyticsEvent.ProjectCreated))
        assertTrue(body.contains("| 1 | project_created | — | — |"))
    }

    @Test
    fun parametric_outcome_event_with_chart_format_does_not_leak_class_name() {
        // ChartEditorSave carries `is_new` + `chart_format` in its
        // properties, but neither is mapped to the table's Screen /
        // Action columns. The row should render with em-dashes there
        // (no event.toString() leak — the property keys are not surfaced
        // in the Recent Actions table per the privacy contract).
        val body =
            formatWithDefaults(
                events = listOf(AnalyticsEvent.ChartEditorSave(isNew = true, chartFormat = ChartFormat.Polar)),
            )
        assertTrue(body.contains("| 1 | chart_editor_save | — | — |"))
        // The `data class` `toString()` would leak `ChartEditorSave(isNew=true, ...)` —
        // the formatter must NEVER include that.
        assertTrue(!body.contains("ChartEditorSave("))
    }

    @Test
    fun posthog_distinct_id_when_present_appears_under_telemetry_header() {
        val body = formatWithDefaults(posthogDistinctId = "ph_abcdef0123456789")
        val telemetryBlock = body.substring(body.indexOf("## Telemetry session ID"))
        assertTrue(telemetryBlock.contains("ph_abcdef0123456789"))
    }

    @Test
    fun posthog_distinct_id_when_null_renders_explicit_unavailable_disclosure() {
        val body = formatWithDefaults(posthogDistinctId = null)
        assertTrue(body.contains("_Not available (PostHog SDK not initialized)._"))
    }

    @Test
    fun escape_markdown_pipe_replaces_pipe_with_backslash_pipe() {
        // The `|` escape is defense-in-depth — every current AnalyticsEvent
        // variant's wireValue is a closed-enum identifier that structurally
        // cannot contain `|`. Testing the helper directly avoids the
        // sealed-hierarchy "extending sealed classes from a different
        // package is prohibited" Kotlin/Native rejection that synthetic
        // event subclasses would hit.
        assertEquals("foo\\|bar", escapeMarkdownPipe("foo|bar"))
        assertEquals("foo\\|bar\\|baz", escapeMarkdownPipe("foo|bar|baz"))
    }

    @Test
    fun escape_markdown_pipe_collapses_newlines_to_spaces() {
        assertEquals("line one line two", escapeMarkdownPipe("line one\nline two"))
        assertEquals("line one line two", escapeMarkdownPipe("line one\r\nline two"))
    }

    @Test
    fun escape_markdown_pipe_is_identity_on_safe_strings() {
        assertEquals("create_project", escapeMarkdownPipe("create_project"))
        assertEquals("プロジェクト", escapeMarkdownPipe("プロジェクト"))
    }

    @Test
    fun formatter_is_pure_and_deterministic_across_repeated_calls() {
        val body1 = formatWithDefaults()
        val body2 = formatWithDefaults()
        assertEquals(body1, body2)
    }
}
