import SwiftUI
import Shared

@main
struct iOSApp: App {
    @State private var appRoot = AppRootView()

    init() {
        KoinHelperKt.doInitKoin()
    }

    var body: some Scene {
        WindowGroup {
            appRoot
                .onOpenURL { url in
                    appRoot.handleDeepLink(url: url)
                }
        }
    }
}
