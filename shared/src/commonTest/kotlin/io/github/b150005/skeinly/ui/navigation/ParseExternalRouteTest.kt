package io.github.b150005.skeinly.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 39 (W3 / 2026-05-11) — unit tests for [parseExternalRoute]. The
 * helper is `internal` so this test sits in the same package on the
 * commonTest source set and accesses it directly.
 *
 * These tests lock the parser contract that both Android `MainActivity`
 * (Intent.data → SkeinlyNavHost.deepLinkUrl) and iOS `AppRootView.onOpenURL`
 * consume. Symmetric Swift parser at
 * `iosApp/iosApp/Navigation/AppRouter.swift` `parseExternalRoute(url:)` —
 * keep the two in sync.
 */
class ParseExternalRouteTest {
    private val validToken = "00000000-0000-0000-0000-000000000001"

    @Test
    fun `share-pattern URL resolves to SharedContent with token`() {
        val target = parseExternalRoute("https://b150005.github.io/skeinly/patterns/shared/$validToken")
        assertTrue(target is SharedContent)
        assertEquals(validToken, target.token)
        assertNull(target.shareId)
    }

    @Test
    fun `pull-request URL resolves to SuggestionDetail with prId`() {
        val target = parseExternalRoute("https://b150005.github.io/skeinly/pull-requests/abc-123")
        assertTrue(target is SuggestionDetail)
        assertEquals("abc-123", target.prId)
    }

    @Test
    fun `share-pattern URL with non-UUID token returns null`() {
        // Defense — only UUID v4 is valid share-token shape. A
        // hand-crafted URL with `?token=admin` or `<script>` style
        // garbage MUST be rejected at the parser boundary, not pushed
        // through to the SharedContent fetch where it would fail
        // server-side with a less-clear error.
        assertNull(parseExternalRoute("https://b150005.github.io/skeinly/patterns/shared/not-a-uuid"))
        assertNull(parseExternalRoute("https://b150005.github.io/skeinly/patterns/shared/admin"))
    }

    @Test
    fun `share-pattern URL with empty token returns null`() {
        assertNull(parseExternalRoute("https://b150005.github.io/skeinly/patterns/shared/"))
    }

    @Test
    fun `pull-request URL with empty prId returns null`() {
        assertNull(parseExternalRoute("https://b150005.github.io/skeinly/pull-requests/"))
    }

    @Test
    fun `URL with query string strips before route matching for share-pattern`() {
        // The share-token UUID precedes any `?utm_source=...` etc. The
        // parser must strip the query string before identifier
        // extraction so a URL like
        //   https://b150005.github.io/skeinly/patterns/shared/<UUID>?utm_source=sms
        // resolves to SharedContent(token=<UUID>) cleanly.
        val target =
            parseExternalRoute(
                "https://b150005.github.io/skeinly/patterns/shared/$validToken?utm_source=sms",
            )
        assertTrue(target is SharedContent)
        assertEquals(validToken, target.token)
    }

    @Test
    fun `URL with fragment strips before route matching for pull-request`() {
        val target =
            parseExternalRoute(
                "https://b150005.github.io/skeinly/pull-requests/abc-123#comment-1",
            )
        assertTrue(target is SuggestionDetail)
        assertEquals("abc-123", target.prId)
    }

    @Test
    fun `non-https scheme returns null`() {
        // Defense-in-depth — Manifest intent-filter and AASA already
        // restrict to https, but the parser must reject http and any
        // custom scheme so a misconfigured intent-filter cannot leak
        // unverified URLs through.
        assertNull(parseExternalRoute("http://b150005.github.io/skeinly/patterns/shared/$validToken"))
        assertNull(parseExternalRoute("skeinly://share/$validToken"))
    }

    @Test
    fun `wrong host returns null`() {
        // Defense — even a typo'd hostile mirror under a similar-looking
        // domain (e.g. `b150005.gihtub.io` typo) must not be accepted.
        assertNull(parseExternalRoute("https://b150005.gihtub.io/skeinly/patterns/shared/$validToken"))
        assertNull(parseExternalRoute("https://example.com/skeinly/patterns/shared/$validToken"))
    }

    @Test
    fun `path outside skeinly prefix returns null`() {
        // The host serves other content (privacy policy, terms,
        // marketing) under non-/skeinly/ paths. Those paths MUST NOT
        // claim app navigation.
        assertNull(parseExternalRoute("https://b150005.github.io/privacy-policy/"))
        assertNull(parseExternalRoute("https://b150005.github.io/skeinly"))
        assertNull(parseExternalRoute("https://b150005.github.io/"))
    }

    @Test
    fun `unknown skeinly subpath returns null`() {
        // Forward-compat — post-alpha additions like /skeinly/users/<id>
        // or /skeinly/discovery must silently no-op on older clients
        // that don't know the route shape, NOT crash or fall through
        // to a hostile SharedContent fetch.
        assertNull(parseExternalRoute("https://b150005.github.io/skeinly/users/123"))
        assertNull(parseExternalRoute("https://b150005.github.io/skeinly/discovery"))
        assertNull(parseExternalRoute("https://b150005.github.io/skeinly/unknown/foo"))
    }

    @Test
    fun `empty string returns null`() {
        assertNull(parseExternalRoute(""))
    }

    @Test
    fun `share-pattern URL with trailing slash returns null after token`() {
        // The substringBefore('/') stripping after the prefix means a
        // trailing slash like `.../shared/<token>/` is treated as token
        // = <token> + extra path. Validate the token survives intact.
        val target =
            parseExternalRoute(
                "https://b150005.github.io/skeinly/patterns/shared/$validToken/",
            )
        assertTrue(target is SharedContent)
        assertEquals(validToken, target.token)
    }

    // Phase 25.4 (ADR-024 §Phase 25.4) — friend-invite token path. The
    // token is a 32-byte URL-safe random string, NOT a UUID, so the
    // friend arm intentionally does no shape validation beyond non-empty
    // + length cap (≤512). Existence / expiry / consumed / self-redeem
    // are server-side RPC concerns.
    @Test
    fun `friend invite URL resolves to FriendInviteConfirm with token`() {
        val token = "abc_DEF-123xyz456"
        val target =
            parseExternalRoute("https://b150005.github.io/skeinly/friend/$token")
        assertTrue(target is FriendInviteConfirm)
        assertEquals(token, target.token)
    }

    @Test
    fun `friend invite URL with empty token returns null`() {
        assertNull(parseExternalRoute("https://b150005.github.io/skeinly/friend/"))
    }

    @Test
    fun `friend invite URL with oversized token returns null`() {
        // 513 chars — one over the MAX_FRIEND_TOKEN_LENGTH (512) cap.
        val oversized = "a".repeat(513)
        assertNull(
            parseExternalRoute("https://b150005.github.io/skeinly/friend/$oversized"),
        )
    }

    @Test
    fun `friend invite URL at exactly the length cap is accepted`() {
        // 512 chars — exactly at the cap, must still parse.
        val maxLen = "a".repeat(512)
        val target =
            parseExternalRoute("https://b150005.github.io/skeinly/friend/$maxLen")
        assertTrue(target is FriendInviteConfirm)
        assertEquals(maxLen, target.token)
    }

    @Test
    fun `friend invite URL strips query string before token extraction`() {
        val token = "tok_QY9z"
        val target =
            parseExternalRoute(
                "https://b150005.github.io/skeinly/friend/$token?utm_source=imessage",
            )
        assertTrue(target is FriendInviteConfirm)
        assertEquals(token, target.token)
    }

    @Test
    fun `friend invite URL strips fragment before token extraction`() {
        val token = "tok_QY9z"
        val target =
            parseExternalRoute(
                "https://b150005.github.io/skeinly/friend/$token#section",
            )
        assertTrue(target is FriendInviteConfirm)
        assertEquals(token, target.token)
    }

    @Test
    fun `friend invite URL with trailing slash keeps token intact`() {
        val token = "tok_QY9z"
        val target =
            parseExternalRoute(
                "https://b150005.github.io/skeinly/friend/$token/",
            )
        assertTrue(target is FriendInviteConfirm)
        assertEquals(token, target.token)
    }
}
