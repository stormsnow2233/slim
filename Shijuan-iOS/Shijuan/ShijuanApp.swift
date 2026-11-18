import SwiftUI

@main
struct ShijuanApp: App {
    @StateObject private var state = AppState()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(state)
                .tint(state.theme == .miuix ? Color(red: 0.25, green: 0.55, blue: 0.95) : .accentColor)
        }
    }
}
