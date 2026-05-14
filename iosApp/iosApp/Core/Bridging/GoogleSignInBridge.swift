import Foundation
import GoogleSignIn
import Shared
import UIKit

/// Phase 26.3 (ADR-022 §6.2) — SwiftUI ↔ Kotlin bridge for Google
/// Sign-In on iOS. Owns the `GIDSignIn` modal presentation flow and
/// forwards the issued ID token into the shared `AuthViewModel` via
/// `KoinHelperKt.signInWithGoogleIdToken(...)`.
///
/// Architecture mirror with `AppleSignInBridge`:
///   - Swift owns the presentation (needs UIKit's
///     `UIWindow.rootViewController` — doesn't generalize across
///     platforms, so kept native).
///   - Kotlin owns the repository forward (single shared
///     `AuthRepository.signInWithGoogle` call).
///
/// `GIDSignIn.sharedInstance` reads `CLIENT_ID` from
/// `GoogleService-Info.plist` automatically when the SDK is first
/// touched; no explicit `GIDConfiguration` setup is required as long
/// as the plist is shipped in the app bundle. When the plist is
/// missing (local-dev / CI without `IOS_GOOGLE_SERVICES_PLIST_BASE64`),
/// `signIn(withPresenting:)` reports a configuration error which we
/// surface as a logged failure — Apple Sign-In + email/password remain
/// functional.
///
/// **Lives under `Core/Bridging/`** because it imports the `Shared`
/// framework — the `iosAppTests` target excludes that directory per
/// the project.yml convention block (test target does not link
/// `Shared.framework`). Same placement as `AppleSignInBridge`.
@MainActor
final class GoogleSignInBridge {
    static let shared = GoogleSignInBridge()

    private init() {}

    /// Resolves the currently-foregrounded `UIWindow.rootViewController`
    /// for use as the GIDSignIn presenting view controller. SwiftUI's
    /// scene graph hosts views inside a `UIHostingController` whose
    /// parent is the window's root — walking the connected scenes
    /// finds the active one even when multi-window iPad presents
    /// multiple SwiftUI scenes.
    ///
    /// Returns `nil` when no foreground window is available (extremely
    /// rare — would imply the app is backgrounded while a sign-in
    /// tap is being processed; the caller treats this as a failure
    /// and abandons the flow).
    private func rootPresentingViewController() -> UIViewController? {
        let activeScene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first(where: { $0.activationState == .foregroundActive })
            ?? UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .first
        let keyWindow = activeScene?.windows.first(where: { $0.isKeyWindow })
            ?? activeScene?.windows.first
        return keyWindow?.rootViewController
    }

    /// Presents the GIDSignIn modal and forwards the resulting ID
    /// token to the supplied `forward` closure on the happy path. On
    /// user cancellation or any SDK error, `forward` is NOT invoked —
    /// the ViewModel's submitting flag never flips because we only
    /// publish `AuthEvent.SignInWithGoogleIdToken` on success.
    ///
    /// The `forward` closure is intentionally non-async + non-throwing
    /// so the call site reads cleanly inside a SwiftUI `Button.action`
    /// (which doesn't support async). `KoinHelperKt.signInWithGoogleIdToken`
    /// publishes an event and returns immediately; the actual repository
    /// call runs on the shared `viewModelScope`.
    func signIn(forward: @escaping (String, String?) -> Void) {
        guard let presenting = rootPresentingViewController() else {
            NSLog("[GoogleSignInBridge] no foreground window; abandoning sign-in")
            return
        }
        GIDSignIn.sharedInstance.signIn(withPresenting: presenting) { result, error in
            // Both branches: failure paths log + drop. Cancellation
            // surfaces as `error.code == GIDSignInError.canceled.rawValue`
            // — silent by design (matches Apple Sign-In HIG: user
            // dismissing the picker should not generate a banner).
            if let error {
                NSLog("[GoogleSignInBridge] sign-in failed: %@", String(describing: error))
                return
            }
            guard
                let user = result?.user,
                let idTokenString = user.idToken?.tokenString
            else {
                NSLog("[GoogleSignInBridge] result missing user or idToken; dropping")
                return
            }
            // GIDSignIn 8.x does not stamp a nonce by default; Supabase
            // accepts nonceless Google ID tokens (parity with Android's
            // Phase 26.2 Credential Manager path which also omits the
            // nonce). Forward-compat: pass plaintext nonce here when
            // upstream wires `signIn(withPresenting:nonce:)` and we
            // mirror the Apple Sign-In digest scheme on Google.
            forward(idTokenString, nil)
        }
    }

    /// Handle a URL opened in response to the GIDSignIn OAuth flow.
    /// Returns `true` if GIDSignIn consumed the URL. SwiftUI call site
    /// is `.onOpenURL { url in _ = GoogleSignInBridge.shared.handle(url: url) }`
    /// inside the app's root view modifier chain.
    @discardableResult
    func handle(url: URL) -> Bool {
        GIDSignIn.sharedInstance.handle(url)
    }
}
