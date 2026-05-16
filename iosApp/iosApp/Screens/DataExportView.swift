import Shared
import SwiftUI

/// Pre-Phase-40 A20 Option B (docs/en/ops/data-export-sop.md §Scope
/// deferrals) — SwiftUI mirror of the Compose `DataExportScreen`.
/// Settings → Privacy → Export My Data.
///
/// Non-destructive GDPR Art. 20 / CCPA "right to know" surface (reads
/// only). Explains what is / isn't included, then a single Export
/// action: a `ProgressView` while in flight → on success the OS share
/// sheet is fired by the shared `DataExportViewModel` (via the platform
/// `DataExportSaver`) and a success card shows the record count; on
/// failure an error card shows a localized message and the user can
/// re-tap Export.
///
/// `ScopedViewModel` holder + `ViewModelFactory` + `KoinHelperKt`
/// bridge mirror `BlockedUsersListView`. Sealed Kotlin `result` is
/// pattern-matched via `as?`; the sealed `data object` events are
/// dispatched as `<Name>.shared` (same idiom as
/// `WipeDataConfirmPhraseView`).
///
/// Bare `Text("snake_case_key")` calls are intentional: SwiftUI
/// resolves a `String` literal as a `LocalizedStringKey`, and every
/// key here lives in `Localizable.xcstrings` with en + ja values
/// (verified by `verifyI18nKeys`). This matches the existing
/// `SettingsScreen.swift` / `BlockedUsersListView` convention — not an
/// accidental un-localized literal.
struct DataExportView: View {
    @StateObject private var holder: ScopedViewModel<DataExportViewModel, DataExportState>

    private var viewModel: DataExportViewModel { holder.viewModel }

    init() {
        let vm = ViewModelFactory.dataExportViewModel()
        let wrapper = KoinHelperKt.wrapDataExportState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        let state = holder.state
        Form {
            Section {
                Text("body_export_data_explanation")
                    .font(.callout)
            }
            Section {
                Text("body_export_data_not_included")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            if let success = state.result as? DataExportResultSuccess {
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("state_export_data_ready")
                            .font(.headline)
                        // Single %d-parameterized key (NSLocalizedString
                        // + String(format:)) so the translator controls
                        // the count's position — not an EN-word-order
                        // concatenation.
                        Text(
                            String(
                                format: NSLocalizedString(
                                    "label_export_data_record_count",
                                    comment: "Export record count"
                                ),
                                success.totalRows
                            )
                        )
                        .font(.subheadline)
                        Text("body_export_data_ready_detail")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        Button("action_close") {
                            viewModel.onEvent(event: DataExportEventDismissResult.shared)
                        }
                        .padding(.top, 4)
                    }
                    .accessibilityIdentifier("dataExportSuccessCard")
                }
            } else if let failure = state.result as? DataExportResultError {
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(failure.message.localizedString)
                            .font(.subheadline)
                            .foregroundStyle(.red)
                        Button("action_close") {
                            viewModel.onEvent(event: DataExportEventDismissResult.shared)
                        }
                        .padding(.top, 4)
                    }
                    .accessibilityIdentifier("dataExportErrorCard")
                }
            }

            Section {
                Button {
                    viewModel.onEvent(event: DataExportEventExport.shared)
                } label: {
                    if state.isExporting {
                        HStack {
                            ProgressView()
                                .padding(.trailing, 8)
                            Text("state_export_data_in_progress")
                        }
                    } else {
                        Text("action_export_data")
                    }
                }
                .disabled(state.isExporting)
                .accessibilityIdentifier("exportDataButton")
            }
        }
        .navigationTitle(LocalizedStringKey("title_export_data_screen"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("dataExportScreen")
    }
}
