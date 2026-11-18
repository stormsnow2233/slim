import SwiftUI

@MainActor
final class AppState: ObservableObject {
    enum ThemeMode: String, CaseIterable, Identifiable {
        case classic = "经典"
        case miuix = "MiuiX"
        var id: Self { self }
    }

    @Published var theme: ThemeMode = .miuix
    @Published var books: [Book] = [
        Book(title: "从零开始的魔法书", author: "虎走かける", progress: 0.42, isDownloaded: true, chapters: (1...12).map { Chapter(id: $0, title: "第\($0)章", isCached: $0 <= 5) }),
        Book(title: "Re:从零开始的异世界生活", author: "长月达平", progress: 0.18, chapters: (1...8).map { Chapter(id: $0, title: "第\($0)章") })
    ]
    @Published var searchText = ""

    var filteredBooks: [Book] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return books }
        return books.filter { $0.title.localizedCaseInsensitiveContains(query) || $0.author.localizedCaseInsensitiveContains(query) }
    }
}
