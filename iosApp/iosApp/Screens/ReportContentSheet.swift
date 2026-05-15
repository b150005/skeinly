import Shared
import SwiftUI

/// Phase 39 (ADR-021 §D4) — SwiftUI mirror of the Compose
/// `ReportContentDialog`. Reusable "Report content" sheet for every
/// UGC surface (Discovery pattern cards, Suggestion threads). Mounts a
/// fresh `UgcReportViewModel` keyed on `targetType` + `targetId` via
/// `ViewModelFactory.ugcReportViewModel(...)` (Koin parametric
/// resolution; same precedent as `WipeDataConfirmPhraseView`).
///
/// On a successful submit the VM emits `UgcReportNavEventSubmitted`;
/// this view forwards it as `onSubmitted` so the presenter dismisses +
/// flashes a confirmation. Failure stays inline (the user keeps their
/// typed reason and can retry).
struct ReportContentSheet: View {
    let targetType: UgcTargetType
    let targetId: String
    let onSubmitted: () -> Void
    let onDismiss: () -> Void

    @StateObject private var holder: ScopedViewModel<UgcReportViewModel, UgcReportState>
    @State private var navCloseable: Closeable?

    private var viewModel: UgcReportViewModel { holder.viewModel }
    private let categories: [UgcReportCategory]

    init(
        targetType: UgcTargetType,
        targetId: String,
        onSubmitted: @escaping () -> Void,
        onDismiss: @escaping () -> Void
    ) {
        self.targetType = targetType
        self.targetId = targetId
        self.onSubmitted = onSubmitted
        self.onDismiss = onDismiss
        self.categories = KoinHelperKt.ugcReportCategories()
        let vm = ViewModelFactory.ugcReportViewModel(
            targetType: targetType,
            targetId: targetId
        )
        let wrapper = KoinHelperKt.wrapUgcReportState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        let state = holder.state
        NavigationStack {
            Form {
                Section {
                    Text("body_report_content_explanation")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Section("label_report_category") {
                    ForEach(categories, id: \.wireValue) { category in
                        Button {
                            viewModel.onEvent(event: UgcReportEventSelectCategory(category: category))
                        } label: {
                            HStack {
                                Text(category.localizedLabel)
                                    .foregroundStyle(.primary)
                                Spacer()
                                if state.category?.wireValue == category.wireValue {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(Color.accentColor)
                                }
                            }
                        }
                        .disabled(state.isSubmitting)
                        .accessibilityIdentifier("ugcReportCategory_\(category.wireValue)")
                    }
                }
                Section("label_report_detail") {
                    TextEditor(text: reasonBinding(state: state))
                        .frame(minHeight: 96)
                        .disabled(state.isSubmitting)
                        .accessibilityIdentifier("ugcReportReasonField")
                    Text("\(state.reason.count) / \(MutationConstants.maxUgcReasonLength)")
                        .font(.caption)
                        .foregroundStyle(state.reasonValid ? Color.secondary : Color.red)
                }
                if let err = state.error {
                    Section {
                        Text(err.localizedString)
                            .font(.footnote)
                            .foregroundStyle(.red)
                            .accessibilityIdentifier("ugcReportError")
                    }
                }
            }
            .navigationTitle(LocalizedStringKey("title_report_content"))
            .navigationBarTitleDisplayMode(.inline)
            .accessibilityIdentifier("ugcReportSheet")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("action_cancel") { onDismiss() }
                        .disabled(state.isSubmitting)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if state.isSubmitting {
                        ProgressView()
                    } else {
                        Button("action_report_submit") {
                            viewModel.onEvent(event: UgcReportEventSubmit.shared)
                        }
                        .disabled(!state.submitEnabled())
                        .accessibilityIdentifier("ugcReportSubmitButton")
                    }
                }
            }
            .task {
                navCloseable?.close()
                let flow = KoinHelperKt.wrapUgcReportNavEvents(flow: viewModel.navEvents)
                navCloseable = flow.collect { event in
                    Task { @MainActor in
                        if event is UgcReportNavEventSubmitted {
                            onSubmitted()
                        }
                    }
                }
            }
            .onDisappear {
                navCloseable?.close()
                navCloseable = nil
            }
        }
    }

    private func reasonBinding(state: UgcReportState) -> Binding<String> {
        Binding(
            get: { state.reason },
            set: { viewModel.onEvent(event: UgcReportEventUpdateReason(value: $0)) }
        )
    }
}

/// Mirror of `MAX_UGC_REASON_LENGTH` (commonMain). The Kotlin top-level
/// `const val` is not bridged as a Swift symbol, so the cap is restated
/// here for the character counter. Server + repository remain the
/// authority — this is display-only.
private enum MutationConstants {
    static let maxUgcReasonLength: Int = 2000
}
