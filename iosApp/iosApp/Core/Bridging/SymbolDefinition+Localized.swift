import Foundation
import Shared

/// Y3-iOS — Catalog locale-aware resolver consolidation (Swift half).
///
/// Mirror of the Kotlin extensions
/// `fun SymbolDefinition.localizedLabel(locale: String): String` and
/// `fun SymbolCategory.localizedLabel(locale: String): String` defined in
/// `shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/symbol/LocalizedLabel.kt`
/// (Y3, commit `d09d68a`). Centralizes the predicate previously duplicated
/// inline at four iOS Swift chart-UI sites (X3 surface Swift half).
///
/// Predicate contract: any locale string whose lowercased form starts with
/// `ja` (`"ja"`, `"ja_JP"`, `"JA"`) returns `jaLabel`; anything else
/// (including the empty string and other languages) returns `enLabel`. The
/// predicate uses `hasPrefix` rather than strict equality so the contract
/// matches Y3 Kotlin's `startsWith("ja", ignoreCase = true)` exactly and
/// stays forward-safe if a future call site passes a BCP-47 full tag like
/// `"ja-JP"` instead of the ISO 639-1 short code.
///
/// Diverges intentionally from the existing `UgcReportCategory+Localized.swift`
/// / `ErrorMessage+Localized.swift` Bridging convention (which use a no-arg
/// `var localizedLabel: String` because they route through `NSLocalizedString`
/// for iOS String Catalog auto-resolution). `SymbolDefinition` carries the two
/// label strings inline (`jaLabel` / `enLabel` on the data class), so the
/// resolver is function-shaped — call sites pass an explicit locale, which
/// keeps the helper a pure value-in / value-out function and matches the
/// Y3 Kotlin signature symmetrically.
extension SymbolDefinition {
    func localizedLabel(locale: String) -> String {
        locale.lowercased().hasPrefix("ja") ? jaLabel : enLabel
    }
}

/// Symmetric to `SymbolDefinition.localizedLabel(locale:)`. Mirrors
/// `fun SymbolCategory.localizedLabel(locale: String)` for forward-compat
/// with future Swift consumers (e.g. `SymbolGalleryScreen.swift` /
/// `Text(category.enLabel)` site ports). No current Swift caller — kept for
/// API symmetry with the Kotlin half so the Swift side mirrors Y3 exactly.
extension SymbolCategory {
    func localizedLabel(locale: String) -> String {
        locale.lowercased().hasPrefix("ja") ? jaLabel : enLabel
    }
}
