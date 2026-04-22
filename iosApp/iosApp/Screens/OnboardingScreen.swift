import SwiftUI
import Shared

// Index-keyed copy. Order MUST match OnboardingViewModel.DEFAULT_PAGES in
// shared/src/commonMain/kotlin/io/github/b150005/knitnote/ui/onboarding/OnboardingViewModel.kt.
private let onboardingTitleKeys: [LocalizedStringKey] = [
    "title_onboarding_track",
    "title_onboarding_count",
    "title_onboarding_library",
]

private let onboardingBodyKeys: [LocalizedStringKey] = [
    "body_onboarding_track",
    "body_onboarding_count",
    "body_onboarding_library",
]

struct OnboardingScreen: View {
    let onComplete: () -> Void

    @StateObject private var holder: ScopedViewModel<OnboardingViewModel, OnboardingState>

    init(onComplete: @escaping () -> Void) {
        self.onComplete = onComplete
        let vm = ViewModelFactory.onboardingViewModel()
        let wrapper = KoinHelperKt.wrapOnboardingState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        let state = holder.state
        let viewModel = holder.viewModel

        VStack(spacing: 0) {
            // Skip button
            HStack {
                Spacer()
                Button("action_skip") {
                    viewModel.onEvent(event: OnboardingEventSkip.shared)
                }
                .foregroundStyle(.secondary)
                .padding(.trailing, 24)
                .padding(.top, 16)
                .accessibilityIdentifier("skipButton")
            }

            // Page content
            TabView(selection: Binding(
                get: { Int(state.currentPage) },
                set: { newPage in
                    viewModel.onEvent(event: OnboardingEventPageChanged(page: Int32(newPage)))
                }
            )) {
                ForEach(Array(state.pages.enumerated()), id: \.offset) { index, page in
                    OnboardingPageView(page: page, index: index)
                        .tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .always))
            .animation(.easeInOut, value: state.currentPage)

            // Action button
            let isLastPage = state.currentPage == Int32(state.pages.count) - 1

            Button {
                if isLastPage {
                    viewModel.onEvent(event: OnboardingEventComplete.shared)
                } else {
                    viewModel.onEvent(event: OnboardingEventNextPage.shared)
                }
            } label: {
                Text(isLastPage
                    ? LocalizedStringKey("action_get_started")
                    : LocalizedStringKey("action_next"))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .padding(.horizontal, 24)
            .padding(.bottom, 32)
            .accessibilityIdentifier(isLastPage ? "getStartedButton" : "nextButton")
        }
        .onChange(of: state.isCompleted) { _, isCompleted in
            if isCompleted {
                onComplete()
            }
        }
    }
}

private struct OnboardingPageView: View {
    let page: OnboardingPage
    let index: Int

    var body: some View {
        // Fallback to the first entry if a new page is added to DEFAULT_PAGES
        // without matching key entries. Build should never ship in that state;
        // adding a page requires updating onboardingTitleKeys + onboardingBodyKeys
        // + i18n resources in the same change.
        let titleKey = onboardingTitleKeys.indices.contains(index)
            ? onboardingTitleKeys[index]
            : onboardingTitleKeys[0]
        let bodyKey = onboardingBodyKeys.indices.contains(index)
            ? onboardingBodyKeys[index]
            : onboardingBodyKeys[0]
        VStack(spacing: 16) {
            Spacer()

            Image(systemName: mapIconName(page.iconName))
                .font(.system(size: 64))
                .foregroundStyle(.tint)

            Text(titleKey)
                .font(.title)
                .fontWeight(.bold)
                .multilineTextAlignment(.center)
                .padding(.top, 16)

            Text(bodyKey)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)

            Spacer()
            Spacer()
        }
        .padding(.horizontal, 24)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("onboardingPage\(index + 1)")
    }

    private func mapIconName(_ name: String) -> String {
        switch name {
        case "home": return "house"
        case "add_circle": return "plus.circle"
        case "favorite": return "heart.text.square"
        default: return "house"
        }
    }
}
