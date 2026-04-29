import SwiftUI
import Shared

/// SwiftUI counterpart of `ChartViewerScreen`. Owns a Koin-resolved
/// `ChartViewerViewModel` via `ScopedViewModel` so the observed state survives
/// parent re-inits. Phase 34 adds per-segment progress overlay + tap/long-press
/// gestures when `projectId` is non-null.
struct StructuredChartViewerScreen: View {
    let patternId: String
    let projectId: String?
    @Binding var path: NavigationPath
    @StateObject private var holder: ScopedViewModel<ChartViewerViewModel, ChartViewerState>
    private let catalog: SymbolCatalog
    @State private var showBranchPicker = false
    @State private var switchedToast: String?
    /// Phase 38.4.1 — toast for "Pull request opened" feedback. Same 2-second
    /// auto-dismiss pattern as `switchedToast` since SwiftUI lacks a non-blocking
    /// Snackbar equivalent in this codebase.
    @State private var prOpenedToast: String?
    /// Closeable for the navEvents Flow subscription. Closed in `.onDisappear`
    /// per the established Phase 32.2 / 36.5 iOS Closeable leak audit pattern.
    @State private var navEventsCloseable: Closeable?

    init(patternId: String, projectId: String?, path: Binding<NavigationPath>) {
        self.patternId = patternId
        self.projectId = projectId
        self._path = path
        let vm = ViewModelFactory.chartViewerViewModel(patternId: patternId, projectId: projectId)
        let wrapper = KoinHelperKt.wrapChartViewerState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
        self.catalog = ViewModelFactory.symbolCatalog()
    }

    private var viewModel: ChartViewerViewModel { holder.viewModel }

    var body: some View {
        Group {
            if holder.state.isLoading {
                ProgressView()
            } else if let chart = holder.state.chart {
                content(chart: chart)
            } else {
                ContentUnavailableView(
                    LocalizedStringKey("state_no_structured_chart"),
                    systemImage: "square.grid.3x3"
                )
            }
        }
        .navigationTitle(LocalizedStringKey("title_chart_viewer"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            // Phase 37.2 (ADR-013 §6) overflow → "View history". Mirrors the
            // Compose ChartViewerScreen TopAppBar `actions` overflow.
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button {
                        path.append(Route.chartHistory(patternId: patternId))
                    } label: {
                        Label(LocalizedStringKey("title_chart_history"), systemImage: "clock.arrow.circlepath")
                    }
                    .accessibilityIdentifier("viewHistoryMenuItem")

                    Button {
                        showBranchPicker = true
                    } label: {
                        Label(LocalizedStringKey("title_branch_picker"), systemImage: "arrow.triangle.branch")
                    }
                    .accessibilityIdentifier("openBranchPickerMenuItem")

                    // Phase 38.4.1 — Open PR entry. Visibility gated on the
                    // ViewModel-side `canOpenPullRequest` derived property so
                    // forks owned by current user with resolved branches see
                    // the entry; non-fork or non-owner viewers see no item
                    // (parallel to the Compose `if (state.canOpenPullRequest)`
                    // gate above).
                    if holder.state.canOpenPullRequest {
                        Button {
                            viewModel.onEvent(event: ChartViewerEventRequestOpenPullRequest())
                        } label: {
                            Label(
                                LocalizedStringKey("action_open_pull_request"),
                                systemImage: "bubble.left.and.bubble.right"
                            )
                        }
                        .accessibilityIdentifier("openPullRequestMenuItem")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .accessibilityLabel(LocalizedStringKey("action_more_options"))
                }
                .accessibilityIdentifier("overflowMenuButton")
            }
        }
        .sheet(isPresented: $showBranchPicker) {
            ChartBranchPickerSheet(
                patternId: patternId,
                onDismiss: { showBranchPicker = false },
                onBranchSwitched: { branchName in
                    let template = NSLocalizedString("message_switched_to_branch", comment: "")
                    switchedToast = String(format: template, branchName)
                    showBranchPicker = false
                }
            )
        }
        // Phase 38.4.1 — Open PR form sheet. `Binding<Bool>` derived from the
        // ViewModel's `pendingOpenPrSheet` flag; dismissal routes through the
        // ViewModel event so drafts get cleared.
        .sheet(
            isPresented: Binding(
                get: { holder.state.pendingOpenPrSheet },
                set: { newValue in
                    if !newValue {
                        viewModel.onEvent(event: ChartViewerEventDismissOpenPullRequestSheet())
                    }
                }
            )
        ) {
            OpenPullRequestSheet(
                titleDraft: holder.state.openPrTitleDraft,
                descriptionDraft: holder.state.openPrDescriptionDraft,
                isSubmitting: holder.state.isOpeningPullRequest,
                errorMessage: holder.state.openPrError,
                onTitleChange: { value in
                    viewModel.onEvent(event: ChartViewerEventOpenPrTitleChanged(value: value))
                },
                onDescriptionChange: { value in
                    viewModel.onEvent(event: ChartViewerEventOpenPrDescriptionChanged(value: value))
                },
                onConfirm: {
                    viewModel.onEvent(event: ChartViewerEventConfirmOpenPullRequest())
                },
                onDismiss: {
                    viewModel.onEvent(event: ChartViewerEventDismissOpenPullRequestSheet())
                }
            )
        }
        .overlay(alignment: .bottom) {
            // Two independent toasts; the most recent message wins via
            // ZStack-style ordering (SwiftUI overlays the last non-nil branch).
            if let message = prOpenedToast {
                Text(verbatim: message)
                    .font(.callout)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.thinMaterial, in: Capsule())
                    .padding(.bottom, 24)
                    .transition(.opacity)
                    .task {
                        try? await Task.sleep(nanoseconds: 2_000_000_000)
                        prOpenedToast = nil
                    }
            } else if let message = switchedToast {
                Text(verbatim: message)
                    .font(.callout)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.thinMaterial, in: Capsule())
                    .padding(.bottom, 24)
                    .transition(.opacity)
                    .task {
                        try? await Task.sleep(nanoseconds: 2_000_000_000)
                        switchedToast = nil
                    }
            }
        }
        .task {
            // Subscribe to nav events; close any prior subscription on view
            // re-appearance per the iOS Closeable leak audit pattern.
            navEventsCloseable?.close()
            let wrapper = KoinHelperKt.wrapChartViewerNavEvents(flow: viewModel.navEvents)
            navEventsCloseable = wrapper.collect { event in
                Task { @MainActor in
                    if let created = event as? ChartViewerNavEventPullRequestCreated {
                        let message = NSLocalizedString("message_pr_opened_successfully", comment: "")
                        prOpenedToast = message
                        path.append(Route.pullRequestDetail(prId: created.prId))
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
    private func content(chart: StructuredChart) -> some View {
        VStack(spacing: 4) {
            if !chart.layers.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(chart.layers, id: \.id) { layer in
                            let visible = !holder.state.hiddenLayerIds.contains(layer.id)
                            Button {
                                viewModel.onEvent(
                                    event: ChartViewerEventToggleLayer(layerId: layer.id)
                                )
                            } label: {
                                Text(verbatim: layer.name)
                                    .font(.caption)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .background(
                                        Capsule().fill(visible ? Color.accentColor.opacity(0.2) : Color.gray.opacity(0.1))
                                    )
                                    .foregroundStyle(visible ? Color.accentColor : .secondary)
                            }
                        }
                    }
                    .padding(.horizontal)
                }
            }

            ChartCanvasView(
                chart: chart,
                catalog: catalog,
                hiddenLayerIds: holder.state.hiddenLayerIds,
                segmentLookup: { layerId, x, y in
                    viewModel.segmentStateAt(layerId: layerId, x: Int32(x), y: Int32(y))
                },
                segmentsVersion: holder.state.segments.count,
                onTap: { layerId, x, y in
                    viewModel.onEvent(event: ChartViewerEventTapCell(layerId: layerId, x: Int32(x), y: Int32(y)))
                },
                onLongPress: { layerId, x, y in
                    let generator = UIImpactFeedbackGenerator(style: .medium)
                    generator.impactOccurred()
                    viewModel.onEvent(event: ChartViewerEventLongPressCell(layerId: layerId, x: Int32(x), y: Int32(y)))
                },
                onMarkRowDone: { row in
                    let generator = UIImpactFeedbackGenerator(style: .medium)
                    generator.impactOccurred()
                    viewModel.onEvent(event: ChartViewerEventMarkRowDone(row: Int32(row)))
                }
            )
            .accessibilityIdentifier("segmentOverlay")
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

/// Phase 38.4.1 (ADR-014 §6) — SwiftUI mirror of Compose `OpenPullRequestDialog`.
/// `NavigationStack` + `Form` + Cancel / Open toolbar items, matching the
/// `ChartBranchPickerSheet` / `Open PR` form pattern. Title is required;
/// description is optional. Errors render inline so the user stays in the form
/// for retry.
private struct OpenPullRequestSheet: View {
    let titleDraft: String
    let descriptionDraft: String
    let isSubmitting: Bool
    let errorMessage: ErrorMessage?
    let onTitleChange: (String) -> Void
    let onDescriptionChange: (String) -> Void
    let onConfirm: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(
                        LocalizedStringKey("hint_pr_title"),
                        text: Binding(get: { titleDraft }, set: onTitleChange)
                    )
                    .accessibilityIdentifier("openPrTitleInput")
                    .disabled(isSubmitting)
                } header: {
                    Text(LocalizedStringKey("label_pr_title"))
                }

                Section {
                    TextField(
                        LocalizedStringKey("hint_pr_description_optional"),
                        text: Binding(get: { descriptionDraft }, set: onDescriptionChange),
                        axis: .vertical
                    )
                    .lineLimit(3...8)
                    .accessibilityIdentifier("openPrDescriptionInput")
                    .disabled(isSubmitting)
                } header: {
                    Text(LocalizedStringKey("label_pr_description"))
                }

                if let errorMessage {
                    Section {
                        Text(errorMessage.localizedString)
                            .font(.footnote)
                            .foregroundStyle(.red)
                            .accessibilityIdentifier("openPrErrorLabel")
                    }
                }
            }
            .navigationTitle(LocalizedStringKey("dialog_open_pull_request_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(LocalizedStringKey("action_cancel"), action: onDismiss)
                        .disabled(isSubmitting)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(LocalizedStringKey("action_open_pr"), action: onConfirm)
                        .disabled(isSubmitting || titleDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .accessibilityIdentifier("confirmOpenPullRequestButton")
                }
            }
            .accessibilityIdentifier("openPullRequestSheet")
        }
    }
}

/// Mutable cache for parsed SVG path commands keyed by symbol id.
/// Canvas with overlay + tap/long-press support. Canvas geometry is recomputed
/// on every draw so hit-test math stays in lockstep. The gesture layer sits in
/// parallel via `.simultaneousGesture` so pinch-to-zoom keeps working.
private struct ChartCanvasView: View {
    let chart: StructuredChart
    let catalog: SymbolCatalog
    let hiddenLayerIds: Set<String>
    /// Reads segment state by (layerId, x, y) — routes through the Kotlin
    /// helper to avoid bridging a Kotlin `Map<SegmentKey, SegmentState>`
    /// through Swift `Hashable`.
    let segmentLookup: (String, Int, Int) -> SegmentState?
    /// Sentinel value that changes whenever the segment map changes — forces
    /// SwiftUI to re-evaluate the Canvas closure when the lookup result flips.
    let segmentsVersion: Int
    let onTap: (String, Int, Int) -> Void
    let onLongPress: (String, Int, Int) -> Void
    let onMarkRowDone: (Int) -> Void

    /// Reserved left-gutter width for rect row-number labels. Mirrors Kotlin
    /// `RECT_ROW_LABEL_GUTTER_PX` in ChartViewerScreen.kt — update in lock-step.
    private let rectRowLabelGutter: CGFloat = 28

    /// Half-width of the polar ring-label hit rectangle around the 12 o'clock
    /// diameter. Mirrors Kotlin `POLAR_RING_LABEL_HALF_W_PX`.
    private let polarRingLabelHalfW: CGFloat = 16

    @State private var scale: CGFloat = 1.0
    @State private var lastScale: CGFloat = 1.0
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero
    @State private var canvasSize: CGSize = .zero
    /// Flag set briefly when a long-press fires so the subsequent `onTapGesture`
    /// callback can short-circuit. Without this, SwiftUI fires both gesture
    /// recognizers on long-press release and the tap handler immediately
    /// cycles the now-done cell back to todo.
    @State private var longPressActive = false
    @StateObject private var pathCache = PathCommandCache()

    private let minScale: CGFloat = 0.5
    private let maxScale: CGFloat = 8.0

    var body: some View {
        GeometryReader { proxy in
            Canvas { context, size in
                if let rect = chart.extents as? ChartExtentsRect {
                    if rect.maxX < rect.minX || rect.maxY < rect.minY { return }
                    draw(into: &context, size: size, rect: rect)
                } else if let polar = chart.extents as? ChartExtentsPolar {
                    let rings = Int(polar.rings)
                    let perRing = polar.stitchesPerRing.map { Int(truncating: $0) }
                    if rings <= 0 || perRing.count < rings || perRing.contains(where: { $0 <= 0 }) {
                        return
                    }
                    drawPolar(into: &context, size: size, polar: polar, ringsCount: rings, stitchesPerRing: perRing)
                }
            }
            .scaleEffect(scale)
            .offset(offset)
            .gesture(
                SimultaneousGesture(
                    MagnificationGesture()
                        .onChanged { value in
                            let proposed = lastScale * value
                            scale = min(max(proposed, minScale), maxScale)
                        }
                        .onEnded { _ in lastScale = scale },
                    DragGesture()
                        .onChanged { value in
                            offset = CGSize(
                                width: lastOffset.width + value.translation.width,
                                height: lastOffset.height + value.translation.height
                            )
                        }
                        .onEnded { _ in lastOffset = offset }
                )
            )
            .onAppear { canvasSize = proxy.size }
            .onChange(of: proxy.size) { _, newSize in canvasSize = newSize }
            .background(Color(.systemBackground))
            .clipped()
            // Phase 34 gestures — coexist with pinch/drag via .simultaneousGesture.
            // Phase 35.1d wired polar through `resolveAnyHit` so both rect and polar
            // taps dispatch to the ViewModel.
            .contentShape(Rectangle())
            .simultaneousGesture(
                LongPressGesture(minimumDuration: 0.5)
                    .sequenced(before: DragGesture(minimumDistance: 0))
                    .onEnded { value in
                        if case .second(true, let drag) = value, let d = drag {
                            longPressActive = true
                            handleLongPress(at: d.startLocation)
                            // Defer the clear so the subsequent onTapGesture that
                            // SwiftUI fires on finger-lift is suppressed.
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                                longPressActive = false
                            }
                        }
                    }
            )
            .onTapGesture { location in
                guard !longPressActive else { return }
                handleTap(at: location)
            }
        }
    }

    /// Converts a gesture-space tap location into the Canvas's unscaled/unpanned
    /// coordinate space. `.scaleEffect` + `.offset` are render-only transforms;
    /// the gesture recognizer delivers coordinates in the view's layout space.
    /// Without inverting the transform, hit-tests at any zoom ≠ 1.0 or non-zero
    /// pan land on the wrong cell.
    private func toCanvasSpace(_ location: CGPoint) -> (point: CGPoint, size: CGSize) {
        let safeScale = scale == 0 ? 1 : scale
        let adjusted = CGPoint(
            x: (location.x - offset.width) / safeScale,
            y: (location.y - offset.height) / safeScale,
        )
        let adjustedSize = CGSize(
            width: canvasSize.width / safeScale,
            height: canvasSize.height / safeScale,
        )
        return (adjusted, adjustedSize)
    }

    private func handleTap(at location: CGPoint) {
        let (p, s) = toCanvasSpace(location)
        guard let hit = resolveAnyHit(location: p, size: s) else { return }
        onTap(hit.layerId, hit.x, hit.y)
    }

    private func handleLongPress(at location: CGPoint) {
        let (p, s) = toCanvasSpace(location)
        // Phase 35.2d: label hits take priority over cell hits so long-press on
        // the row/ring number dispatches MarkRowDone.
        if let row = resolveAnyLabelHit(location: p, size: s) {
            onMarkRowDone(row)
            return
        }
        guard let hit = resolveAnyHit(location: p, size: s) else { return }
        onLongPress(hit.layerId, hit.x, hit.y)
    }

    /// Dispatches to the rect row-label or polar ring-label hit-test. Returns
    /// the chart y-coordinate (rect) or ring index (polar) — both map to
    /// `ChartCell.y` without reinterpretation per ADR-010 §4.
    private func resolveAnyLabelHit(location: CGPoint, size: CGSize) -> Int? {
        if let rect = chart.extents as? ChartExtentsRect {
            return resolveRowLabelHit(location: location, size: size, rect: rect)
        } else if let polar = chart.extents as? ChartExtentsPolar {
            return resolvePolarRingLabelHit(location: location, size: size, polar: polar)
        }
        return nil
    }

    /// Hit-test the reserved left gutter for rect row-number labels.
    /// Returns the chart y-coordinate (row, offset by `rect.minY`) or nil.
    private func resolveRowLabelHit(location: CGPoint, size: CGSize, rect: ChartExtentsRect) -> Int? {
        let gridWidth = Int(rect.maxX - rect.minX + 1)
        let gridHeight = Int(rect.maxY - rect.minY + 1)
        let availableW = max(1, size.width - rectRowLabelGutter)
        let cellSize = max(
            1,
            min(availableW / CGFloat(gridWidth), size.height / CGFloat(gridHeight))
        )
        let drawW = cellSize * CGFloat(gridWidth)
        let drawH = cellSize * CGFloat(gridHeight)
        let originX = rectRowLabelGutter + (availableW - drawW) / 2
        let originY = (size.height - drawH) / 2
        if location.x < 0 || location.x >= originX { return nil }
        if location.y < originY { return nil }
        if location.y >= originY + drawH { return nil }
        let rowFromTop = Int(floor((location.y - originY) / cellSize))
        let gy = gridHeight - 1 - rowFromTop
        if gy < 0 || gy >= gridHeight { return nil }
        return Int(rect.minY) + gy
    }

    /// Hit-test the polar ring-label column along the 12 o'clock diameter.
    /// Returns the ring index (0-based, matches `ChartCell.y` storage) or nil.
    private func resolvePolarRingLabelHit(location: CGPoint, size: CGSize, polar: ChartExtentsPolar) -> Int? {
        let ringsCount = Int(polar.rings)
        if ringsCount <= 0 { return nil }
        let layout = polarLayout(canvasSize: size, ringsCount: ringsCount)
        if location.x < layout.cx - polarRingLabelHalfW { return nil }
        if location.x >= layout.cx + polarRingLabelHalfW { return nil }
        if location.y >= layout.cy { return nil }
        let dy = Double(layout.cy - location.y)
        let innerR = Double(layout.innerRadius)
        let ringThickness = Double(layout.ringThickness)
        let outerR = innerR + Double(ringsCount) * ringThickness
        if dy < innerR || dy >= outerR { return nil }
        let ring = Int(floor((dy - innerR) / ringThickness))
        return min(max(ring, 0), ringsCount - 1)
    }

    /// Dispatches to the rect or polar hit-test depending on the chart extents.
    /// Matches the Kotlin `resolveHit` / `resolvePolarHit` split.
    private func resolveAnyHit(location: CGPoint, size: CGSize) -> HitResult? {
        if let rect = chart.extents as? ChartExtentsRect {
            return resolveHit(location: location, size: size, rect: rect)
        } else if let polar = chart.extents as? ChartExtentsPolar {
            return resolvePolarHit(location: location, size: size, polar: polar)
        }
        return nil
    }

    private struct HitResult { let layerId: String; let x: Int; let y: Int }

    private func resolveHit(location: CGPoint, size: CGSize, rect: ChartExtentsRect) -> HitResult? {
        let gridWidth = Int(rect.maxX - rect.minX + 1)
        let gridHeight = Int(rect.maxY - rect.minY + 1)
        // Reserve the left gutter for row labels (Phase 35.2d). Mirrors the
        // Kotlin `computeViewerLayout` math to keep hit-test and draw in lockstep.
        let availableW = max(1, size.width - rectRowLabelGutter)
        let cellSize = max(
            1,
            min(availableW / CGFloat(gridWidth), size.height / CGFloat(gridHeight))
        )
        let drawW = cellSize * CGFloat(gridWidth)
        let drawH = cellSize * CGFloat(gridHeight)
        let originX = rectRowLabelGutter + (availableW - drawW) / 2
        let originY = (size.height - drawH) / 2

        // GridHitTest.hitTest semantics — mirror Kotlin for consistency.
        let localX = location.x - originX
        let localY = location.y - originY
        if localX < 0 || localY < 0 { return nil }
        if localX >= cellSize * CGFloat(gridWidth) { return nil }
        if localY >= cellSize * CGFloat(gridHeight) { return nil }
        let gx = Int(floor(localX / cellSize))
        let rowFromTop = Int(floor(localY / cellSize))
        let gy = gridHeight - 1 - rowFromTop
        let cellX = Int(rect.minX) + gx
        let cellY = Int(rect.minY) + gy

        // Top-most visible layer that has a drawn cell at (cellX, cellY) wins.
        for layer in chart.layers.reversed() {
            // Phase 35.2f: locked layers are silently skipped from tap routing
            // per ADR-011 §5 addendum decision 1(c). Overlay still paints (the
            // separate draw loop iterates `chart.layers` directly).
            if !layer.visible || hiddenLayerIds.contains(layer.id) || layer.locked { continue }
            if layer.cells.contains(where: { Int($0.x) == cellX && Int($0.y) == cellY }) {
                return HitResult(layerId: layer.id, x: cellX, y: cellY)
            }
        }
        return nil
    }

    /// Polar analog of `resolveHit`. Mirrors Kotlin `GridHitTest.hitTestPolar`:
    /// 12-o'clock-CW-positive convention, inner-hole tap returns nil, outer
    /// boundary exclusive. `cell.x = stitch`, `cell.y = ring` — same storage
    /// convention the Phase 35.1b/c overlay + glyph passes use.
    private func resolvePolarHit(location: CGPoint, size: CGSize, polar: ChartExtentsPolar) -> HitResult? {
        let ringsCount = Int(polar.rings)
        if ringsCount <= 0 { return nil }
        let perRing = polar.stitchesPerRing.map { Int(truncating: $0) }
        if perRing.count < ringsCount || perRing.contains(where: { $0 <= 0 }) { return nil }

        let layout = polarLayout(canvasSize: size, ringsCount: ringsCount)
        if layout.ringThickness <= 0 || layout.innerRadius < 0 { return nil }

        let dx = Double(location.x - layout.cx)
        let dy = Double(location.y - layout.cy)
        let radius = (dx * dx + dy * dy).squareRoot()
        if radius < Double(layout.innerRadius) { return nil }
        let outerRadius = Double(layout.innerRadius) + Double(ringsCount) * Double(layout.ringThickness)
        if radius >= outerRadius { return nil }

        let ringRaw = Int(floor((radius - Double(layout.innerRadius)) / Double(layout.ringThickness)))
        let ring = min(max(ringRaw, 0), ringsCount - 1) // defensive against fp boundary rounding
        let stitchesInRing = perRing[ring]
        if stitchesInRing <= 0 { return nil }

        // Screen atan2 runs CW from +x (3 o'clock); adding π/2 shifts origin to
        // 12 o'clock, mod 2π normalizes.
        let twoPi = 2.0 * Double.pi
        let rawTheta = atan2(dy, dx) + Double.pi / 2
        // Mirror Kotlin's `((rawTheta % 2π) + 2π) % 2π` for positive normalization.
        let theta = (rawTheta.truncatingRemainder(dividingBy: twoPi) + twoPi)
            .truncatingRemainder(dividingBy: twoPi)

        let sweep = twoPi / Double(stitchesInRing)
        let stitchRaw = Int(floor(theta / sweep))
        let stitch = min(max(stitchRaw, 0), stitchesInRing - 1)

        for layer in chart.layers.reversed() {
            // Phase 35.2f: locked layers are silently skipped from tap routing
            // per ADR-011 §5 addendum decision 1(c).
            if !layer.visible || hiddenLayerIds.contains(layer.id) || layer.locked { continue }
            if layer.cells.contains(where: { Int($0.x) == stitch && Int($0.y) == ring }) {
                return HitResult(layerId: layer.id, x: stitch, y: ring)
            }
        }
        return nil
    }

    private func draw(into context: inout GraphicsContext, size: CGSize, rect: ChartExtentsRect) {
        let gridWidth = Int(rect.maxX - rect.minX + 1)
        let gridHeight = Int(rect.maxY - rect.minY + 1)
        // Reserve the left gutter (Phase 35.2d). Shared with resolveHit so the
        // tap coords and drawn cells agree.
        let availableW = max(1, size.width - rectRowLabelGutter)
        let cellSize = max(
            1,
            min(availableW / CGFloat(gridWidth), size.height / CGFloat(gridHeight))
        )
        let drawW = cellSize * CGFloat(gridWidth)
        let drawH = cellSize * CGFloat(gridHeight)
        let originX = rectRowLabelGutter + (availableW - drawW) / 2
        let originY = (size.height - drawH) / 2

        // Grid background — Phase 36.4.1b shared helper.
        let gridColor = GraphicsContext.Shading.color(.gray.opacity(0.25))
        drawRectGrid(
            into: &context,
            gridWidth: gridWidth,
            gridHeight: gridHeight,
            cellSize: cellSize,
            originX: originX,
            originY: originY,
            color: gridColor
        )

        let strokeWidth = max(1, cellSize * 0.06)
        let symbolColor = GraphicsContext.Shading.color(.primary)
        let unknownBg = GraphicsContext.Shading.color(.red.opacity(0.2))
        // Per PRD AC-1.1 — done → filled 20% primary (SwiftUI has no true onSurface);
        // wip → 2pt outline in accent.
        let segmentDoneShading = GraphicsContext.Shading.color(.primary.opacity(0.2))
        let segmentWipShading = GraphicsContext.Shading.color(.accentColor)

        for layer in chart.layers {
            if !layer.visible || hiddenLayerIds.contains(layer.id) { continue }
            for cell in layer.cells {
                let bounds = cellRect(
                    cell: cell,
                    rect: rect,
                    gridHeight: gridHeight,
                    cellSize: cellSize,
                    originX: originX,
                    originY: originY
                )

                // Paint overlay under the glyph (AC-1.2). The rect draw path is
                // only reached for rect extents; polar routes through `drawPolar`
                // which has its own segment overlay. No `!isPolar` guard needed here.
                if let s = segmentLookup(layer.id, Int(cell.x), Int(cell.y)) {
                    if s == .done {
                        context.fill(Path(bounds), with: segmentDoneShading)
                    } else if s == .wip {
                        context.stroke(Path(bounds), with: segmentWipShading, lineWidth: 2)
                    }
                }
                _ = segmentsVersion // capture to drive re-evaluation when map mutates

                guard let def = catalog.get(id: cell.symbolId) else {
                    drawUnknownGlyph(into: &context, bounds: bounds, fill: unknownBg)
                    continue
                }

                drawSymbolPath(
                    into: &context,
                    def: def,
                    bounds: bounds,
                    rotation: Int(cell.rotation),
                    color: symbolColor,
                    lineWidth: strokeWidth,
                    cache: pathCache
                )
                drawParameterSlots(
                    into: &context,
                    def: def,
                    cell: cell,
                    bounds: bounds,
                    cellSize: cellSize
                )
            }
        }

        // Phase 35.2d: paint row-number labels in the left gutter. Labels are
        // 1-indexed upward from the bottom row per chart y-up convention
        // (docs/en/chart-coordinates.md). Locale-independent digits — no i18n.
        let labelFontPx = max(8, cellSize * 0.35)
        let gutterCenterX = originX - rectRowLabelGutter / 2
        for gy in 0..<gridHeight {
            let rowCenterY = originY + CGFloat(gridHeight - gy) * cellSize - cellSize / 2
            let label = Text(verbatim: "\(gy + 1)")
                .font(.system(size: labelFontPx))
                .foregroundColor(.secondary)
            context.draw(label, at: CGPoint(x: gutterCenterX, y: rowCenterY), anchor: .center)
        }
    }

    private func drawParameterSlots(
        into context: inout GraphicsContext,
        def: SymbolDefinition,
        cell: ChartCell,
        bounds: CGRect,
        cellSize: CGFloat
    ) {
        guard !def.parameterSlots.isEmpty else { return }
        let cellBounds = CellBounds(
            left: Double(bounds.minX),
            top: Double(bounds.minY),
            right: Double(bounds.maxX),
            bottom: Double(bounds.maxY)
        )
        let fontSize = max(8, cellSize * 0.35)
        for slot in def.parameterSlots {
            let value: String
            if let provided = cell.symbolParameters[slot.key] {
                value = provided
            } else if let fallback = slot.defaultValue {
                value = fallback
            } else {
                continue
            }
            let anchor = SymbolRenderTransform.shared.mapPoint(
                unitX: slot.x,
                unitY: slot.y,
                bounds: cellBounds,
                rotation: Int32(cell.rotation)
            )
            let text = Text(verbatim: value).font(.system(size: fontSize)).foregroundColor(.accentColor)
            context.draw(text, at: CGPoint(x: anchor.x, y: anchor.y), anchor: .center)
        }
    }

    // MARK: - Polar rendering (ADR-011 §2)

    private func drawPolar(
        into context: inout GraphicsContext,
        size: CGSize,
        polar: ChartExtentsPolar,
        ringsCount: Int,
        stitchesPerRing: [Int]
    ) {
        let layout = polarLayout(canvasSize: size, ringsCount: ringsCount)
        let gridColor = GraphicsContext.Shading.color(.gray.opacity(0.3))
        let segmentDoneShading = GraphicsContext.Shading.color(.primary.opacity(0.2))
        let segmentWipShading = GraphicsContext.Shading.color(.accentColor)

        // Ring boundaries (includes innermost + outermost).
        for i in 0...ringsCount {
            let r = layout.innerRadius + CGFloat(i) * layout.ringThickness
            let rect = CGRect(
                x: layout.cx - r,
                y: layout.cy - r,
                width: r * 2,
                height: r * 2
            )
            context.stroke(Path(ellipseIn: rect), with: gridColor, lineWidth: 1)
        }

        // Radial spokes — outermost ring stitch count, per-ring spokes is a
        // Phase 35.x polish item per ADR-011 §2.
        if let outerStitches = stitchesPerRing.last, outerStitches > 0 {
            let innerR = layout.innerRadius
            let outerR = layout.innerRadius + CGFloat(ringsCount) * layout.ringThickness
            let sweep = 2.0 * Double.pi / Double(outerStitches)
            for s in 0..<outerStitches {
                // 12-o'clock-CW convention → screen cartesian: subtract π/2.
                let screenAngle = Double(s) * sweep - Double.pi / 2
                let dx = cos(screenAngle)
                let dy = sin(screenAngle)
                var path = Path()
                path.move(to: CGPoint(
                    x: layout.cx + innerR * CGFloat(dx),
                    y: layout.cy + innerR * CGFloat(dy)
                ))
                path.addLine(to: CGPoint(
                    x: layout.cx + outerR * CGFloat(dx),
                    y: layout.cy + outerR * CGFloat(dy)
                ))
                context.stroke(path, with: gridColor, lineWidth: 1)
            }
        }

        // Segment overlay — paint done/wip wedges. Out-of-range cells silently
        // skip, matching the rect renderer's defensive clipping.
        for layer in chart.layers {
            if !layer.visible || hiddenLayerIds.contains(layer.id) { continue }
            for cell in layer.cells {
                let ring = Int(cell.y)
                let stitch = Int(cell.x)
                if ring < 0 || ring >= ringsCount { continue }
                if ring >= stitchesPerRing.count { continue }
                let stitchesInRing = stitchesPerRing[ring]
                if stitch < 0 || stitch >= stitchesInRing { continue }
                guard let state = segmentLookup(layer.id, stitch, ring) else { continue }
                let path = polarWedgePath(
                    stitch: stitch,
                    ring: ring,
                    stitchesInRing: stitchesInRing,
                    layout: layout
                )
                if state == .done {
                    context.fill(path, with: segmentDoneShading)
                } else if state == .wip {
                    context.stroke(path, with: segmentWipShading, lineWidth: 2)
                }
            }
        }
        _ = segmentsVersion // force re-evaluation on segment map mutation

        // Glyph pass — paints on top of the overlay per ADR-011 §2 (matches rect
        // AC-1.2 "overlay under glyph"). Each cell is the largest axis-aligned
        // square that fits inside the wedge at its mid-radius, then rotated CW
        // by the wedge's angular center so the glyph's local "up" points
        // radially outward. `cell.rotation` (author's discrete 0/90/180/270)
        // composes on top via `SymbolRenderTransform.mapCommand`.
        let symbolColor = GraphicsContext.Shading.color(.primary)
        let unknownBg = GraphicsContext.Shading.color(.red.opacity(0.2))
        for layer in chart.layers {
            if !layer.visible || hiddenLayerIds.contains(layer.id) { continue }
            for cell in layer.cells {
                let ring = Int(cell.y)
                let stitch = Int(cell.x)
                if ring < 0 || ring >= ringsCount { continue }
                if ring >= stitchesPerRing.count { continue }
                let stitchesInRing = stitchesPerRing[ring]
                if stitch < 0 || stitch >= stitchesInRing { continue }

                // Polar cell geometry — Phase 36.4.1b lifted these into
                // ChartRenderingKit so the three consumers (Viewer, Diff,
                // Thumbnail) share the lock-step formulas with Kotlin
                // `PolarCellLayout.cellCenter` / `cellRadialUpRotation`.
                let side = polarCellInscribedSide(ring: ring, stitchesInRing: stitchesInRing, layout: layout)
                let half = CGFloat(side) / 2
                let center = polarCellCenter(stitch: stitch, ring: ring, stitchesInRing: stitchesInRing, layout: layout)
                let rotation = polarCellRotation(stitch: stitch, stitchesInRing: stitchesInRing)
                let bounds = CGRect(x: center.x - half, y: center.y - half, width: half * 2, height: half * 2)
                let strokeWidth = max(1, CGFloat(side) * 0.06)

                // Rotate a subcontext around the cell center so the glyph's local
                // "up" aligns with the radial direction. Positive radians rotate
                // CW on SwiftUI's y-down screen — matches our 12-o'clock-CW convention.
                var subcontext = context
                subcontext.translateBy(x: center.x, y: center.y)
                subcontext.rotate(by: .radians(rotation))
                subcontext.translateBy(x: -center.x, y: -center.y)

                guard let def = catalog.get(id: cell.symbolId) else {
                    drawUnknownGlyph(into: &subcontext, bounds: bounds, fill: unknownBg)
                    continue
                }
                drawSymbolPath(
                    into: &subcontext,
                    def: def,
                    bounds: bounds,
                    rotation: Int(cell.rotation),
                    color: symbolColor,
                    lineWidth: strokeWidth,
                    cache: pathCache
                )
            }
        }

        // Phase 35.2d: paint ring-number labels along the 12 o'clock diameter
        // at each ring's mid-radius. 1-indexed display; ring 0 renders as "1".
        let fontPx = max(10, layout.ringThickness * 0.4)
        for ring in 0..<ringsCount {
            let rCenter = layout.innerRadius + (CGFloat(ring) + 0.5) * layout.ringThickness
            let py = layout.cy - rCenter
            let label = Text(verbatim: "\(ring + 1)")
                .font(.system(size: fontPx))
                .foregroundColor(.secondary)
            context.draw(label, at: CGPoint(x: layout.cx, y: py), anchor: .center)
        }
    }
}
