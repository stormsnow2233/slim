import Foundation

/// Minimal comic model mirroring Android Comic/ComicChapter/ComicPages for the
/// proxy-backed JM source.
struct Comic: Identifiable, Codable, Hashable {
    var id: String { url }
    var name: String
    var cover: String
    var author: String
    var intro: String
    var url: String
    var sourceName: String = "JM"

    init(name: String, cover: String = "", author: String = "", intro: String = "", url: String, sourceName: String = "JM") {
        self.name = name; self.cover = cover; self.author = author
        self.intro = intro; self.url = url; self.sourceName = sourceName
    }
}

struct ComicChapter: Identifiable, Codable, Hashable {
    var id: Int
    var name: String
    var url: String
}

struct ComicPages: Codable, Hashable {
    var title: String
    var images: [String]
    var referer: String
}

/// Proxy-backed comic catalog. Endpoints follow the JM proxy convention:
/// GET {base}/comics/search?q=, /comics/album?id=, /comics/chapter?id=.
enum ComicCatalog {
    static func search(keyword: String, base: URL) async throws -> [Comic] {
        guard var comps = URLComponents(url: base.appendingPathComponent("comics/search"), resolvingAgainstBaseURL: false) else {
            throw URLError(.badURL)
        }
        comps.queryItems = [URLQueryItem(name: "q", value: keyword)]
        guard let url = comps.url else { throw URLError(.badURL) }
        let data = try await data(from: url)
        return decodeList(data)
    }

    static func chapters(comic: Comic, base: URL) async throws -> [ComicChapter] {
        guard var comps = URLComponents(url: base.appendingPathComponent("comics/album"), resolvingAgainstBaseURL: false) else {
            throw URLError(.badURL)
        }
        comps.queryItems = [URLQueryItem(name: "id", value: comic.url)]
        guard let url = comps.url else { throw URLError(.badURL) }
        let data = try await data(from: url)
        struct Wrapper: Decodable { let chapters: [ComicChapter] }
        if let wrapper = try? JSONDecoder().decode(Wrapper.self, from: data) { return wrapper.chapters }
        let decoder = JSONDecoder()
        if let list = try? decoder.decode([ComicChapter].self, from: data) { return list }
        struct ChapterWrapper: Decodable { let list: [ComicChapter]?; let data: [ComicChapter]? }
        if let wrapper = try? decoder.decode(ChapterWrapper.self, from: data) {
            return wrapper.list ?? wrapper.data ?? []
        }
        return []
    }

    static func pages(chapter: ComicChapter, base: URL) async throws -> ComicPages {
        guard var comps = URLComponents(url: base.appendingPathComponent("comics/chapter"), resolvingAgainstBaseURL: false) else {
            throw URLError(.badURL)
        }
        comps.queryItems = [URLQueryItem(name: "id", value: chapter.url)]
        guard let url = comps.url else { throw URLError(.badURL) }
        let data = try await data(from: url)
        return try JSONDecoder().decode(ComicPages.self, from: data)
    }

    private static func decodeList(_ data: Data) -> [Comic] {
        let decoder = JSONDecoder()
        if let list = try? decoder.decode([Comic].self, from: data) { return list }
        struct Wrapper: Decodable { let results: [Comic]; let list: [Comic]?; let data: [Comic]? }
        if let wrapper = try? decoder.decode(Wrapper.self, from: data) {
            return wrapper.results.isEmpty ? (wrapper.list ?? wrapper.data ?? []) : wrapper.results
        }
        return []
    }

    private static func data(from url: URL) async throws -> Data {
        var req = URLRequest(url: url)
        req.timeoutInterval = 15
        let (payload, response) = try await URLSession.shared.data(for: req)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return payload
    }
}
