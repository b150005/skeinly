import SwiftUI
import UIKit

// Phase 39.5 (ADR-015 §1) — iOS shake detection for the bug-report flow.
//
// The ADR's prescription was a `ShakeDetectingController : UIHostingController`
// subclass installed in `SceneDelegate`. Skeinly's iOS app uses pure SwiftUI
// `App` lifecycle (no SceneDelegate, no UIHostingController exposed at the
// surface), so the prescribed shape does not directly fit. The idiomatic
// SwiftUI App equivalent is a `UIWindow.motionEnded` extension that posts
// an `NSNotification` plus a `ViewModifier` that subscribes via
// `onReceive(NotificationCenter.default.publisher(...))`. This is the
// pattern Apple's SwiftUI WWDC samples use; UIWindow is the root responder
// for motion events in SwiftUI App lifecycle the same way SceneDelegate's
// hosting controller is when you opt into UIKit lifecycle. Documented as a
// deliberate ADR deviation in the iOS section of the Phase 39.5
// implementation note in `CLAUDE.md`.

extension UIDevice {
    static let deviceDidShakeNotification: Notification.Name =
        Notification.Name(rawValue: "io.github.b150005.skeinly.deviceDidShakeNotification")
}

/// Open subclass extension override on the framework `UIWindow` class.
///
/// Motion events propagate to the **root responder**; the SwiftUI App
/// runtime puts a `UIWindow` at the root, so this is where the `motionEnded`
/// override lands. A `UIViewControllerRepresentable` wrapped *inside*
/// SwiftUI never receives motion events (it's not the root responder), which
/// is the exact failure mode the ADR called out.
extension UIWindow {
    open override func motionEnded(_ motion: UIEvent.EventSubtype, with event: UIEvent?) {
        super.motionEnded(motion, with: event)
        if motion == .motionShake {
            NotificationCenter.default.post(
                name: UIDevice.deviceDidShakeNotification,
                object: nil
            )
        }
    }
}

/// SwiftUI ViewModifier that converts the shake `NSNotification` into a
/// closure callback. The closure fires on the main run loop because
/// `NotificationCenter.default.publisher` defaults to the posting thread
/// (`UIWindow.motionEnded` runs on main). Multiple shakes can fire in
/// rapid succession; the consumer (`AppRootView`) gates re-fires by
/// checking whether the bug-report preview is already on the path.
struct ShakeDetectorModifier: ViewModifier {
    let action: () -> Void

    func body(content: Content) -> some View {
        content.onReceive(
            NotificationCenter.default.publisher(for: UIDevice.deviceDidShakeNotification)
        ) { _ in
            action()
        }
    }
}

extension View {
    /// Phase 39.5 (ADR-015 §1) — installs a shake-gesture handler on the
    /// view. Combined with the `UIWindow.motionEnded` extension above,
    /// every shake gesture emitted while the view is on screen invokes
    /// the supplied closure.
    func onShake(perform action: @escaping () -> Void) -> some View {
        modifier(ShakeDetectorModifier(action: action))
    }
}
