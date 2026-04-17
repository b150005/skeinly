import SwiftUI
import Shared

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
                Button("Skip") {
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
                    OnboardingPageView(page: page)
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
                Text(isLastPage ? "Get Started" : "Next")
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

    var body: some View {
        VStack(spacing: 16) {
            Spacer()

            Image(systemName: mapIconName(page.iconName))
                .font(.system(size: 64))
                .foregroundStyle(.tint)

            Text(page.title)
                .font(.title)
                .fontWeight(.bold)
                .multilineTextAlignment(.center)
                .padding(.top, 16)

            Text(page.body)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)

            Spacer()
            Spacer()
        }
        .padding(.horizontal, 24)
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
