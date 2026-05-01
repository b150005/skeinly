package io.github.b150005.skeinly.data.analytics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Phase 39.3 (ADR-015 §6) — wire-shape tests for the new
 * [AnalyticsEvent.ScreenViewed] and [AnalyticsEvent.ClickAction] variants
 * + the supporting [Screen] / [ClickActionId] enums.
 *
 * Pure tests — no [AnalyticsTracker] involvement, since opt-in gating is
 * already covered in [AnalyticsTrackerTest]. These assert what PostHog
 * sees on the wire, which is the load-bearing contract: analytics
 * dashboards built against `event.name` + `event.properties` keys
 * must remain stable across releases.
 */
class ScreenAndClickActionEventTest {
    @Test
    fun `ScreenViewed wire name is screen_viewed`() {
        assertEquals(
            "screen_viewed",
            AnalyticsEvent.ScreenViewed(Screen.ProjectList).name,
        )
    }

    @Test
    fun `ScreenViewed properties carry the screen wireValue`() {
        assertEquals(
            mapOf("screen" to "projectListScreen"),
            AnalyticsEvent.ScreenViewed(Screen.ProjectList).properties,
        )
    }

    @Test
    fun `ScreenViewed wireValue matches across all enum entries`() {
        // Every Screen entry's wireValue must appear when wrapped in ScreenViewed.
        // Catches accidental property-key drift on a single variant.
        Screen.entries.forEach { screen ->
            assertEquals(
                mapOf("screen" to screen.wireValue),
                AnalyticsEvent.ScreenViewed(screen).properties,
            )
        }
    }

    @Test
    fun `ClickAction wire name is click_action`() {
        assertEquals(
            "click_action",
            AnalyticsEvent
                .ClickAction(
                    action = ClickActionId.CreateProject,
                    screen = Screen.ProjectList,
                ).name,
        )
    }

    @Test
    fun `ClickAction properties carry both action and screen wireValues`() {
        assertEquals(
            mapOf(
                "action" to "create_project",
                "screen" to "projectListScreen",
            ),
            AnalyticsEvent
                .ClickAction(
                    action = ClickActionId.CreateProject,
                    screen = Screen.ProjectList,
                ).properties,
        )
    }

    @Test
    fun `Screen wireValues are unique`() {
        // Cardinality guard — a duplicate wireValue would silently merge
        // two screens in PostHog dashboards. The test fails fast on
        // accidental copy-paste.
        val wireValues = Screen.entries.map { it.wireValue }
        assertEquals(wireValues.size, wireValues.toSet().size, "duplicate wireValue in Screen enum")
    }

    @Test
    fun `ClickActionId wireValues are unique`() {
        val wireValues = ClickActionId.entries.map { it.wireValue }
        assertEquals(
            wireValues.size,
            wireValues.toSet().size,
            "duplicate wireValue in ClickActionId enum",
        )
    }

    @Test
    fun `Screen wireValues use the testTag landmark naming convention`() {
        // The Phase 33.x i18n sweep made every screen's root testTag end
        // with the suffix `Screen`. Aligning Screen.wireValue with that
        // taxonomy keeps cross-tool grep one-step (see Screen KDoc).
        Screen.entries.forEach { screen ->
            assertTrue(
                screen.wireValue.endsWith("Screen"),
                "${screen.name} wireValue '${screen.wireValue}' does not end with 'Screen'",
            )
        }
    }

    @Test
    fun `ClickAction equality is structural over action plus screen`() {
        val a = AnalyticsEvent.ClickAction(ClickActionId.IncrementRow, Screen.ProjectDetail)
        val b = AnalyticsEvent.ClickAction(ClickActionId.IncrementRow, Screen.ProjectDetail)
        assertEquals(a, b, "data class equality must compare action + screen")

        val differentScreen =
            AnalyticsEvent.ClickAction(ClickActionId.IncrementRow, Screen.ChartViewer)
        assertNotEquals(a, differentScreen, "different screen must NOT compare equal")

        val differentAction =
            AnalyticsEvent.ClickAction(ClickActionId.DecrementRow, Screen.ProjectDetail)
        assertNotEquals(a, differentAction, "different action must NOT compare equal")
    }
}
