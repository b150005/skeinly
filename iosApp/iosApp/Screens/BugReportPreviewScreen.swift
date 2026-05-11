import SwiftUI
import Shared

/// Phase 39 W5b (ADR-020) — SwiftUI mirror of `BugReportPreviewScreen.kt`
/// (Compose). Reachable from Settings → Beta → "Send Feedback" or via
/// the shake gesture.
///
/// W5b changes over Phase 39.5:
/// - The "Send report" button now POSTs to the `submit-bug-report`
///   Edge Function which authenticates as the "Skeinly Beta Bug
///   Reporter" GitHub App and creates the Issue server-side. No
///   browser hand-off.
/// - A post-submit banner renders the result above the editor:
///   Success banner with the GitHub Issue number ("#123"), or one of
///   six typed error banners. Dismiss button clears the banner so the
///   user can edit + retry.
/// - Submit button shows a `ProgressView` + "Sending…" label while
///   the HTTP round-trip is in flight; the underlying ViewModel
///   guards against double-submit so multi-tap is harmless.
struct BugReportPreviewScreen: View {
    @StateObject private var holder: ScopedViewModel<
        BugReportPreviewViewModel,
        BugReportPreviewState
    >
    private let onCancel: () -> Void

    init(onCancel: @escaping () -> Void) {
        let vm = ViewModelFactory.bugReportPreviewViewModel()
        let wrapper = KoinHelperKt.wrapBugReportPreviewState(flow: vm.state)
        _holder = StateObject(
            wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper)
        )
        self.onCancel = onCancel
    }

    private var viewModel: BugReportPreviewViewModel { holder.viewModel }

    var body: some View {
        let state = holder.state

        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Result banner — surfaces success / error of the most
                // recent submission. Null state hides the banner.
                if let result = state.submitResult {
                    ResultBanner(result: result) {
                        viewModel.onEvent(event: BugReportPreviewEventDismissResult.shared)
                    }
                }

                Text(LocalizedStringKey("body_bug_report_includes_actions"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                TextField(
                    LocalizedStringKey("hint_bug_description_placeholder"),
                    text: Binding(
                        get: { state.description },
                        set: { newValue in
                            viewModel.onEvent(
                                event: BugReportPreviewEventDescriptionChanged(value: newValue)
                            )
                        }
                    ),
                    axis: .vertical
                )
                .lineLimit(4...12)
                .textFieldStyle(.roundedBorder)
                .disabled(state.isSubmitting)
                .accessibilityIdentifier("bugDescriptionField")
                .accessibilityLabel(LocalizedStringKey("label_bug_description"))

                Divider()

                Text(LocalizedStringKey("label_bug_report_preview_body"))
                    .font(.subheadline)
                    .fontWeight(.semibold)

                Text(verbatim: state.previewBody)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
                    .accessibilityIdentifier("bugReportPreviewBody")

                Button {
                    viewModel.onEvent(event: BugReportPreviewEventSubmit.shared)
                } label: {
                    HStack(spacing: 8) {
                        if state.isSubmitting {
                            ProgressView()
                                .progressViewStyle(.circular)
                                .controlSize(.small)
                                .tint(.white)
                            Text(LocalizedStringKey("state_bug_report_submitting"))
                        } else {
                            Text(LocalizedStringKey("action_submit_bug_report"))
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                // Disable on in-flight or success — error states stay
                // enabled so retry is one tap. Mirrors the Compose gate.
                .disabled(state.isSubmitting || state.submitResult is SubmitResultStateSuccess)
                .accessibilityIdentifier("submitBugReportButton")

                Button(role: .cancel) {
                    onCancel()
                } label: {
                    Text(LocalizedStringKey("action_cancel"))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("cancelBugReportButton")
            }
            .padding()
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("bugReportPreviewScreen")
        .navigationTitle(LocalizedStringKey("title_bug_report_preview"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Post-submit banner. Maps each [SubmitResultState] subclass to the
/// matching localized copy. Exhaustive switch via Swift's NSEnum
/// bridge of the Kotlin sealed-interface `ErrorKind`.
private struct ResultBanner: View {
    let result: SubmitResultState
    let onDismiss: () -> Void

    var body: some View {
        let (text, background, foreground): (LocalizedStringKey, Color, Color) = {
            if let success = result as? SubmitResultStateSuccess {
                // "Bug report sent. Thank you! #123"
                let key = LocalizedStringKey(
                    "\(NSLocalizedString("state_bug_report_submitted", comment: "")) #\(success.issueNumber)",
                )
                return (key, Color.accentColor.opacity(0.18), Color.primary)
            }
            if let error = result as? SubmitResultStateError {
                return (errorKey(for: error.kind), Color.red.opacity(0.12), Color.primary)
            }
            // Defensive default — keep the banner visible with the
            // dismiss button so the user can clear it rather than
            // hide it silently if a future SubmitResultState subclass
            // bridges through without a Swift switch case.
            return (LocalizedStringKey("state_bug_report_error_unknown"), Color.red.opacity(0.12), Color.primary)
        }()

        VStack(alignment: .leading, spacing: 8) {
            Text(text)
                .font(.body)
                .foregroundStyle(foreground)
            HStack {
                Spacer()
                Button {
                    onDismiss()
                } label: {
                    Text(LocalizedStringKey("action_bug_report_dismiss_result"))
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .accessibilityIdentifier("bugReportResultBannerDismiss")
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 12).fill(background),
        )
        .accessibilityIdentifier("bugReportResultBanner")
    }

    private func errorKey(for kind: ErrorKind) -> LocalizedStringKey {
        // Default arm guards against future ErrorKind additions
        // bridging through without a Swift case — same defensive
        // pattern as the SettingsScreen notification status mapping.
        switch kind {
        case .offline: return LocalizedStringKey("state_bug_report_error_offline")
        case .rateLimited: return LocalizedStringKey("state_bug_report_error_rate_limited")
        case .validationFailed: return LocalizedStringKey("state_bug_report_error_validation")
        case .configMissing: return LocalizedStringKey("state_bug_report_error_config_missing")
        case .server: return LocalizedStringKey("state_bug_report_error_server")
        case .unknown: return LocalizedStringKey("state_bug_report_error_unknown")
        default: return LocalizedStringKey("state_bug_report_error_unknown")
        }
    }
}
