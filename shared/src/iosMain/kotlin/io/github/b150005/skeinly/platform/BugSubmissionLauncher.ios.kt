@file:Suppress("ktlint:standard:filename")

package io.github.b150005.skeinly.platform

import platform.Foundation.NSURL
import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLQueryItem
import platform.UIKit.UIApplication

actual class BugSubmissionLauncher {
    actual fun launch(
        prefilledTitle: String,
        prefilledBody: String,
    ) {
        // NSURLComponents + queryItems correctly percent-encodes `&`, `=`,
        // `?`, and other URL-reserved characters that the more familiar
        // `String.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)`
        // does NOT encode (the `.urlQueryAllowed` character set permits
        // `&` and `=` so it can be applied to pre-formed query strings —
        // wrong tool for individual-component encoding). A free-form
        // description containing `&` written via manual percent-encoding
        // would silently truncate the body parameter at the GitHub-side
        // parser. NSURLComponents is the only correct shape here.
        // NSURLComponents(string:) is non-nullable in the current Kotlin/Native
        // platform bindings — the constructor never returns nil for a hardcoded
        // valid URL like ours. Direct assignment is safe; an `?: return` would
        // be dead code and ktlint flags the unreachable elvis branch.
        val components =
            NSURLComponents(string = "https://github.com/b150005/skeinly/issues/new")
        components.queryItems =
            listOf(
                NSURLQueryItem.queryItemWithName(name = "template", value = "beta-bug.yml"),
                NSURLQueryItem.queryItemWithName(name = "title", value = prefilledTitle),
                NSURLQueryItem.queryItemWithName(name = "body", value = prefilledBody),
            )
        val url: NSURL = components.URL ?: return
        // openURL:options:completionHandler: dispatches to Safari (or the
        // default browser); a null completion handler accepts the default
        // success/failure semantics (we surface nothing back to the user
        // either way per the fire-and-forget contract documented on the
        // expect declaration).
        UIApplication.sharedApplication.openURL(
            url = url,
            options = emptyMap<Any?, Any>(),
            completionHandler = null,
        )
    }
}
