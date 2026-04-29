import SwiftUI
import Shared

/// Phase 38.4 (ADR-014 §6) — SwiftUI mirror of the shared Compose
/// `ChartConflictResolutionScreen`. Three-pane canvas preview is deferred
/// to a polish slice (same scope-cut as Phase 36.4 iOS Discovery thumbnail);
/// the row-based picker UI alone closes the merge loop end-to-end.
///
/// Conflict cells use the iOS systemPurple at 50% alpha so they read as
/// "needs attention" — distinct from the Phase 37 traffic-light diff palette
/// per ADR-014 §6 last paragraph.
struct ChartConflictResolutionScreen: View {
    let prId: String
    @Binding var path: NavigationPath
    @StateObject private var holder: ScopedViewModel<ChartConflictResolutionViewModel, ChartConflictResolutionState>
    @State private var showError = false
    @State private var navEventsCloseable: Closeable?
    @State private var pendingMergedToast: Bool = false

    private var viewModel: ChartConflictResolutionViewModel { holder.viewModel }

    init(prId: String, path: Binding<NavigationPath>) {
        self.prId = prId
        self._path = path
        let vm = ViewModelFactory.chartConflictResolutionViewModel(prId: prId)
        let wrapper = KoinHelperKt.wrapChartConflictResolutionState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        contentView
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("chartConflictResolutionScreen")
            .navigationTitle(LocalizedStringKey("title_resolve_conflicts"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    if let report = holder.state.report {
                        let total = Int(report.conflicts.count) + Int(report.layerConflicts.count)
                        if total == 0 {
                            Text(LocalizedStringKey("state_all_conflicts_resolved"))
                                .font(.caption)
                                .accessibilityIdentifier("conflictSummaryChip")
                        } else {
                            Text(
                                String(
                                    format: NSLocalizedString("label_conflict_summary", comment: ""),
                                    total
                                )
                            )
                            .font(.caption)
                            .accessibilityIdentifier("conflictSummaryChip")
                        }
                    }
                }
            }
            .onChange(of: holder.state.error != nil) { _, hasError in
            showError = hasError
        }
            .alert(LocalizedStringKey("title_error"), isPresented: $showError) {
                Button("action_ok") {
                    viewModel.onEvent(event: ChartConflictResolutionEventClearError.shared)
                }
            } message: {
                Text(holder.state.error?.localizedString ?? "")
            }
            .overlay(alignment: .bottom) {
                if pendingMergedToast {
                    Text(LocalizedStringKey("message_pr_merged_successfully"))
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(.regularMaterial)
                        .clipShape(Capsule())
                        .padding(.bottom, 32)
                        .transition(.opacity)
                }
            }
            .task {
                navEventsCloseable?.close()
                let wrapper = KoinHelperKt.wrapChartConflictResolutionNavEvents(flow: viewModel.navEvents)
                navEventsCloseable = wrapper.collect { event in
                    Task { @MainActor in
                        if event is ChartConflictResolutionNavEventMergeApplied {
                            withAnimation { pendingMergedToast = true }
                            try? await Task.sleep(nanoseconds: 1_500_000_000)
                            withAnimation { pendingMergedToast = false }
                            // Pop back to the PR detail screen — Realtime
                            // echo will reflect the now-merged status there.
                            path.removeLast()
                        }
                    }
                }
            }
            .onDisappear {
                navEventsCloseable?.close()
                navEventsCloseable = nil
            }
    }

    @ViewBuilder
    private var contentView: some View {
        let state = holder.state
        if state.isLoading {
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let report = state.report {
            VStack(spacing: 0) {
                List {
                    if report.layerConflicts.count > 0 {
                        Section {
                            ForEach(report.layerConflicts, id: \.id) { conflict in
                                layerConflictRow(
                                    conflict: conflict,
                                    pick: state.layerResolutions[conflict.layerId]
                                )
                            }
                        }
                    }
                    if report.conflicts.count > 0 {
                        Section {
                            ForEach(report.conflicts, id: \.id) { conflict in
                                cellConflictRow(
                                    conflict: conflict,
                                    pick: state.cellResolutions[
                                        CellCoordinate(
                                            layerId: conflict.layerId,
                                            x: conflict.x,
                                            y: conflict.y
                                        )
                                    ]
                                )
                            }
                        }
                    }
                }
                .listStyle(.insetGrouped)

                applyAndMergeBar(state: state)
            }
        } else {
            ContentUnavailableView(
                LocalizedStringKey("title_resolve_conflicts"),
                systemImage: "exclamationmark.triangle"
            )
        }
    }

    @ViewBuilder
    private func cellConflictRow(conflict: CellConflict, pick: ConflictResolution?) -> some View {
        let testTagSuffix = "\(conflict.layerId)_\(conflict.x)_\(conflict.y)"
        VStack(alignment: .leading, spacing: 8) {
            Text(
                String(
                    format: NSLocalizedString("label_conflict_cell", comment: ""),
                    Int(truncating: conflict.x as NSNumber),
                    Int(truncating: conflict.y as NSNumber)
                )
            )
            .font(.subheadline.weight(.semibold))
            resolutionPickerRow(
                pick: pick,
                takeTheirsTag: "takeTheirsButton_\(testTagSuffix)",
                keepMineTag: "keepMineButton_\(testTagSuffix)",
                skipTag: "skipButton_\(testTagSuffix)"
            ) { resolution in
                viewModel.onEvent(
                    event: ChartConflictResolutionEventPickCell(
                        coordinate: CellCoordinate(
                            layerId: conflict.layerId,
                            x: conflict.x,
                            y: conflict.y
                        ),
                        resolution: resolution
                    )
                )
            }
        }
        // 0.45 alpha aligns visual weight with Compose's tertiaryContainer @ 50% so
// the "needs attention" affordance reads consistently across platforms.
.listRowBackground(Color(.systemPurple).opacity(0.45))
        .accessibilityIdentifier("conflictRow_\(testTagSuffix)")
    }

    @ViewBuilder
    private func layerConflictRow(conflict: LayerConflict, pick: ConflictResolution?) -> some View {
        // Prefer human-readable layer name; layerId is a UUID and meaningless
        // to the user. At least one of theirs/mine is non-null on a
        // PropertyChanged conflict.
        let layerName = conflict.theirs?.name
            ?? conflict.mine?.name
            ?? conflict.ancestor?.name
            ?? conflict.layerId
        VStack(alignment: .leading, spacing: 8) {
            Text("\(NSLocalizedString("label_conflict_layer", comment: "")): \(layerName)")
                .font(.subheadline.weight(.semibold))
            resolutionPickerRow(
                pick: pick,
                takeTheirsTag: "takeTheirsLayerButton_\(conflict.layerId)",
                keepMineTag: "keepMineLayerButton_\(conflict.layerId)",
                skipTag: "skipLayerButton_\(conflict.layerId)"
            ) { resolution in
                viewModel.onEvent(
                    event: ChartConflictResolutionEventPickLayer(
                        layerId: conflict.layerId,
                        resolution: resolution
                    )
                )
            }
        }
        // 0.45 alpha aligns visual weight with Compose's tertiaryContainer @ 50% so
// the "needs attention" affordance reads consistently across platforms.
.listRowBackground(Color(.systemPurple).opacity(0.45))
        .accessibilityIdentifier("layerConflictRow_\(conflict.layerId)")
    }

    @ViewBuilder
    private func resolutionPickerRow(
        pick: ConflictResolution?,
        takeTheirsTag: String,
        keepMineTag: String,
        skipTag: String,
        onPick: @escaping (ConflictResolution) -> Void
    ) -> some View {
        HStack(spacing: 8) {
            pickerButton(
                isSelected: pick == .takeTheirs,
                label: LocalizedStringKey("action_take_theirs"),
                tag: takeTheirsTag,
                onTap: { onPick(.takeTheirs) }
            )
            pickerButton(
                isSelected: pick == .keepMine,
                label: LocalizedStringKey("action_keep_mine"),
                tag: keepMineTag,
                onTap: { onPick(.keepMine) }
            )
            pickerButton(
                isSelected: pick == .skip,
                label: LocalizedStringKey("action_skip_conflict"),
                tag: skipTag,
                onTap: { onPick(.skip) }
            )
        }
    }

    @ViewBuilder
    private func pickerButton(
        isSelected: Bool,
        label: LocalizedStringKey,
        tag: String,
        onTap: @escaping () -> Void
    ) -> some View {
        Button(label, action: onTap)
            .buttonStyle(isSelected ? AnyButtonStyle(.borderedProminent) : AnyButtonStyle(.bordered))
            .accessibilityIdentifier(tag)
    }

    @ViewBuilder
    private func applyAndMergeBar(state: ChartConflictResolutionState) -> some View {
        HStack {
            Spacer()
            Button(LocalizedStringKey("action_apply_and_merge")) {
                viewModel.onEvent(event: ChartConflictResolutionEventApplyAndMerge.shared)
            }
            .buttonStyle(.borderedProminent)
            .disabled(!state.canApplyAndMerge)
            .accessibilityIdentifier("applyAndMergeButton")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(.thinMaterial)
    }
}

/// Type-erasing wrapper so the `pickerButton` helper can choose between
/// `.borderedProminent` and `.bordered` at runtime. SwiftUI's `buttonStyle`
/// is opaque at the type level, so a plain ternary won't work.
private struct AnyButtonStyle: PrimitiveButtonStyle {
    private let _makeBody: (Configuration) -> AnyView

    init<S: PrimitiveButtonStyle>(_ style: S) {
        self._makeBody = { config in
            AnyView(style.makeBody(configuration: config))
        }
    }

    func makeBody(configuration: Configuration) -> some View {
        _makeBody(configuration)
    }
}
