package io.github.b150005.skeinly.data.bug

import io.github.b150005.skeinly.data.analytics.AnalyticsEvent
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Phase 39.5 (ADR-015 §3) — pure formatter that assembles the GitHub Issue
 * Markdown body from a freeform user description, the recent-actions trail
 * pulled from [io.github.b150005.skeinly.data.analytics.EventRingBuffer],
 * and the device context.
 *
 * The shape matches the Markdown-body section of the ADR §3 specification:
 *
 * ```markdown
 * ## Description
 * {{user-supplied freeform description}}
 *
 * ## Reproduction context
 * - App version: {{appVersion}}
 * - Platform: {{platformName}} {{osVersion}}
 * - Device: {{deviceModel}}
 * - Locale: {{locale}}
 * - Timestamp: {{ISO8601 timestamp}}
 *
 * ## Recent actions (last 10)
 * | # | Type | Screen | Action |
 * |---|------|--------|--------|
 * | 1 | screen_viewed | ProjectList | — |
 * | 2 | click_action | ProjectList | create_project |
 * | ...
 *
 * ## Telemetry session ID
 * {{posthog distinct_id, for cross-referencing PostHog dashboard if needed}}
 * ```
 *
 * **Per-event timestamps deviation from ADR §3:** the ADR draft sketched a
 * "Time" column on the actions table, but
 * [io.github.b150005.skeinly.data.analytics.EventRingBuffer] stores bare
 * [AnalyticsEvent]s (no per-event capture instant) — the buffer was
 * deliberately simplified after the ADR cut to avoid per-emission
 * `Clock.System.now()` calls inside the hot tracker collector. The Time
 * column is therefore omitted; the surrounding `Timestamp:` field still
 * gives triagers an anchor for "when the report was sent".
 *
 * **Privacy invariant (load-bearing):** the function reads
 * [AnalyticsEvent.name] + [AnalyticsEvent.properties] only — never
 * `event.toString()`. The `toString()` of a `data class` event leaks the
 * Kotlin class FQN and any hidden constructor params (none today, but the
 * sealed surface is open to future additions); restricting to the wire
 * fields preserves the property-cardinality contract documented on
 * [AnalyticsEvent].
 *
 * **URL length budget (ADR §3):** GitHub's URL-length ceiling is ~8KB;
 * after subtracting URL chrome and the title we have ~6.5KB for the body.
 * Ring buffer at N=10 events × ~120 bytes per row = ~1.2KB; device-context
 * block ~300 bytes; freeform description capped at 4000 chars by the
 * preview UI. Comfortable headroom in the typical case.
 */
fun formatBugReportBody(
    description: String,
    events: List<AnalyticsEvent>,
    posthogDistinctId: String?,
    appVersion: String,
    osVersion: String,
    deviceModel: String,
    locale: String,
    platformName: String,
    timestamp: Instant = Clock.System.now(),
): String =
    buildString {
        appendLine("## Description")
        // Empty description is preserved as a blank line so the section
        // header is still semantically present in the body — triagers can
        // tell the user-authored portion was empty rather than missing.
        appendLine(description)
        appendLine()

        appendLine("## Reproduction context")
        appendLine("- App version: $appVersion")
        appendLine("- Platform: $platformName $osVersion")
        appendLine("- Device: $deviceModel")
        appendLine("- Locale: $locale")
        appendLine("- Timestamp: $timestamp")
        appendLine()

        appendLine("## Recent actions (last 10)")
        if (events.isEmpty()) {
            // Empty trail is normal — opt-in toggle off, fresh app
            // launch, or buffer cleared via EventRingBuffer.clear() per
            // Phase 39.4. Render an explicit "no actions captured" line
            // so triagers don't think the table renderer broke.
            appendLine("_No actions captured (diagnostic data sharing may be off)._")
        } else {
            appendLine("| # | Type | Screen | Action |")
            appendLine("|---|------|--------|--------|")
            events.forEachIndexed { index, event ->
                val type = escapeMarkdownPipe(event.name)
                val props = event.properties.orEmpty()
                // The Screen + Action columns are derived from the wire
                // properties — `screen` is present on every Phase 39.3
                // variant (ScreenViewed, ClickAction); `action` is on
                // ClickAction only. Outcome events (ProjectCreated,
                // ChartEditorSave, etc.) carry neither — em-dash for both
                // keeps column alignment clean.
                val screen = props["screen"]?.toString()?.let(::escapeMarkdownPipe) ?: "—"
                val action = props["action"]?.toString()?.let(::escapeMarkdownPipe) ?: "—"
                appendLine("| ${index + 1} | $type | $screen | $action |")
            }
        }
        appendLine()

        appendLine("## Telemetry session ID")
        if (posthogDistinctId != null) {
            appendLine(posthogDistinctId)
        } else {
            // The opt-in might be ON but PostHog SDK init not yet have
            // resolved a distinct_id (rare race window) — surface the
            // absence explicitly so triagers know to skip the
            // cross-reference attempt rather than search PostHog for
            // nothing.
            appendLine("_Not available (PostHog SDK not initialized)._")
        }
    }

/**
 * Markdown table cells delimit rows on `|`, so any user-controlled string
 * that ends up in a cell needs the literal pipe escaped to `\|`. Newlines
 * are also problematic — strip them rather than try to escape since
 * GitHub-flavored Markdown does not collapse them inside a table cell.
 *
 * `internal` so commonTest can reach it directly: every current
 * [AnalyticsEvent] variant's `name` and property values are sourced from
 * closed enum `wireValue`s which structurally cannot contain `|` or
 * newlines, but the helper exists as defense-in-depth against future
 * variants. Testing through synthetic [AnalyticsEvent] instances would
 * require subclassing the sealed interface from a different package,
 * which Kotlin/Native rejects per the sealed-hierarchy invariant.
 */
internal fun escapeMarkdownPipe(value: String): String = value.replace("|", "\\|").replace("\n", " ").replace("\r", "")
