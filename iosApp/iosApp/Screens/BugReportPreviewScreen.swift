import SwiftUI
import Shared

/// Phase 39.5 (ADR-015 §3, §6) — SwiftUI mirror of
/// `BugReportPreviewScreen.kt` (Compose). Reachable from Settings → Beta →
/// "Send Feedback" or via the shake gesture.
///
/// UI parity with the Compose surface: description TextField at the top,
/// disclosure copy, scrollable preview of the generated GitHub Issue body,
/// Submit / Cancel actions at the bottom. The Submit action delegates to
/// the shared `BugSubmissionLauncher` via the ViewModel, which fires
/// `UIApplication.shared.openURL(url:options:completionHandler:)` against
/// the prefilled GitHub Issue URL.
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

        // Form-style layout to match the Settings screen rhythm; ScrollView
        // keeps the preview body fully visible even on small phones.
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(LocalizedStringKey("body_bug_report_includes_actions"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                // SwiftUI's `axis: .vertical` lets the TextField grow with
                // its content. lineLimit(4...12) parallels the Compose
                // `minLines = 4, maxLines = 12`.
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
                .accessibilityIdentifier("bugDescriptionField")
                .accessibilityLabel(LocalizedStringKey("label_bug_description"))

                Divider()

                Text(LocalizedStringKey("label_bug_report_preview_body"))
                    .font(.subheadline)
                    .fontWeight(.semibold)

                // SwiftUI `Text` is selectable in iOS 15+ via
                // `.textSelection(.enabled)`. Mirrors the Compose
                // `SelectionContainer` wrap.
                Text(verbatim: state.previewBody)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
                    .accessibilityIdentifier("bugReportPreviewBody")

                Button {
                    viewModel.onEvent(event: BugReportPreviewEventSubmit.shared)
                } label: {
                    Text(LocalizedStringKey("action_submit_bug_report"))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(state.isSubmitting)
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
