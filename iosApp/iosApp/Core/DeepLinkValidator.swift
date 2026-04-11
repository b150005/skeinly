import Foundation

/// Validates whether a string is a valid UUID format for share tokens.
func isValidShareToken(_ string: String) -> Bool {
    let pattern = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    return string.range(of: pattern, options: .regularExpression) != nil
}
