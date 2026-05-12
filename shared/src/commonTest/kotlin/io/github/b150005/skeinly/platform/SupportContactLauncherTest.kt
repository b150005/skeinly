package io.github.b150005.skeinly.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pre-alpha A34 — locks the mailto: URL composition and percent-encoding
 * contract for [composeSupportMailtoUrl] + [percentEncode]. The launcher
 * itself is platform-specific (Android Intent / iOS UIApplication) and
 * doesn't sit in commonTest, but the URL build path is pure and entirely
 * testable here.
 */
class SupportContactLauncherTest {
    // ---------- percentEncode ----------

    @Test
    fun `percentEncode passes unreserved characters unchanged`() {
        // RFC 3986 unreserved set: A-Z a-z 0-9 - . _ ~
        assertEquals("abcXYZ123-._~", percentEncode("abcXYZ123-._~"))
    }

    @Test
    fun `percentEncode space becomes percent-20 not plus`() {
        // mailto: per RFC 6068 uses RFC 3986 percent-encoding (NOT the
        // application/x-www-form-urlencoded plus-substitution). Spaces
        // become %20.
        assertEquals("hello%20world", percentEncode("hello world"))
        assertFalse(percentEncode("hello world").contains('+'))
    }

    @Test
    fun `percentEncode newline becomes percent-0A`() {
        assertEquals("line1%0Aline2", percentEncode("line1\nline2"))
    }

    @Test
    fun `percentEncode mailto-reserved characters encoded`() {
        // `?`, `&`, `=`, `#` would otherwise collide with mailto URL
        // delimiters. All must encode inside parameter values.
        assertEquals("a%3Fb", percentEncode("a?b"))
        assertEquals("a%26b", percentEncode("a&b"))
        assertEquals("a%3Db", percentEncode("a=b"))
        assertEquals("a%23b", percentEncode("a#b"))
    }

    @Test
    fun `percentEncode non-ASCII multibyte encoded as UTF-8 bytes`() {
        // 「お」 = U+304A = 3 UTF-8 bytes E3 81 8A
        assertEquals("%E3%81%8A", percentEncode("お"))
        // Mixed ASCII + Japanese
        assertEquals("hi%E3%81%8A", percentEncode("hiお"))
    }

    @Test
    fun `percentEncode uppercase hex digits`() {
        // RFC 3986 §2.1 — implementations SHOULD produce uppercase hex
        // for percent-encoded triplets. (Lowercase is tolerated on
        // parse but uppercase is the canonical emit form.)
        // ÿ is the byte 0xFF.
        assertEquals("%C3%BF", percentEncode("ÿ"))
    }

    // ---------- composeSupportMailtoUrlFromFields ----------

    // Property-surface-based ctor params; bypasses the `expect class`
    // DeviceContextProvider (Kotlin forbids test-side subclassing of
    // expect classes, so we test the field-based overload directly).

    private fun sampleUrl(
        appVersion: String = "0.1.0",
        osVersion: String = "Android 14 (API 34)",
        deviceModel: String = "Pixel 8",
        platformName: String = "Android",
        locale: String = "en-US",
        subject: String = SKEINLY_SUPPORT_SUBJECT_DEFAULT,
    ): String =
        composeSupportMailtoUrlFromFields(
            appVersion = appVersion,
            osVersion = osVersion,
            deviceModel = deviceModel,
            platformName = platformName,
            locale = locale,
            subject = subject,
        )

    @Test
    fun `composeSupportMailtoUrl starts with mailto and support email`() {
        assertTrue(sampleUrl().startsWith("mailto:skeinly.app@gmail.com?"))
    }

    @Test
    fun `composeSupportMailtoUrl contains URL-encoded subject parameter`() {
        // Subject default "Skeinly support" — spaces encoded as %20.
        assertTrue(sampleUrl().contains("subject=Skeinly%20support"))
    }

    @Test
    fun `composeSupportMailtoUrl body contains diagnostic context fields`() {
        val url = sampleUrl()
        // Body is URL-encoded; check that the encoded representations
        // of each diagnostic line appear. Each "App version: ", "OS: "
        // etc. carries a space (becomes %20) and a colon (encoded
        // %3A for defensive consistency).
        assertTrue(
            url.contains("App%20version%3A%200.1.0"),
            "URL did not contain the encoded app version line: $url",
        )
        assertTrue(url.contains("Locale%3A%20en-US"))
        assertTrue(url.contains("Platform%3A%20Android"))
        assertTrue(url.contains("Device%3A%20Pixel%208"))
    }

    @Test
    fun `composeSupportMailtoUrl body has leading whitespace for user input`() {
        val url = sampleUrl()
        // The body starts with %0A%0A%0A (three blank lines) so the
        // user can type their question above the diagnostic block
        // without scrolling past it.
        assertTrue(
            url.contains("body=%0A%0A%0A"),
            "Expected body= followed by three %0A newlines for user-input vertical room. URL: $url",
        )
    }

    @Test
    fun `composeSupportMailtoUrl uses URL-encoded newline separator between fields`() {
        // K/N test name forbids `%` characters in backticked function names;
        // describe the contract in prose instead. The encoded separator is
        // %0A — i.e. a percent-encoded line feed.
        val url = sampleUrl()
        assertTrue(url.contains("0.1.0%0A"))
    }

    @Test
    fun `composeSupportMailtoUrl with Japanese locale encodes correctly`() {
        assertTrue(sampleUrl(locale = "ja-JP").contains("Locale%3A%20ja-JP"))
    }

    @Test
    fun `composeSupportMailtoUrl supports overriding subject`() {
        val url = sampleUrl(subject = "Refund request")
        assertTrue(url.contains("subject=Refund%20request"))
        assertTrue(url.startsWith("mailto:skeinly.app@gmail.com"))
    }
}
