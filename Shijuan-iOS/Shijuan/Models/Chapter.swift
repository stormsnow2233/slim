import Foundation

struct Chapter: Identifiable, Hashable, Codable {
    let id: Int
    let title: String
    var url: URL? = nil
    var content: String = ""
    var isCached: Bool = false

    init(id: Int, title: String, url: URL? = nil, content: String = "", isCached: Bool = false) {
        self.id = id
        self.title = title
        self.url = url
        self.content = content
        self.isCached = isCached
    }
}
