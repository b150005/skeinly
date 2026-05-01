package io.github.b150005.skeinly.platform

/**
 * Phase 39.5 (ADR-015 §3) — opens the system browser/intent on a prefilled
 * `https://github.com/.../issues/new?template=beta-bug.yml&title=...&body=...`
 * URL so the tester lands on the GitHub issue form with the diagnostic
 * payload (description + reproduction context + last 10 actions trail)
 * already populated.
 *
 * **Why URL prefill, not a server-side endpoint or a GitHub OAuth in-app flow:**
 * Beta is bounded to ≤10 testers; the abuse risk of an open issue creator
 * is bounded; baking a PAT into the binary is unsafe; OAuth round-trip would
 * triple the surface area for a one-off feature. URL prefill is the
 * established Bugsnag / Sentry / TestFlight idiom and the GitHub Issue
 * form auto-opens with the body already filled in.
 *
 * **Cancellation contract:** `launch` is fire-and-forget — the platform
 * intent / URL handler runs asynchronously and the caller does not
 * suspend or receive a result. Users can dismiss the system browser
 * sheet without affecting subsequent submissions; the local trail in
 * [io.github.b150005.skeinly.data.analytics.EventRingBuffer] is unchanged
 * either way.
 *
 * **iOS percent-encoding gotcha (documented at the iOS actual):** the
 * naive `String.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)`
 * does NOT encode `&` / `=` / `?` because that character set is meant for
 * pre-formed query strings — wrong tool for individual-component encoding.
 * A free-form description containing `&` written through that helper would
 * silently truncate the body parameter at the GitHub-side parser.
 * `URLComponents.queryItems` is the only correct shape on iOS — its
 * percent-encoding rules treat each item value as opaque, encoding every
 * URL-reserved character. Android uses `URLEncoder.encode(value, "UTF-8")`
 * which encodes the same way and composes safely.
 */
expect class BugSubmissionLauncher {
    /**
     * Opens the prefilled GitHub Issue URL.
     *
     * @param prefilledTitle title for the issue
     * @param prefilledBody Markdown body produced by [formatBugReportBody]
     */
    fun launch(
        prefilledTitle: String,
        prefilledBody: String,
    )
}
