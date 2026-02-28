import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    init() {
        SampleIosBootstrapKt.initializeSampleActionSchedulerIos()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}