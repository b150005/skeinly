import SwiftUI
import Shared

/// Phase 41.3b (ADR-016 §5.1) — SwiftUI mirror of `PaywallScreen.kt`
/// (Compose). Surfaces from Settings → "Subscribe to Pro" via the
/// AppRouter `Route.paywall(trigger:)` route as a `.sheet`.
///
/// UI parity with the Compose surface: header pitch + monthly/annual
/// package rows + 7-day trial disclosure + filled-prominent "Subscribe"
/// button + bordered "Restore Purchases" + ToS / Privacy links. Loading +
/// error are inline. The screen never throws — every error path surfaces
/// an inline label with a "Dismiss" affordance.
///
/// Result handling: success messages dispatch through the host's
/// `onPaywallResult` callback. The current Settings entry just pops the
/// sheet on success without surfacing a host-side toast (the paywall's
/// own success state is enough). Phase 41.4 will introduce a chart-editor
/// entry that needs a host toast — at that point the Settings host can
/// adopt the same surface for parity if desired.
struct PaywallScreen: View {
    @StateObject private var holder: ScopedViewModel<PaywallViewModel, PaywallState>
    @State private var navCloseable: Closeable?
    private let onDismiss: () -> Void
    private let onPaywallResult: (PaywallResultSwift) -> Void

    init(
        trigger: PaywallTrigger,
        onDismiss: @escaping () -> Void,
        onPaywallResult: @escaping (PaywallResultSwift) -> Void = { _ in }
    ) {
        let vm = ViewModelFactory.paywallViewModel(trigger: trigger)
        let wrapper = KoinHelperKt.wrapPaywallState(flow: vm.state)
        _holder = StateObject(
            wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper)
        )
        self.onDismiss = onDismiss
        self.onPaywallResult = onPaywallResult
    }

    private var viewModel: PaywallViewModel { holder.viewModel }

    var body: some View {
        let state = holder.state
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(LocalizedStringKey("title_paywall"))
                        .font(.title2.weight(.semibold))

                    Text(LocalizedStringKey("body_paywall_pitch"))
                        .font(.body)
                        .foregroundStyle(.secondary)

                    Divider()

                    if state.isLoading {
                        HStack {
                            Spacer()
                            ProgressView()
                            Spacer()
                        }
                        .padding(.vertical, 24)
                    } else if state.offering == nil {
                        Text(LocalizedStringKey("state_paywall_unavailable"))
                            .font(.body)
                            .foregroundStyle(.secondary)
                    } else if let offering = state.offering {
                        // Iterate `packages` in order so a future OTHER-period
                        // addition (lifetime, weekly) renders without a layout
                        // edit, matching the Compose surface.
                        ForEach(offering.packages, id: \.identifier) { pkg in
                            PackageRowView(
                                pkg: pkg,
                                selected: state.selectedPackageId == pkg.identifier
                            ) {
                                viewModel.onEvent(
                                    event: PaywallEventSelectPackage(packageId: pkg.identifier)
                                )
                            }
                        }

                        Text(LocalizedStringKey("body_paywall_trial_disclosure"))
                            .font(.footnote)
                            .foregroundStyle(.secondary)

                        Button {
                            if let pkgId = state.selectedPackageId {
                                viewModel.onEvent(
                                    event: PaywallEventConfirmPurchase(packageId: pkgId)
                                )
                            }
                        } label: {
                            HStack {
                                if state.isPurchasing {
                                    ProgressView()
                                        .padding(.trailing, 8)
                                    Text(LocalizedStringKey("state_purchase_in_progress"))
                                } else {
                                    Text(LocalizedStringKey("action_subscribe"))
                                }
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(
                            state.selectedPackageId == nil ||
                            state.isPurchasing ||
                            state.isRestoring
                        )
                        .accessibilityIdentifier("confirmPurchaseButton")

                        Button {
                            viewModel.onEvent(event: PaywallEventRestorePurchases.shared)
                        } label: {
                            HStack {
                                if state.isRestoring {
                                    ProgressView()
                                        .padding(.trailing, 8)
                                    Text(LocalizedStringKey("state_restoring"))
                                } else {
                                    Text(LocalizedStringKey("action_restore_purchase"))
                                }
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .disabled(state.isPurchasing || state.isRestoring)
                        .accessibilityIdentifier("restorePurchasesButton")
                    }

                    if let error = state.error {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(LocalizedStringKey("state_purchase_failed"))
                                .font(.subheadline.weight(.semibold))
                            Text(error)
                                .font(.footnote)
                            Button {
                                viewModel.onEvent(event: PaywallEventClearError.shared)
                            } label: {
                                Text(LocalizedStringKey("action_dismiss_paywall_error"))
                            }
                            .buttonStyle(.borderless)
                            .accessibilityIdentifier("dismissPaywallErrorButton")
                        }
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color(.systemRed).opacity(0.15))
                        .cornerRadius(8)
                        .accessibilityIdentifier("paywallErrorLabel")
                    }

                    Divider()

                    VStack(spacing: 0) {
                        Link(
                            destination: URL(
                                string: "https://b150005.github.io/skeinly/terms-of-service/"
                            )!
                        ) {
                            Text(LocalizedStringKey("action_terms_of_service"))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 8)
                        }
                        .accessibilityIdentifier("paywallTermsLink")

                        Link(
                            destination: URL(
                                string: "https://b150005.github.io/skeinly/privacy-policy/"
                            )!
                        ) {
                            Text(LocalizedStringKey("action_privacy_policy"))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 8)
                        }
                        .accessibilityIdentifier("paywallPrivacyLink")
                    }
                }
                .padding()
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("paywallScreen")
            .navigationTitle(LocalizedStringKey("title_paywall"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        viewModel.onEvent(event: PaywallEventDismiss.shared)
                    } label: {
                        Text(LocalizedStringKey("action_close_paywall"))
                    }
                    .accessibilityIdentifier("closePaywallButton")
                }
            }
        }
        .task {
            // Subscribe to nav events. Per the Phase 32.2 / 36.5 iOS
            // Closeable leak audit pattern, close any prior subscription
            // first; .task re-fires on view re-appearance.
            navCloseable?.close()
            let flow = KoinHelperKt.wrapPaywallNavEvents(flow: viewModel.navEvents)
            navCloseable = flow.collect { event in
                Task { @MainActor in
                    let result: PaywallResultSwift?
                    if event is PaywallNavEventPurchaseConfirmed {
                        result = .purchaseConfirmed
                    } else if event is PaywallNavEventRestoredWithPro {
                        result = .restoredWithPro
                    } else if event is PaywallNavEventRestoredEmpty {
                        result = .restoredEmpty
                    } else {
                        // PaywallNavEventDismissed — pop without a host
                        // result. The exhaustive set is enforced by Kotlin
                        // (sealed interface); this `else` is unreachable
                        // for any other variant.
                        result = nil
                    }
                    onDismiss()
                    if let result = result {
                        onPaywallResult(result)
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

/// Swift-side mirror of `PaywallResult` (Kotlin sealed interface). Kept in
/// Swift instead of bridged through the framework because there is no
/// host that consumes the discriminator yet — Phase 41.4 may surface
/// contextual toasts that need to disambiguate.
enum PaywallResultSwift {
    case purchaseConfirmed
    case restoredWithPro
    case restoredEmpty
}

private struct PackageRowView: View {
    let pkg: PaywallPackage
    let selected: Bool
    let onTap: () -> Void

    private var labelKey: String {
        switch pkg.period {
        case .monthly: return "action_subscribe_monthly"
        case .annual: return "action_subscribe_annual"
        // OTHER falls back to monthly form — matches Compose's behavior.
        // Future weekly / lifetime offers should add a key here.
        default: return "action_subscribe_monthly"
        }
    }

    private var testTagSuffix: String {
        switch pkg.period {
        case .monthly: return "Monthly"
        case .annual: return "Annual"
        default: return "Other"
        }
    }

    var body: some View {
        Button(action: onTap) {
            HStack {
                // Use String(format:) for the parametric label so the user-
                // visible string carries the localized template AND the
                // RevenueCat priceString. SwiftUI's LocalizedStringKey
                // initializer with String interpolation does NOT consume
                // the template; we must format manually.
                Text(
                    String(
                        format: NSLocalizedString(labelKey, comment: ""),
                        pkg.priceString
                    )
                )
                .font(.headline)
                Spacer()
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(selected ? Color.accentColor.opacity(0.18) : Color(.tertiarySystemFill))
            .cornerRadius(8)
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(selected ? Color.accentColor : Color.clear, lineWidth: 2)
            )
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("subscribe\(testTagSuffix)Button")
    }
}
