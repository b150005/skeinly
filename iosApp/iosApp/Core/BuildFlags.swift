import Foundation

/// Phase 39.2: Swift-side mirror of the shared `BuildFlags.ios.kt` actual.
///
/// Reads the `IsBetaBuild` Info.plist key (populated via the xcconfig
/// macro `IS_BETA` in `iosApp/project.yml`). Returns `true` when the
/// running binary is a beta build (Phase 39 closed-beta channel), `false`
/// for v1.0 production releases (Phase 40+).
///
/// Swift call sites (`SceneDelegate`, `ShakeDetectingController`, the
/// onboarding consent page predicate) read this enum directly rather than
/// bridging through the Kotlin `BuildFlags` object. This avoids the
/// KMP/Swift interop surface for a flag whose source of truth is the
/// Info.plist entry both sides already agree on. See ADR-015 §5
/// "Swift-side access" for the rationale.
enum BuildFlags {
    static var isBeta: Bool {
        (Bundle.main.object(forInfoDictionaryKey: "IsBetaBuild") as? String) == "YES"
    }
}
