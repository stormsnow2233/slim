import SwiftUI
import UIKit
import BackgroundTasks

@main
struct ShijuanApp: App {
    @StateObject private var state: AppState

    init() {
        let appState = AppState()
        _state = StateObject(wrappedValue: appState)
        BGTaskScheduler.shared.register(forTaskWithIdentifier: "com.storm.shijuan.ios.cache", using: nil) { task in
            Task { @MainActor in
                appState.handleCacheRefresh(task: task)
            }
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(state)
                .tint(state.theme == .miuix ? Color(red: 0.25, green: 0.55, blue: 0.95) : .accentColor)
                .onReceive(NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)) { _ in
                    state.scheduleCacheRefresh()
                }
        }
    }
}
