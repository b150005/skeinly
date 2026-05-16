package io.github.b150005.skeinly.platform

import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

/**
 * Resolve the top-most presented `UIViewController`: the key window's
 * root controller walked down its `presentedViewController` chain so a
 * modal (share sheet, picker, alert) is presented by the deepest
 * visible VC rather than being swallowed by an already-presenting
 * ancestor.
 *
 * Extracted from `DataExportSaver.ios` so a single key-window-walk
 * exists for new iosMain presentation code. `ImagePicker.ios` carries
 * an older root-only variant (no presented-chain walk); consolidating
 * it is tracked as the broader `isKeyWindow()` dedup Tech Debt and is
 * intentionally NOT folded in here to avoid churning a working,
 * unrelated screen mid-feature.
 */
internal fun topMostViewController(): UIViewController? {
    val keyWindow =
        (UIApplication.sharedApplication.connectedScenes.firstOrNull() as? UIWindowScene)
            ?.windows
            ?.firstOrNull { (it as? UIWindow)?.isKeyWindow() == true } as? UIWindow
    var vc = keyWindow?.rootViewController
    while (vc?.presentedViewController != null) {
        vc = vc.presentedViewController
    }
    return vc
}
