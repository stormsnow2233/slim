import Foundation

struct Book: Identifiable, Hashable {
    let id: UUID
    var title: String
    var author: String
    var progress: Double
    var isDownloaded: Bool
    var chapters: [Chapter] = []

    init(id: UUID = UUID(), title: String, author: String, progress: Double = 0, isDownloaded: Bool = false) {
        self.id = id
        self.title = title
        self.author = author
        self.progress = progress
        self.isDownloaded = isDownloaded
    }
}
