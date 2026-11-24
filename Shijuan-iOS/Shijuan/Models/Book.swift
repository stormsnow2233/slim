import Foundation

struct Book: Identifiable, Hashable, Codable {
    let id: UUID
    var title: String
    var author: String
    var progress: Double
    var isDownloaded: Bool
    var chapters: [Chapter] = []
    var sourceURL: URL? = nil
    var sourceName: String = ""

    init(id: UUID = UUID(), title: String, author: String, progress: Double = 0, isDownloaded: Bool = false, chapters: [Chapter] = [], sourceURL: URL? = nil, sourceName: String = "") {
        self.id = id
        self.title = title
        self.author = author
        self.progress = progress
        self.isDownloaded = isDownloaded
        self.chapters = chapters
        self.sourceURL = sourceURL
        self.sourceName = sourceName
    }
}
