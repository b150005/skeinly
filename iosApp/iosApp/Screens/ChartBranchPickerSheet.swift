import SwiftUI
import Shared

/// SwiftUI mirror of the shared Compose `ChartBranchPickerSheet` (Phase 37.4,
/// ADR-013 §7). Owns a Koin-resolved `ChartBranchPickerViewModel` via
/// `ScopedViewModel` so the observed state survives sheet open/close cycles.
///
/// Body: live branch list + a "New branch" CTA. The row matching the chart's
/// current tip carries the `label_current_branch` chip; non-current rows
/// expose a Switch button.
///
/// On a successful switch the picker closes via `BranchSwitchedEvent` and the
/// caller receives `onBranchSwitched(branchName:)` so it can surface a
/// transient toast (the SwiftUI parity with Compose's Snackbar).
struct ChartBranchPickerSheet: View {
    let patternId: String
    let onDismiss: () -> Void
    let onBranchSwitched: (String) -> Void

    @StateObject private var holder: ScopedViewModel<ChartBranchPickerViewModel, ChartBranchPickerState>
    @State private var branchSwitchedCloseable: Closeable?
    @State private var showCreateDialog = false

    init(
        patternId: String,
        onDismiss: @escaping () -> Void,
        onBranchSwitched: @escaping (String) -> Void
    ) {
        self.patternId = patternId
        self.onDismiss = onDismiss
        self.onBranchSwitched = onBranchSwitched
        let vm = ViewModelFactory.chartBranchPickerViewModel(patternId: patternId)
        let wrapper = KoinHelperKt.wrapChartBranchPickerState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    private var viewModel: ChartBranchPickerViewModel { holder.viewModel }

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(LocalizedStringKey("title_branch_picker"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(LocalizedStringKey("action_create_branch")) {
                            showCreateDialog = true
                        }
                        .accessibilityIdentifier("createBranchCta")
                    }
                }
        }
        .accessibilityIdentifier("branchPickerSheet")
        .task { observeBranchSwitched() }
        .onDisappear {
            branchSwitchedCloseable?.close()
            branchSwitchedCloseable = nil
        }
        .sheet(isPresented: $showCreateDialog) {
            CreateBranchDialog(
                onConfirm: { name in
                    viewModel.onEvent(event: ChartBranchPickerEventCreateBranch(branchName: name))
                    showCreateDialog = false
                },
                onDismiss: { showCreateDialog = false }
            )
            .presentationDetents([.medium])
        }
    }

    @ViewBuilder
    private var content: some View {
        let state = holder.state
        if state.branches.isEmpty && !state.isLoading {
            ContentUnavailableView(
                LocalizedStringKey("state_no_branches"),
                systemImage: "arrow.triangle.branch"
            )
        } else {
            List(state.branches, id: \.id) { branch in
                BranchRow(
                    branch: branch,
                    isCurrent: branch.tipRevisionId == state.currentRevisionId,
                    onSwitch: {
                        viewModel.onEvent(
                            event: ChartBranchPickerEventSwitchBranch(branchName: branch.branchName)
                        )
                    }
                )
            }
        }
    }

    private func observeBranchSwitched() {
        branchSwitchedCloseable?.close()
        branchSwitchedCloseable = nil
        let wrapper = KoinHelperKt.wrapChartBranchSwitchedFlow(flow: viewModel.branchSwitched)
        branchSwitchedCloseable = wrapper.collect { event in
            // The branch name comes from the event envelope so the consumer
            // is independent of the timing race between `_branchSwitched.trySend`
            // and the `combine` block writing the new `currentRevisionId` into
            // `_state`. See `BranchSwitchedEvent` KDoc.
            let name = event.branchName
            Task { @MainActor in onBranchSwitched(name) }
        }
    }
}

private struct BranchRow: View {
    let branch: ChartBranch
    let isCurrent: Bool
    let onSwitch: () -> Void

    var body: some View {
        HStack {
            Text(verbatim: branch.branchName)
                .font(.body)
            Spacer()
            if isCurrent {
                Label(LocalizedStringKey("label_current_branch"), systemImage: "checkmark")
                    .labelStyle(.titleAndIcon)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("currentBranchChip_\(branch.branchName)")
            } else {
                Button(LocalizedStringKey("action_switch"), action: onSwitch)
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("switchBranchButton_\(branch.branchName)")
            }
        }
        .accessibilityIdentifier("branchRow_\(branch.branchName)")
    }
}

private struct CreateBranchDialog: View {
    let onConfirm: (String) -> Void
    let onDismiss: () -> Void

    @State private var name: String = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField(
                    LocalizedStringKey("label_branch_name"),
                    text: $name
                )
                .accessibilityIdentifier("branchNameInput")
            }
            .navigationTitle(LocalizedStringKey("dialog_create_branch_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(LocalizedStringKey("action_cancel"), action: onDismiss)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(LocalizedStringKey("action_create_branch")) {
                        onConfirm(name)
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                    .accessibilityIdentifier("confirmCreateBranchButton")
                }
            }
        }
        .accessibilityIdentifier("createBranchDialog")
    }
}
