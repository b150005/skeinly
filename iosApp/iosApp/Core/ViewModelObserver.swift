import SwiftUI
import Shared

/// Generic observer that bridges a Kotlin `FlowWrapper<State>` to SwiftUI's `@Published`.
/// Usage: `@StateObject var observer = ViewModelObserver(wrapper: viewModel.stateWrapper)`
@MainActor
final class ViewModelObserver<State: AnyObject>: ObservableObject {
    @Published var state: State
    private var closeable: Closeable?

    init(wrapper: FlowWrapper<State>) {
        self.state = wrapper.currentValue
        self.closeable = wrapper.collect { [weak self] newState in
            Task { @MainActor in
                self?.state = newState
            }
        }
    }

    deinit {
        closeable?.close()
    }
}

/// Observer for one-shot event flows (e.g., navigation triggers, success signals).
@MainActor
final class EventFlowObserver<T: AnyObject>: ObservableObject {
    @Published var latestEvent: T?
    private var closeable: Closeable?

    init(wrapper: EventFlowWrapper<T>) {
        self.closeable = wrapper.collect { [weak self] event in
            Task { @MainActor in
                self?.latestEvent = event
            }
        }
    }

    func consume() {
        latestEvent = nil
    }

    deinit {
        closeable?.close()
    }
}
