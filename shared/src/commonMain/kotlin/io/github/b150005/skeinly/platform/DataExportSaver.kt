package io.github.b150005.skeinly.platform

/**
 * Pre-Phase-40 A20 Option B (docs/en/ops/data-export-sop.md §Scope
 * deferrals) — writes the composed data-export JSON to an app-scoped
 * temporary file and hands it to the OS share sheet so the user can
 * save it to Files / Drive / email / etc.
 *
 * **Why a file (not intent/clipboard text):** a real account export
 * (patterns + chart documents whose cell grids are large JSON) can
 * exceed Android's ~1 MB binder transaction limit if passed as an
 * intent extra, and is unwieldy on the clipboard. A file-backed share
 * is the robust delivery for the "downloadable JSON bundle" the SOP
 * promises.
 *
 * Pattern mirrors [SupportContactLauncher] / [StoreUrlLauncher]:
 * constructor-injected Context on Android, parameterless on iOS;
 * fire-and-forget; failure is swallowed (best-effort — the user can
 * re-tap Export, and the success state already told them the bundle
 * was composed). The file is written to a cache / temp directory so
 * the OS reclaims it; no permission is required for either platform's
 * share path.
 */
expect class DataExportSaver {
    /**
     * Persist [jsonContent] as [fileName] in an app-scoped temp
     * location and present the OS share sheet for it. Fire-and-forget:
     * does not suspend, returns no result. A failure (no disk, no
     * presentable VC) is swallowed.
     */
    fun save(
        jsonContent: String,
        fileName: String,
    )
}
