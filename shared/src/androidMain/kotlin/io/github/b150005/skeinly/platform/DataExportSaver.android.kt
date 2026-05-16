@file:Suppress("ktlint:standard:filename")

package io.github.b150005.skeinly.platform

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Pre-Phase-40 A20 Option B — Android impl. Writes the bundle to
 * `cacheDir/export/<fileName>`, wraps it in a `content://` URI via the
 * app's [FileProvider] (authority `${applicationId}.fileprovider`,
 * declared in androidApp's AndroidManifest), and fires an
 * `ACTION_SEND` chooser so the user picks Drive / Files / Gmail / etc.
 *
 * `FLAG_GRANT_READ_URI_PERMISSION` is required so the receiving app can
 * read the FileProvider URI. `FLAG_ACTIVITY_NEW_TASK` on the chooser is
 * required because the injected [context] is the application Context,
 * not an Activity (same pattern as the other platform launchers).
 *
 * `cacheDir/export/` is reused (not a fresh temp name per call) so the
 * directory does not accumulate — each export overwrites the
 * date-stamped file; the OS reclaims cacheDir under storage pressure
 * regardless.
 */
actual class DataExportSaver(
    private val context: Context,
) {
    actual fun save(
        jsonContent: String,
        fileName: String,
    ) {
        try {
            // Defense-in-depth: the caller generates a digits-only
            // date-stamped name, but strip any path separator so a
            // future name change can never resolve outside the
            // FileProvider-declared cacheDir/export/ scope (which would
            // otherwise throw inside getUriForFile + be swallowed).
            val safeName = fileName.replace(Regex("""[/\\]"""), "_")
            val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
            val file = File(dir, safeName)
            file.writeText(jsonContent)

            val uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )

            val sendIntent =
                Intent(Intent.ACTION_SEND).apply {
                    type = MIME_JSON
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            val chooser =
                Intent
                    .createChooser(sendIntent, null)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (_: Exception) {
            // Best-effort, same contract as SupportContactLauncher. A
            // failure here (no disk, no app to receive the share) is
            // non-fatal — the success state already confirmed the
            // bundle was composed; the user can re-tap Export.
            // `Exception` (not `Throwable`) so a `CancellationException`
            // is never swallowed (project Kotlin coding-style rule),
            // matching DataExportRepositoryImpl's rethrow discipline.
        }
    }

    private companion object {
        private const val EXPORT_DIR = "export"
        private const val MIME_JSON = "application/json"
    }
}
