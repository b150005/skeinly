import SwiftUI
import Shared

/// Phase 26.6 (ADR-022 §6.5) — SwiftUI mirror of the Compose
/// `BiometricSettingsScreen`. Toggle ON/OFF + threshold picker
/// (1m / 5m / 15m / 1h). State binds to the shared
/// `BiometricSettingsViewModel`; events route through `onEvent`.
struct BiometricSettingsScreen: View {
    @StateObject private var holder: ScopedViewModel<BiometricSettingsViewModel, BiometricSettingsState>

    private var viewModel: BiometricSettingsViewModel { holder.viewModel }

    init() {
        let vm = ViewModelFactory.biometricSettingsViewModel()
        let wrapper = KoinHelperKt.wrapBiometricSettingsState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        let state = holder.state

        Form {
            Section {
                Toggle(
                    isOn: Binding(
                        get: { state.enabled },
                        set: { viewModel.onEvent(event: BiometricSettingsEventToggleEnabled(value: $0)) }
                    )
                ) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(LocalizedStringKey("title_biometric_settings"))
                            .font(.body)
                        Text(LocalizedStringKey("body_biometric_settings_explanation"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .disabled(!state.canToggle)
                .accessibilityIdentifier("biometricToggle")

                if let unavailableKey = unavailableMessageKey(for: state.availability) {
                    Text(unavailableKey)
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }

            // Threshold picker — only relevant when toggle is on AND OS available.
            if state.enabled && state.canToggle {
                Section {
                    ForEach(thresholdChoices, id: \.self) { choice in
                        thresholdRow(choice: choice, currentSelection: state.threshold)
                    }
                } header: {
                    Text(LocalizedStringKey("label_biometric_threshold_picker"))
                }
            }
        }
        .navigationTitle(LocalizedStringKey("title_biometric_settings"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("biometricSettingsScreen")
        // Phase 26.7 (Tech Debt carryover from Phase 26.6) — re-query OS
        // availability on every screen appearance so a user who walked
        // through OS Settings to enroll biometric / PIN sees the
        // updated state when they return. Mirrors the Compose
        // `LaunchedEffect(Unit)` on the same surface.
        .onAppear {
            viewModel.onEvent(event: BiometricSettingsEventRefreshAvailability.shared)
        }
    }

    private var thresholdChoices: [ThresholdChoice] {
        [
            ThresholdChoice.oneminute,
            ThresholdChoice.fiveminutes,
            ThresholdChoice.fifteenminutes,
            ThresholdChoice.onehour,
        ]
    }

    @ViewBuilder
    private func thresholdRow(
        choice: ThresholdChoice,
        currentSelection: ThresholdChoice
    ) -> some View {
        Button {
            viewModel.onEvent(
                event: BiometricSettingsEventSelectThreshold(choice: choice)
            )
        } label: {
            HStack {
                Text(thresholdLabelKey(for: choice))
                    .foregroundStyle(.primary)
                Spacer()
                if choice == currentSelection {
                    Image(systemName: "checkmark")
                        .foregroundStyle(.tint)
                }
            }
        }
        .accessibilityIdentifier("biometricThreshold_\(choice.name)")
    }

    private func thresholdLabelKey(for choice: ThresholdChoice) -> LocalizedStringKey {
        // Switch on Kotlin enum identity via Kotlin-generated equals(_:)
        // override on KotlinEnum. The `default:` arm collapses any future
        // ThresholdChoice variant added in Kotlin to the safe 1-hour
        // label rather than misrendering — same defensive pattern as
        // notificationStatusLabelKey in SettingsScreen.swift.
        switch choice {
        case ThresholdChoice.oneminute: return "value_biometric_threshold_1m"
        case ThresholdChoice.fiveminutes: return "value_biometric_threshold_5m"
        case ThresholdChoice.fifteenminutes: return "value_biometric_threshold_15m"
        case ThresholdChoice.onehour: return "value_biometric_threshold_1h"
        default: return "value_biometric_threshold_1h"
        }
    }

    private func unavailableMessageKey(
        for availability: BiometricAvailability
    ) -> LocalizedStringKey? {
        if availability == BiometricAvailability.notenrolled {
            return "state_biometric_unavailable_not_enrolled"
        } else if availability == BiometricAvailability.nohardware
            || availability == BiometricAvailability.lockout {
            return "state_biometric_unavailable_no_hw"
        } else {
            return nil
        }
    }
}
