import Foundation

struct SourceDescriptor: Identifiable, Codable, Hashable {
    let id: UUID
    var name: String
    var baseURL: URL
    var enabled: Bool
    var note: String

    init(name: String, baseURL: URL, enabled: Bool = true, note: String = "") {
        self.id = UUID()
        self.name = name
        self.baseURL = baseURL
        self.enabled = enabled
        self.note = note
    }
}

struct RemoteDetail: Codable {
    var title: String
    var author: String
    var intro: String
    var chapters: [ChapterSummary]

    private enum CodingKeys: String, CodingKey { case title, name, author, intro, description, chapters, toc, data }

    init(title: String, author: String, intro: String = "", chapters: [ChapterSummary] = []) {
        self.title = title
        self.author = author
        self.intro = intro
        self.chapters = chapters
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        // Some proxies wrap detail data in {data:{...}}; decode that first.
        if let nested = try? c.superDecoder(forKey: .data),
           let detail = try? RemoteDetail(from: nested) {
            self = detail
            return
        }
        title = (try? c.decode(String.self, forKey: .title)) ?? (try? c.decode(String.self, forKey: .name)) ?? "未命名"
        author = (try? c.decode(String.self, forKey: .author)) ?? "未知作者"
        intro = (try? c.decode(String.self, forKey: .intro)) ?? (try? c.decode(String.self, forKey: .description)) ?? ""
        chapters = (try? c.decode([ChapterSummary].self, forKey: .chapters))
            ?? (try? c.decode([ChapterSummary].self, forKey: .toc)) ?? []
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(title, forKey: .title)
        try c.encode(author, forKey: .author)
        try c.encode(intro, forKey: .intro)
        try c.encode(chapters, forKey: .chapters)
    }
}

struct ChapterSummary: Identifiable, Codable, Hashable {
    let id: Int
    let title: String
    let url: URL
    var isCached: Bool = false

    private enum CodingKeys: String, CodingKey { case id, index, title, name, url, link, isCached }

    init(id: Int, title: String, url: URL, isCached: Bool = false) {
        self.id = id
        self.title = title
        self.url = url
        self.isCached = isCached
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? c.decode(Int.self, forKey: .id)) ?? (try? c.decode(Int.self, forKey: .index)) ?? 0
        title = (try? c.decode(String.self, forKey: .title)) ?? (try? c.decode(String.self, forKey: .name)) ?? "第\(id + 1)章"
        let rawURL = (try? c.decode(String.self, forKey: .url)) ?? (try? c.decode(String.self, forKey: .link)) ?? ""
        guard let parsed = URL(string: rawURL), !rawURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw URLError(.badURL) }
        // Detail endpoints may return absolute URLs or relative paths such as
        // `/chapter/1`; RemoteCatalog resolves the latter against its proxy.
        url = parsed
        isCached = (try? c.decode(Bool.self, forKey: .isCached)) ?? false
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(title, forKey: .title)
        try c.encode(url.absoluteString, forKey: .url)
        try c.encode(isCached, forKey: .isCached)
    }
}
