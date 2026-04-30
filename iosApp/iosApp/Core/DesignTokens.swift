import SwiftUI

enum DesignTokens {
    // MARK: - Spacing

    static let chipPaddingH: CGFloat = 14
    static let chipPaddingV: CGFloat = 7
    static let badgePaddingH: CGFloat = 8
    static let badgePaddingV: CGFloat = 3
    static let listRowPaddingV: CGFloat = 4

    // MARK: - Counter (ProjectDetailScreen)

    static let counterFontSize: CGFloat = 72
    static let decrementButtonSize: CGFloat = 48
    static let incrementButtonSize: CGFloat = 64

    // MARK: - Profile Avatar

    static let avatarSizeLarge: CGFloat = 80
    static let avatarSizeSmall: CGFloat = 60

    // MARK: - Opacity

    static let highlightOpacity: Double = 0.15

    // MARK: - Brand

    /// Brand accent color (#7B61FF) sourced from `Assets.xcassets/AccentColor`.
    /// SwiftUI auto-applies this as the default tint across the app — explicit
    /// references are only needed when a child view's local context overrides
    /// the inherited tint (e.g. swipe-actions buttons, custom status indicators).
    static let brandAccent: Color = .accentColor
}
