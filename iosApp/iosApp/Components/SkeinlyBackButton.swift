import SwiftUI

/// Toolbar modifier that replaces SwiftUI's auto-generated NavigationStack
/// back button with a custom one carrying a stable
/// `accessibilityIdentifier("backButton")`.
///
/// **Why**: SwiftUI's native back button does not expose an
/// `accessibilityIdentifier` public API. The label it carries is the parent
/// screen's localized title — so a Maestro flow written as
/// `tapOn: "Back"` fails on `ja-JP` locale where it renders as `戻る`. The
/// flow asserting against a hard-coded English string was the
/// Phase 33.x i18n sweep's residual brittleness in the E2E layer.
///
/// **Pattern source**: Phase 32 `StructuredChartEditorScreen` already used
/// this shape to implement a discard-guard back. This modifier generalizes
/// it so every NavigationStack destination can opt into the same
/// locale-agnostic, Maestro-friendly back button.
///
/// **Usage**: apply at the destination view (NOT the root); the root has no
/// back button. Screens with custom back semantics (e.g. discard guard)
/// should NOT use this modifier — they implement their own toolbar with the
/// same `accessibilityIdentifier("backButton")` so the Maestro contract
/// stays consistent.
struct SkeinlyBackButtonModifier: ViewModifier {
    @Binding var path: NavigationPath

    func body(content: Content) -> some View {
        content
            .navigationBarBackButtonHidden(true)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        if !path.isEmpty { path.removeLast() }
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: "chevron.left")
                            Text(LocalizedStringKey("action_back"))
                        }
                    }
                    .accessibilityIdentifier("backButton")
                }
            }
    }
}

extension View {
    /// Apply the standard Skeinly custom back button. See
    /// ``SkeinlyBackButtonModifier`` for rationale.
    func skeinlyBackButton(path: Binding<NavigationPath>) -> some View {
        modifier(SkeinlyBackButtonModifier(path: path))
    }
}
