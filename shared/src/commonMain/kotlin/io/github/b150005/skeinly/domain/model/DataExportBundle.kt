package io.github.b150005.skeinly.domain.model

/**
 * Pre-Phase-40 A20 Option B (docs/en/ops/data-export-sop.md §Scope
 * deferrals) — the in-app GDPR Art. 20 / CCPA data export result.
 *
 * The `export-my-data` Edge Function composes the bundle server-side
 * (it is the only place that can reach `auth.users` + `storage.objects`
 * scoped to the caller); the client never re-derives the shape. This
 * model carries:
 *
 * - [bundleJson]: the pretty-printed JSON the user actually downloads /
 *   shares. Re-serialized client-side from the envelope's `bundle`
 *   element so the file is human-readable; the wire transfer itself is
 *   compact.
 * - [summary]: per-table row counts (`_avatars` included) so the
 *   success UI can render "N records across M categories" without
 *   parsing [bundleJson].
 * - [totalRows]: sum of every [summary] value — the headline number.
 *
 * Immutable value object (matches the codebase data-class + `copy()`
 * convention). No behavior; the [io.github.b150005.skeinly.platform.DataExportSaver]
 * is what turns [bundleJson] into a file + OS share sheet.
 */
data class DataExportBundle(
    val bundleJson: String,
    val summary: Map<String, Int>,
    val totalRows: Int,
)
