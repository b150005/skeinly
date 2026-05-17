import Shared
import SwiftUI

/// Pre-Phase-40 A33 — SwiftUI mirror of the Compose `OssLicensesScreen`.
/// Settings → About → Open Source Licenses.
///
/// Renders the `OssLibrary` list parsed from the build-time-generated
/// `aboutlibraries.json` — the SAME shared model + shared
/// `OssLibraryParser` the Compose screen uses, so the two platforms stay
/// in parity (no `aboutlibraries-compose-m3`, no Compose-on-iOS). The VM
/// auto-loads on `init`; three states: a spinner on first load, a
/// message + Retry on parse/resource failure, otherwise the list. A row
/// with a license URL opens it in the browser; the static
/// `docs/public/licenses/` page remains the reviewer-facing surface.
///
/// `ScopedViewModel` holder + `ViewModelFactory` + `KoinHelperKt` bridge
/// mirror `BlockedUsersListView`. The sealed Kotlin `data object` event
/// is dispatched as `OssLicensesEventRetry.shared` (same idiom as
/// `BlockedUsersEventClearError.shared`).
///
/// Library-derived strings are rendered with `Text(verbatim:)` so a
/// dependency name like `"Ktor"` is never treated as a localization key;
/// only the fixed UI strings use `LocalizedStringKey` lookups (every key
/// has en + ja values in `Localizable.xcstrings`, verified by
/// `verifyI18nKeys`).
struct OssLicensesView: View {
    @StateObject private var holder: ScopedViewModel<OssLicensesViewModel, OssLicensesState>
    @Environment(\.openURL) private var openURL

    private var viewModel: OssLicensesViewModel { holder.viewModel }

    init() {
        let vm = ViewModelFactory.ossLicensesViewModel()
        let wrapper = KoinHelperKt.wrapOssLicensesState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        let state = holder.state
        // Branch order matches the Compose `OssLicensesScreen`: `hasError`
        // before `isLoading` (a failed reload shows the error, not a
        // stale list); the terminal not-loading/no-error/empty case (a
        // should-never-happen broken bundled JSON) shows a message with
        // NO Retry — retrying a deterministic empty parse would loop.
        Group {
            if state.hasError {
                VStack(spacing: 12) {
                    Text("state_oss_licenses_load_failed")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    Button("action_retry") {
                        viewModel.onEvent(event: OssLicensesEventRetry.shared)
                    }
                }
                .padding(24)
                .accessibilityIdentifier("ossLicensesError")
            } else if state.isLoading {
                ProgressView()
                    .accessibilityIdentifier("ossLicensesLoading")
            } else if !state.libraries.isEmpty {
                List {
                    ForEach(state.libraries, id: \.uniqueId) { library in
                        row(for: library)
                    }
                }
                .accessibilityIdentifier("ossLicensesList")
            } else {
                // Unreachable with a correctly-generated bundle (the
                // committed JSON always has entries; a missing/broken
                // resource throws → hasError). The load-failed string is
                // intentionally reused rather than minting a
                // parity-checked `state_oss_licenses_empty` key for a
                // branch that cannot occur in production (YAGNI).
                Text("state_oss_licenses_load_failed")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(24)
                    .accessibilityIdentifier("ossLicensesEmpty")
            }
        }
        .navigationTitle(LocalizedStringKey("action_open_source_licenses"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("ossLicensesScreen")
    }

    @ViewBuilder
    private func row(for library: OssLibrary) -> some View {
        let content = VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: library.name)
                .font(.subheadline)
                .foregroundStyle(.primary)
            let meta = [library.version, library.license]
                .compactMap { $0 }
                .joined(separator: " • ")
            if !meta.isEmpty {
                Text(verbatim: meta)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)

        if let licenseUrl = library.licenseUrl, let url = URL(string: licenseUrl) {
            Button {
                openURL(url)
            } label: {
                content
            }
            .buttonStyle(.plain)
        } else {
            content
        }
    }
}
