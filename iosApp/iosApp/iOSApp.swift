import ComposeApp
import SwiftUI
import UserNotifications
import BackgroundTasks


@main
struct iOSApp: App {
    init() {
        SampleIosBootstrapKt.initializeSampleActionSchedulerIos()
        requestNotificationPermissions()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }

    private func requestNotificationPermissions() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in
            // No-op: Sample app only needs best-effort permissions for reminders.
        }
    }
}
