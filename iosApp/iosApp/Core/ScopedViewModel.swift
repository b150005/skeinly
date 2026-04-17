import SwiftUI
import Shared

/// Owns a Kotlin `ViewModel` together with its observed `State` so the pair survives
/// SwiftUI struct re-initialization. Must be held as `@StateObject` by the consuming
/// view — SwiftUI evaluates the `@autoclosure` wrappedValue only once per View
/// identity, which is what keeps the Koin-resolved ViewModel stable.
///
/// Why this exists: Koin resolves ViewModels with `factory` scope, so calling
/// `ViewModelFactory.xxx()` returns a fresh instance on every call. When a View
/// struct's `init` is re-run (e.g. because a parent's `@StateObject` published),
/// a stored `let viewModel: VM` property is reassigned to a brand new instance
/// while any `@StateObject` observer that captured the original flow stays bound
/// to the first instance. Events dispatched through the latest `viewModel` then
/// update an orphan state flow and the UI stops reflecting user input.
@MainActor
final class ScopedViewModel<VM: AnyObject, State: AnyObject>: ObservableObject {
    let viewModel: VM
    @Published var state: State
    private var closeable: Closeable?

    init(viewModel: VM, wrapper: FlowWrapper<State>) {
        self.viewModel = viewModel
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

/// Project detail combines the main state flow with a separate progress notes flow,
/// both observed off the same ViewModel. Grouped into one holder so a single
/// `@StateObject` pins the ViewModel across re-inits.
@MainActor
final class ProjectDetailHolder: ObservableObject {
    let viewModel: ProjectDetailViewModel
    @Published var state: ProjectDetailState
    @Published var notes: [Shared.Progress]
    private var stateCloseable: Closeable?
    private var notesCloseable: Closeable?

    init(projectId: String) {
        let vm = ViewModelFactory.projectDetailViewModel(projectId: projectId)
        self.viewModel = vm
        let stateWrapper = KoinHelperKt.wrapProjectDetailState(flow: vm.state)
        self.state = stateWrapper.currentValue
        let notesWrapper = KoinHelperKt.wrapProgressNotesState(flow: vm.progressNotes)
        self.notes = (notesWrapper.currentValue as? [Shared.Progress]) ?? []

        self.stateCloseable = stateWrapper.collect { [weak self] newState in
            Task { @MainActor in
                self?.state = newState
            }
        }
        self.notesCloseable = notesWrapper.collect { [weak self] newNotes in
            Task { @MainActor in
                self?.notes = (newNotes as? [Shared.Progress]) ?? []
            }
        }
    }

    deinit {
        stateCloseable?.close()
        notesCloseable?.close()
    }
}
