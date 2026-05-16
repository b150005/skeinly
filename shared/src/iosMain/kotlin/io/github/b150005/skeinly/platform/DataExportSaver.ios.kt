@file:Suppress("ktlint:standard:filename")

package io.github.b150005.skeinly.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.popoverPresentationController

/**
 * Pre-Phase-40 A20 Option B — iOS impl. Writes the bundle to
 * `NSTemporaryDirectory()/<fileName>`, builds a file `NSURL`, and
 * presents a `UIActivityViewController` (the system share sheet) from
 * the top-most presented view controller so it appears above the
 * pushed Data-Export screen rather than being swallowed.
 *
 * The Kotlin String is written via an `NSData` round-trip
 * (`usePinned` → `NSData.create(bytes,length)` → `NSData.writeToFile`,
 * the same pinning idiom as `KoinHelper.nsDataToByteArray`) instead of
 * an `NSString` cast — `String as NSString` is flagged
 * "cast can never succeed" by the Kotlin/Native compiler.
 *
 * iPad guard: `UIActivityViewController` MUST have a
 * `popoverPresentationController` anchor on iPad or UIKit raises
 * `NSGenericException`. We anchor it to the presenting VC's whole view
 * bounds (no visible source chip, crash-safe, acceptable for a
 * Settings-initiated action).
 *
 * Fire-and-forget contract mirrors [SupportContactLauncher.ios] /
 * [StoreUrlLauncher.ios]: no suspension, no result, failure swallowed
 * (the success state already confirmed the bundle was composed).
 *
 * The top-most-VC resolution is shared via [topMostViewController]
 * (UiKitPresentation.kt) so future iosMain presentation code reuses
 * one key-window walk.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class DataExportSaver {
    actual fun save(
        jsonContent: String,
        fileName: String,
    ) {
        // NSTemporaryDirectory() ends with "/"; fileName is a bare
        // date-stamped name. Defense-in-depth: strip any path separator
        // so a future name change can never escape the temp dir (which
        // would silently no-op via the !wrote guard below).
        val safeName = fileName.replace(Regex("""[/\\]"""), "_")
        val path = NSTemporaryDirectory() + safeName

        val data = jsonContent.encodeToByteArray()
        val nsData =
            if (data.isEmpty()) {
                NSData()
            } else {
                data.usePinned { pinned ->
                    NSData.create(
                        bytes = pinned.addressOf(0),
                        length = data.size.toULong(),
                    )
                }
            }
        val wrote = nsData.writeToFile(path, atomically = true)
        if (!wrote) return

        val url = NSURL.fileURLWithPath(path)
        val activityVc =
            UIActivityViewController(
                activityItems = listOf(url),
                applicationActivities = null,
            )

        val presenter = topMostViewController() ?: return

        // iPad: anchor the popover or UIKit throws. `presenter.view` is
        // non-null in the K/N UIKit binding so no elvis is needed.
        activityVc.popoverPresentationController?.let { popover ->
            val view = presenter.view
            popover.sourceView = view
            popover.sourceRect = view.bounds
        }

        presenter.presentViewController(
            activityVc,
            animated = true,
            completion = null,
        )
    }
}
