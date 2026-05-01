@file:Suppress("ktlint:standard:filename")

package io.github.b150005.skeinly.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

actual class BugSubmissionLauncher(
    private val context: Context,
) {
    actual fun launch(
        prefilledTitle: String,
        prefilledBody: String,
    ) {
        val encodedTitle = URLEncoder.encode(prefilledTitle, "UTF-8")
        val encodedBody = URLEncoder.encode(prefilledBody, "UTF-8")
        val url =
            "https://github.com/b150005/skeinly/issues/new" +
                "?template=beta-bug.yml" +
                "&title=$encodedTitle" +
                "&body=$encodedBody"
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                // FLAG_ACTIVITY_NEW_TASK is required when the launching
                // Context is the application Context (Koin-injected
                // singleton). Without it, ContextImpl rejects the
                // implicit-VIEW dispatch with "Calling startActivity()
                // from outside of an Activity context requires the
                // FLAG_ACTIVITY_NEW_TASK flag".
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }
}
