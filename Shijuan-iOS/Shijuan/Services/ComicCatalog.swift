import Foundation

struct Comic: Identifiable, Codable, Hashable {
    var id: String { url }
    var name: String
    var cover: String
    var author: String
    var intro: String
    var url: String
    var sourceName: String

    init(name: String, cover: String = "", author: String = "", intro: String = "",
         url: String, sourceName: String = "AniList") {
        self.name = name
        self.cover = cover
        self.author = author
        self.intro = intro
        self.url = url
        self.sourceName = sourceName
    }
}

enum AniListComicCatalog {
    private static let endpoint = URL(string: "https://graphql.anilist.co")!
    private static let query = """
    query ($page: Int, $perPage: Int, $search: String) {
      Page(page: $page, perPage: $perPage) {
        media(type: MANGA, isAdult: false, sort: POPULARITY_DESC, search: $search) {
          title { userPreferred romaji english native }
          coverImage { large }
          description(asHtml: false)
          siteUrl
        }
      }
    }
    """

    private struct Response: Decodable {
        let data: DataPayload?
        let errors: [APIError]?
    }

    private struct DataPayload: Decodable {
        let Page: PagePayload?
    }

    private struct PagePayload: Decodable {
        let media: [Media]?
    }

    private struct Media: Decodable {
        let title: Title
        let coverImage: CoverImage?
        let description: String?
        let siteUrl: String
    }

    private struct Title: Decodable {
        let userPreferred: String?
        let romaji: String?
        let english: String?
        let native: String?

        var displayName: String {
            [userPreferred, english, romaji, native]
                .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
                .first { !$0.isEmpty } ?? "未命名作品"
        }
    }

    private struct CoverImage: Decodable {
        let large: String?
    }

    private struct APIError: Decodable {
        let message: String
    }

    private enum CatalogError: LocalizedError {
        case badResponse(Int)
        case invalidPayload
        case api(String)

        var errorDescription: String? {
            switch self {
            case .badResponse(let status): return "AniList 请求失败（HTTP \(status)）"
            case .invalidPayload: return "AniList 返回的数据无法解析"
            case .api(let message): return "AniList：\(message)"
            }
        }
    }

    static func recommendations() async throws -> [Comic] {
        try await fetch(search: nil)
    }

    static func search(keyword: String) async throws -> [Comic] {
        try await fetch(search: keyword)
    }

    private static func fetch(search: String?) async throws -> [Comic] {
        let variables: [String: Any] = [
            "page": 1,
            "perPage": 24,
            "search": search.map { $0 as Any } ?? NSNull()
        ]
        guard let body = try? JSONSerialization.data(withJSONObject: [
            "query": query,
            "variables": variables
        ]) else {
            throw CatalogError.invalidPayload
        }

        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw CatalogError.invalidPayload }
        guard (200..<300).contains(http.statusCode) else { throw CatalogError.badResponse(http.statusCode) }

        let payload = try JSONDecoder().decode(Response.self, from: data)
        if let message = payload.errors?.first?.message { throw CatalogError.api(message) }
        guard let media = payload.data?.Page?.media else { throw CatalogError.invalidPayload }
        return media.map {
            Comic(name: $0.title.displayName,
                  cover: $0.coverImage?.large ?? "",
                  intro: $0.description ?? "",
                  url: $0.siteUrl)
        }
    }
}
