import Foundation
import Shared

/// Phase 39 (ADR-021 §D4) — resolver mirroring the Compose
/// `UgcReportCategory.localizedLabel()` composable. Turns a Kotlin
/// `UgcReportCategory` enum case into a localized Swift `String` over
/// the iOS String Catalog.
///
/// Kotlin enums bridge to Swift as a final class (NOT a Swift `enum`),
/// so `switch self { case .spam: }` does not type-check. We branch on
/// the stable `wireValue` property (the same string the migration-031
/// CHECK + the Edge Function `REASON_CATEGORIES` use), which is the
/// single source of truth shared with the Kotlin side. An unmapped
/// value falls back to the generic "Something else" label.
extension UgcReportCategory {
    var localizedLabel: String {
        switch wireValue {
        case "spam":
            return NSLocalizedString("report_category_spam", comment: "")
        case "harassment":
            return NSLocalizedString("report_category_harassment", comment: "")
        case "sexual":
            return NSLocalizedString("report_category_sexual", comment: "")
        case "violence":
            return NSLocalizedString("report_category_violence", comment: "")
        case "hate":
            return NSLocalizedString("report_category_hate", comment: "")
        case "ip":
            return NSLocalizedString("report_category_ip", comment: "")
        case "other":
            return NSLocalizedString("report_category_other", comment: "")
        default:
            return NSLocalizedString("report_category_other", comment: "")
        }
    }
}
