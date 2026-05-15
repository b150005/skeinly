import SwiftUI
import Shared

/// Phase 41.4 (ADR-016 §5.2 §6 §41.4) — SwiftUI mirror of
/// `PackManagementScreen.kt`. Lists every catalog pack with per-row
/// status badge + Refresh action + total downloaded-disk-size header.
///
/// "Free up storage" + per-pack download/update buttons are deferred
/// to Phase 41.5+ — see [PackManagementViewModel] KDoc for rationale.
struct PackManagementScreen: View {
    @StateObject private var holder: ScopedViewModel<PackManagementViewModel, PackManagementState>
    @Binding private var path: NavigationPath
    @State private var showError = false

    init(path: Binding<NavigationPath>) {
        let vm = ViewModelFactory.packManagementViewModel()
        let wrapper = KoinHelperKt.wrapPackManagementState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
        _path = path
    }

    private var viewModel: PackManagementViewModel { holder.viewModel }

    var body: some View {
        let state = holder.state
        ZStack {
            if state.isLoading {
                ProgressView()
            } else if state.rows.isEmpty {
                ContentUnavailableView(
                    LocalizedStringKey("state_no_packs"),
                    systemImage: "square.stack.3d.up.slash",
                    description: Text(LocalizedStringKey("state_no_packs_body"))
                )
            } else {
                packList(state: state)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("packManagementScreen")
        .navigationTitle(LocalizedStringKey("title_pack_management"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    viewModel.onEvent(event: PackManagementEventRefresh.shared)
                } label: {
                    if state.isRefreshing {
                        ProgressView()
                    } else {
                        Image(systemName: "arrow.clockwise")
                    }
                }
                .disabled(state.isRefreshing)
                .accessibilityIdentifier("refreshPacksButton")
                .accessibilityLabel(LocalizedStringKey("action_refresh_packs"))
            }
        }
        .onChange(of: state.error != nil) { _, hasError in
            showError = hasError
        }
        .alert(LocalizedStringKey("title_error"), isPresented: $showError) {
            Button("action_ok") {
                viewModel.onEvent(event: PackManagementEventClearError.shared)
            }
        } message: {
            Text(state.error?.localizedString ?? "")
        }
    }

    @ViewBuilder
    private func packList(state: PackManagementState) -> some View {
        List {
            Section {
                ForEach(state.rows, id: \.packId) { row in
                    PackCardView(
                        row: row,
                        onUnlockWithPro: {
                            path.append(Route.paywall(trigger: PaywallTrigger.settings))
                        }
                    )
                }
            } header: {
                Text(
                    String(
                        format: NSLocalizedString("label_pack_total_size", comment: ""),
                        formatPackSize(bytes: Int64(state.totalDownloadedBytes))
                    )
                )
            }
        }
    }
}

private struct PackCardView: View {
    let row: PackRow
    let onUnlockWithPro: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(row.displayName)
                    .font(.headline)
                    .accessibilityIdentifier("packTitle_\(row.packId)")
                Spacer()
                tierBadge
            }
            // Kotlin's `description: String?` field collides with NSObject's
            // `description: String` in the ObjC bridge, so Kotlin/Native
            // renames it to `description_` on the Swift side. The renamed
            // accessor stays Optional<String> as the Kotlin source declared.
            if let desc = row.description_, !desc.isEmpty {
                Text(desc)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Text(metadataLine)
                .font(.caption)
                .foregroundStyle(.secondary)
            statusChip
            if row.status == PackStatus.locked {
                Text(LocalizedStringKey("body_pack_locked_inline"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Button(action: onUnlockWithPro) {
                    Label(
                        LocalizedStringKey("action_unlock_with_pro"),
                        systemImage: "lock.fill"
                    )
                }
                .accessibilityIdentifier("unlockWithProButton_\(row.packId)")
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("packCard_\(row.packId)")
    }

    private var metadataLine: String {
        // Format the line as "v3 · 12 symbols · 1.4 MB" — assemble from
        // localized fragments so JA / EN both read naturally with their
        // own counter / unit conventions.
        let version = String(
            format: NSLocalizedString("label_pack_version_x", comment: ""),
            row.serverVersion
        )
        let symbols = String(
            format: NSLocalizedString("label_pack_symbol_count", comment: ""),
            row.symbolCount
        )
        let size = formatPackSize(bytes: Int64(row.payloadSize))
        return "\(version) · \(symbols) · \(size)"
    }

    @ViewBuilder
    private var tierBadge: some View {
        switch row.tier {
        case .free:
            Text(LocalizedStringKey("label_pack_tier_free"))
                .font(.caption.weight(.semibold))
                .padding(.horizontal, 8)
                .padding(.vertical, 2)
                .background(Color(.tertiarySystemFill))
                .clipShape(Capsule())
                .accessibilityIdentifier("packTierBadgeFree")
        case .pro:
            Text(LocalizedStringKey("label_pack_tier_pro"))
                .font(.caption.weight(.semibold))
                .padding(.horizontal, 8)
                .padding(.vertical, 2)
                .background(Color.accentColor.opacity(0.18))
                .clipShape(Capsule())
                .accessibilityIdentifier("packTierBadgePro")
        default:
            EmptyView()
        }
    }

    @ViewBuilder
    private var statusChip: some View {
        // Kotlin/Native ObjC bridging quirk (CLAUDE.md AppRouter §): PascalCase
        // Kotlin enum entries lowercase entirely on the Swift side, so
        // `PackStatus.UpdateAvailable` surfaces as `.updateavailable` etc.
        let (key, suffix): (String, String) = {
            switch row.status {
            case .downloaded: return ("label_pack_status_downloaded", "Downloaded")
            case .updateavailable: return ("label_pack_status_update_available", "UpdateAvailable")
            case .notdownloaded: return ("label_pack_status_not_downloaded", "NotDownloaded")
            case .locked: return ("label_pack_status_locked", "Locked")
            default: return ("label_pack_status_not_downloaded", "NotDownloaded")
            }
        }()
        Text(LocalizedStringKey(key))
            .font(.caption.weight(.medium))
            .padding(.horizontal, 8)
            .padding(.vertical, 2)
            .background(Color(.systemFill))
            .clipShape(Capsule())
            .accessibilityIdentifier("packStatus\(suffix)_\(row.packId)")
    }
}

/// Formats a byte count into "X KB" / "X.Y MB" using the localized
/// label. Mirrors `formatPackSize` on the Compose side.
private func formatPackSize(bytes: Int64) -> String {
    let kbMbThreshold: Int64 = 1_048_576
    if bytes < kbMbThreshold {
        let kb = (bytes + 512) / 1024
        return String(
            format: NSLocalizedString("label_pack_size_kb", comment: ""),
            String(kb)
        )
    }
    let mbInt = bytes / kbMbThreshold
    let mbTenths = (bytes % kbMbThreshold) * 10 / kbMbThreshold
    let rendered = "\(mbInt).\(mbTenths)"
    return String(
        format: NSLocalizedString("label_pack_size_mb", comment: ""),
        rendered
    )
}
