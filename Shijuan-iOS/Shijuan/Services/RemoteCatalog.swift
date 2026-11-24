import Foundation

struct RemoteBook: Identifiable, Hashable, Codable {
    let id: UUID
    let title: String
    let author: String
    let url: URL
    let sourceName: String
    let coverURL: URL?
    let intro: String

    init(title: String, author: String, url: URL, sourceName: String, coverURL: URL? = nil, intro: String = "") {
        self.id = UUID()
        self.title = title
        self.author = author
        self.url = url
        self.sourceName = sourceName
        self.coverURL = coverURL
        self.intro = intro
    }

    private enum CodingKeys: String, CodingKey { case id, title, name, author, url, link, sourceName, source, coverURL, cover, intro, description }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? values.decode(UUID.self, forKey: .id)) ?? UUID()
        title = (try? values.decode(String.self, forKey: .title)) ?? (try? values.decode(String.self, forKey: .name)) ?? "未命名"
        author = (try? values.decode(String.self, forKey: .author)) ?? "未知作者"
        let rawURL = ((try? values.decode(String.self, forKey: .url)) ?? (try? values.decode(String.self, forKey: .link)) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        // Rule-based sources commonly return relative paths such as /book/123.
        // Keep them here; search() resolves them against the source base URL.
        guard !rawURL.isEmpty, let parsedURL = URL(string: rawURL) else { throw URLError(.badURL) }
        url = parsedURL
        sourceName = (try? values.decode(String.self, forKey: .sourceName)) ?? (try? values.decode(String.self, forKey: .source)) ?? "在线书源"
        let cover = (try? values.decode(String.self, forKey: .coverURL)) ?? (try? values.decode(String.self, forKey: .cover))
        coverURL = cover.flatMap(URL.init(string:))
        intro = (try? values.decode(String.self, forKey: .intro)) ?? (try? values.decode(String.self, forKey: .description)) ?? ""
    }

    func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(id, forKey: .id)
        try values.encode(title, forKey: .title)
        try values.encode(author, forKey: .author)
        try values.encode(url.absoluteString, forKey: .url)
        try values.encode(sourceName, forKey: .sourceName)
        try values.encodeIfPresent(coverURL?.absoluteString, forKey: .coverURL)
        try values.encode(intro, forKey: .intro)
    }
}

struct RemoteCatalog {
    let proxyBaseURL: URL

    func detail(for remote: RemoteBook) async throws -> RemoteDetail {
        var request = URLRequest(url: proxyBaseURL.appendingPathComponent("detail"))
        request.httpMethod = "POST"
        request.timeoutInterval = 15
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(["url": remote.url.absoluteString])
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw URLError(.badServerResponse) }
        return try JSONDecoder().decode(RemoteDetail.self, from: data)
    }

    func content(for chapter: ChapterSummary) async throws -> String {
        // Rule-based sources commonly return relative chapter links. Resolve
        // those against the configured proxy instead of issuing an invalid URL request.
        let chapterURL = chapter.url.scheme == nil
            ? URL(string: chapter.url.relativeString, relativeTo: proxyBaseURL)?.absoluteURL ?? chapter.url
            : chapter.url
        let (data, response) = try await URLSession.shared.data(from: chapterURL)
        let http = response as? HTTPURLResponse
        guard let http = http else { throw URLError(.badServerResponse) }
        guard (200..<300).contains(http.statusCode) else {
            if http.statusCode == 403 || http.statusCode == 503 {
                throw URLError(.httpTooManyRedirects) // caller maps to Cloudflare solve
            }
            throw URLError(.badServerResponse)
        }
        // Accept either an HTML/text response or a small JSON envelope used by
        // common reading proxies ({"content": "..."}, {"text": "..."}, {"body": "..."}).
        if let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            for key in ["content", "text", "body", "html"] {
                if let value = object[key] as? String, !value.isEmpty {
                    return Self.cleanContent(value)
                }
            }
        }
        let raw = String(decoding: data, as: UTF8.self)
        return Self.cleanContent(raw)
    }

    private static func cleanContent(_ raw: String) -> String {
        // Sources commonly return HTML fragments; keep readable text for the SwiftUI reader.
        var text = raw.replacingOccurrences(of: "<br\\s*/?>", with: "\\n", options: .regularExpression)
        text = text.replacingOccurrences(of: "<p\\b[^>]*>", with: "", options: .regularExpression)
        text = text.replacingOccurrences(of: "</div\\s*>", with: "\\n", options: .regularExpression)
        text = text.replacingOccurrences(of: "</p\\s*>", with: "\\n", options: .regularExpression)
        text = text.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
        text = text.replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    func search(keyword: String) async throws -> [RemoteBook] {
        guard !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return [] }
        var components = URLComponents(url: proxyBaseURL.appendingPathComponent("search"), resolvingAgainstBaseURL: false)
        components?.queryItems = [URLQueryItem(name: "q", value: keyword)]
        guard let url = components?.url else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.timeoutInterval = 15
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        let decoded: [RemoteBook]
        if let wrapped = try? JSONDecoder().decode(RemoteSearchResponse.self, from: data) {
            decoded = wrapped.results
        } else {
            decoded = try JSONDecoder().decode([RemoteBook].self, from: data)
        }
        // Rule-based sources often return paths such as `/book/123` instead
        // of absolute URLs. Resolve them before the result reaches SwiftUI.
        return decoded.map { item in
            let resolvedURL = item.url.scheme == nil
                ? URL(string: item.url.relativeString, relativeTo: proxyBaseURL)?.absoluteURL ?? item.url
                : item.url
            let resolvedCover = item.coverURL.flatMap { cover in
                cover.scheme == nil
                    ? URL(string: cover.relativeString, relativeTo: proxyBaseURL)?.absoluteURL
                    : cover
            }
            return RemoteBook(title: item.title, author: item.author, url: resolvedURL,
                              sourceName: item.sourceName, coverURL: resolvedCover, intro: item.intro)
        }
    }
}

private struct RemoteSearchResponse: Decodable {
    let results: [RemoteBook]
    private enum CodingKeys: String, CodingKey { case results, books, data }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        if let value = try? c.decode([RemoteBook].self, forKey: .results) { results = value }
        else if let value = try? c.decode([RemoteBook].self, forKey: .books) { results = value }
        else { results = try c.decode([RemoteBook].self, forKey: .data) }
    }
}
