package io.github.b150005.skeinly.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 24.5 (ADR-017 §3.8) — unit tests for [parsePushRoute]. The
 * helper is `internal` so this test sits in the same package on the
 * commonTest source set and accesses it directly.
 *
 * These tests lock the parser contract that both Android `MainActivity`
 * (intent-extra path) and iOS `AppRootView` (NotificationCenter
 * publisher → onReceive) consume. Symmetric Swift parser at
 * `iosApp/iosApp/Navigation/AppRouter.swift` `parsePushRoute(_:)` —
 * keep the two in sync.
 */
class ParsePushRouteTest {
    @Test
    fun `pull-request route resolves to PullRequestDetail with prId`() {
        val target = parsePushRoute("pull-request/abc-123")
        assertTrue(target is PullRequestDetail)
        assertEquals("abc-123", target.prId)
    }

    @Test
    fun `pull-request route preserves uuid-shaped prId verbatim`() {
        val target = parsePushRoute("pull-request/00000000-0000-0000-0000-000000000001")
        assertTrue(target is PullRequestDetail)
        assertEquals("00000000-0000-0000-0000-000000000001", target.prId)
    }

    @Test
    fun `pull-request route with empty prId returns null`() {
        // Defense against the Edge Function ever emitting a malformed
        // route — a tap should silently no-op rather than navigate to
        // a PullRequestDetail with an empty id (which would crash
        // downstream queries).
        assertNull(parsePushRoute("pull-request/"))
    }

    @Test
    fun `unknown prefix returns null`() {
        // Phase 24+ may extend the prefix table to `pattern/<id>`,
        // `share/<token>`, etc. Older clients that don't know the
        // new prefix MUST silently drop rather than crash.
        assertNull(parsePushRoute("pattern/foo"))
        assertNull(parsePushRoute("share/abc"))
        assertNull(parsePushRoute("unknown"))
    }

    @Test
    fun `empty string returns null`() {
        assertNull(parsePushRoute(""))
    }

    @Test
    fun `prId containing slash is preserved as suffix`() {
        // Defense — current PR id format is UUID (no slashes), but a
        // future scheme MIGHT carry slashes. The parser MUST consume
        // the entire post-prefix tail rather than only the segment up
        // to the next slash.
        val target = parsePushRoute("pull-request/foo/bar")
        assertTrue(target is PullRequestDetail)
        assertEquals("foo/bar", target.prId)
    }
}
