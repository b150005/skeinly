import AuthenticationServices
import CryptoKit
import Foundation
import Shared

/// Phase 26.1 (ADR-022 §6.1) — SwiftUI ↔ Kotlin bridge for Sign in with
/// Apple. Owns the per-request nonce that protects the ID-token claim
/// against replay attacks, and forwards the issued JWT into the shared
/// `AuthViewModel` via `KoinHelperKt.signInWithAppleIdToken(...)`.
///
/// Apple ID token verification flow (server-side, handled by Supabase):
///   1. Apple stamps the `nonce` claim of the ID token with the
///      SHA-256(plaintext) digest the request sent.
///   2. Supabase verifies that the `nonce` parameter we pass (plaintext)
///      hashes to the same value embedded in the token. Mismatch → 401.
///
/// The bridge holds plaintext nonce state across the call boundary
/// between `onRequest` (digest sent to Apple) and `onCompletion`
/// (plaintext forwarded to Supabase). Because SwiftUI's
/// `SignInWithAppleButton` does not pass per-instance state between
/// these two callbacks, the bridge stores the plaintext in a property.
/// The lifecycle is bounded: a single `beginRequest()` → `handleCompletion(...)`
/// round trip, with the property cleared at the end so a stale value
/// cannot leak into a subsequent request.
@MainActor
final class AppleSignInBridge {
    static let shared = AppleSignInBridge()

    private var currentNonce: String?

    private init() {}

    /// Generates a fresh plaintext nonce + its SHA-256 digest. The
    /// digest is what `ASAuthorizationOpenIDRequest.nonce` consumes;
    /// the plaintext is held for the completion handler so it can be
    /// forwarded to Supabase.
    @discardableResult
    func beginRequest() -> (plaintext: String, sha256Digest: String) {
        let plaintext = Self.randomNonceString()
        currentNonce = plaintext
        let digest = Self.sha256(of: plaintext)
        return (plaintext, digest)
    }

    /// Handles the SwiftUI `SignInWithAppleButton.onCompletion` callback.
    /// Extracts the ID token, pairs it with the in-flight plaintext
    /// nonce, and invokes `forward` so the call site can route to
    /// Kotlin. On any decoding failure or user cancellation, the
    /// `forward` closure is NOT invoked — the ViewModel relies on the
    /// in-flight `isSubmitting` flag being absent, which is the case
    /// since we never set it without a successful ID token.
    func handleCompletion(
        _ result: Result<ASAuthorization, Error>,
        forward: (String, String) -> Void
    ) {
        defer { currentNonce = nil }
        switch result {
        case .success(let auth):
            guard
                let credential = auth.credential as? ASAuthorizationAppleIDCredential,
                let idTokenData = credential.identityToken,
                let idToken = String(data: idTokenData, encoding: .utf8),
                let nonce = currentNonce
            else {
                // Malformed or missing fields — silently drop. The user
                // can re-tap the button. Logged for diagnostics.
                NSLog("[AppleSignInBridge] missing identityToken or nonce; dropping completion")
                return
            }
            forward(idToken, nonce)
        case .failure(let error):
            // User-cancelled or system error — both surface here. The
            // ASAuthorizationError.canceled case is silent by design
            // (matches Apple HIG); other errors are logged for
            // diagnostics but not surfaced to the user as a banner
            // because Apple's own button already animates the failure.
            NSLog("[AppleSignInBridge] sign-in failed: %@", String(describing: error))
        }
    }

    // MARK: - Nonce helpers

    /// Cryptographically random 32-character nonce drawn from a
    /// URL-safe alphabet. Matches the recommendation from Apple's
    /// "Authenticating Users with Sign in with Apple" sample code.
    private static func randomNonceString(length: Int = 32) -> String {
        precondition(length > 0)
        let charset: [Character] =
            Array("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-._")
        var result = ""
        var remaining = length
        while remaining > 0 {
            let randoms: [UInt8] = (0..<16).map { _ in
                var byte: UInt8 = 0
                let status = SecRandomCopyBytes(kSecRandomDefault, 1, &byte)
                guard status == errSecSuccess else {
                    fatalError("Unable to generate nonce. SecRandomCopyBytes status=\(status)")
                }
                return byte
            }
            for random in randoms {
                if remaining == 0 { break }
                if random < charset.count {
                    result.append(charset[Int(random)])
                    remaining -= 1
                }
            }
        }
        return result
    }

    /// SHA-256 digest of [plaintext] as a lower-case hex string. This
    /// is exactly the format Apple expects in
    /// `ASAuthorizationOpenIDRequest.nonce`.
    private static func sha256(of plaintext: String) -> String {
        let data = Data(plaintext.utf8)
        let digest = SHA256.hash(data: data)
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}
