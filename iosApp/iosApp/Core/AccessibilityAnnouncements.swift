import UIKit

/// Sprint A PR6 — VoiceOver announcement helper for transient toasts.
///
/// SwiftUI does not fire VoiceOver announcements for `.overlay`-based
/// toasts the way it does for `.alert` content. Without an explicit
/// `UIAccessibility.post`, blind and low-vision users receive zero
/// auditory feedback when an action succeeds or fails — closes the
/// audit's HIGH finding A8 (WCAG 4.1.3 Status Messages).
///
/// Call this from the same code path that flips the toast-visible flag
/// (typically inside the navEvents observer or the action callback)
/// so the announcement timing mirrors the visual toast lifecycle.
///
/// Usage:
/// ```swift
/// case is SomeNavEventSuccess:
///     withAnimation { showToast = true }
///     announceToVoiceOver(messageKey: "message_success")
///     try? await Task.sleep(nanoseconds: 2_000_000_000)
///     withAnimation { showToast = false }
/// ```
@MainActor
func announceToVoiceOver(messageKey: String) {
    let message = NSLocalizedString(messageKey, comment: "")
    UIAccessibility.post(notification: .announcement, argument: message)
}

/// Variant for already-resolved literal strings (when the message is
/// computed dynamically, e.g. parametric "Switched to <branch>").
@MainActor
func announceToVoiceOver(message: String) {
    UIAccessibility.post(notification: .announcement, argument: message)
}
