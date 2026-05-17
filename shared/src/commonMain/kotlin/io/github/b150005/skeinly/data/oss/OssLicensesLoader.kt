package io.github.b150005.skeinly.data.oss

import io.github.b150005.skeinly.domain.model.OssLibrary
import io.github.b150005.skeinly.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Pre-Phase-40 A33 — production loader: reads the build-time-generated
 * `aboutlibraries.json` bundled under `commonMain/composeResources/files/`
 * and runs it through [parseOssLibraries].
 *
 * Kept OUT of [io.github.b150005.skeinly.ui.settings.OssLicensesViewModel]
 * behind that VM's lambda seam (same precedent as the `DataExportSaver`
 * seam): `Res.readBytes` requires the Compose-resources runtime, which is
 * available on real Android/iOS targets but not in the plain-JVM
 * commonTest classpath. `ViewModelModule` binds the VM's `loadLibraries`
 * to this function; commonTest injects a fake list instead. This file is
 * therefore thin resource plumbing (kover-excluded, like the
 * `Remote*DataSource` wrappers) — the parsing logic it delegates to is
 * exhaustively unit-tested in `OssLibraryParserTest`.
 */
@OptIn(ExperimentalResourceApi::class)
suspend fun loadBundledOssLibraries(): List<OssLibrary> =
    parseOssLibraries(
        Res.readBytes("files/aboutlibraries.json").decodeToString(),
    )
