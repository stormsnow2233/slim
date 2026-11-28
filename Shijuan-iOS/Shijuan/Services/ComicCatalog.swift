import Foundation
import CommonCrypto

struct Comic: Identifiable, Codable, Hashable {
    var id: String { url }
    var name: String
    var cover: String
    var author: String
    var intro: String
    var url: String
    var sourceName: String
    var apiBase: String?

    init(name: String, cover: String = "", author: String = "", intro: String = "",
         url: String, sourceName: String = "JMcomic", apiBase: String? = nil) {
        self.name = name
        self.cover = cover
        self.author = author
        self.intro = intro
        self.url = url
        self.sourceName = sourceName
        self.apiBase = apiBase
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

enum ComicCatalog {
    private static let key = Data("880c64833265ad47a928afcf1b1220f5".utf8)
    private static let token = "1c6fa345eea2e10d5a30880ec2a7e0b3"
    private static let tokenParameter = "1700000000,2.0.20"
    private static let userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private static let imageFallback = "https://cdn-msp2.jmdanjonproxy.vip"

    private enum CatalogError: LocalizedError {
        case invalidID
        case badResponse(Int)
        case invalidPayload
        case noData

        var errorDescription: String? {
            switch self {
            case .invalidID: return "漫画编号无效"
            case .badResponse(let status): return "漫画源请求失败（HTTP \(status)）"
            case .invalidPayload: return "漫画源返回的数据无法解析"
            case .noData: return "漫画源没有返回内容"
            }
        }
    }

    static func search(keyword: String, base: URL) async throws -> [Comic] {
        var components = URLComponents(url: base.appendingPathComponent("search"), resolvingAgainstBaseURL: false)
        components?.queryItems = [
            URLQueryItem(name: "page", value: "1"),
            URLQueryItem(name: "o", value: "mr"),
            URLQueryItem(name: "search_query", value: keyword),
            URLQueryItem(name: "main_tag", value: "0")
        ]
        guard let url = components?.url else { throw URLError(.badURL) }
        let response = try await api(url: url, base: base)
        let list = (response.value as? [String: Any])?["content"] as? [[String: Any]] ?? []
        guard !list.isEmpty else { return [] }

        let imageHost = await imageHost(for: response.base)
        return list.compactMap { item in
            guard let id = string(item["id"]), !id.isEmpty,
                  let name = string(item["name"]), !name.isEmpty else { return nil }
            let author = string(item["author"]).flatMap { ["N/A", "null"].contains($0) ? nil : $0 } ?? ""
            return Comic(name: name,
                         cover: "\(imageHost)/media/albums/\(id)_3x4.jpg",
                         author: author,
                         intro: string(item["description"]) ?? "",
                         url: id,
                         apiBase: response.base.absoluteString)
        }
    }

    static func chapters(comic: Comic, base: URL) async throws -> [ComicChapter] {
        let aid = try numericID(comic.url)
        let response = try await api(path: "chapter", query: [URLQueryItem(name: "id", value: aid)], base: base)
        guard let object = response.value as? [String: Any] else { throw CatalogError.invalidPayload }
        let series = object["series"] as? [[String: Any]] ?? []
        if !series.isEmpty {
            return series.enumerated().compactMap { index, item in
                guard let id = string(item["id"]), let numericID = Int(id) else { return nil }
                let name = string(item["name"]) ?? "第 \(string(item["sort"]) ?? "\(index + 1)") 话"
                return ComicChapter(id: numericID, name: name, url: id)
            }
        }
        return [ComicChapter(id: aid, name: string(object["name"]) ?? "全一话", url: "\(aid)")]
    }

    static func pages(chapter: ComicChapter, base: URL) async throws -> ComicPages {
        let cid = try numericID(chapter.url)
        let response = try await api(path: "chapter", query: [URLQueryItem(name: "id", value: cid)], base: base)
        guard let object = response.value as? [String: Any] else { throw CatalogError.invalidPayload }
        let filenames = object["images"] as? [String] ?? []
        guard !filenames.isEmpty else { throw CatalogError.noData }
        let imageHost = await imageHost(for: response.base)
        return ComicPages(title: chapter.name,
                          images: filenames.map { "\(imageHost)/media/photos/\(cid)/\($0)" },
                          referer: response.base.absoluteString + "/")
    }

    private static func imageHost(for base: URL) async -> String {
        guard let settingURL = URL(string: "setting", relativeTo: base)?.absoluteURL,
              let response = try? await api(url: settingURL, base: base),
              let object = response.value as? [String: Any],
              let host = string(object["img_host"]), !host.isEmpty else {
            return imageFallback
        }
        return host.hasPrefix("http") ? host.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            : "https://\(host.trimmingCharacters(in: CharacterSet(charactersIn: "/")))"
    }

    private static func api(path: String, query: [URLQueryItem], base: URL) async throws -> APIResponse {
        var components = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false)
        components?.queryItems = query
        guard let url = components?.url else { throw URLError(.badURL) }
        return try await api(url: url, base: base)
    }

    private struct APIResponse {
        let value: Any
        let base: URL
    }

    private static func api(url: URL, base: URL) async throws -> APIResponse {
        var hosts = [base]
        for value in ["https://www.cdnhth.club", "https://18comic.vip"] {
            if let fallback = URL(string: value), !hosts.contains(fallback) { hosts.append(fallback) }
        }
        var lastError: Error = CatalogError.noData
        for host in hosts {
            let target: URL
            if host == base {
                target = url
            } else {
                guard var components = URLComponents(url: host.appendingPathComponent(url.lastPathComponent), resolvingAgainstBaseURL: false) else {
                    continue
                }
                components.query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.query
                guard let fallbackURL = components.url else { continue }
                target = fallbackURL
            }
            do {
                var request = URLRequest(url: target)
                request.timeoutInterval = 20
                request.setValue(token, forHTTPHeaderField: "token")
                request.setValue(tokenParameter, forHTTPHeaderField: "tokenparam")
                request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
                request.setValue(host.absoluteString + "/", forHTTPHeaderField: "Referer")
                request.setValue("1", forHTTPHeaderField: "X-JM")
                let (data, response) = try await URLSession.shared.data(for: request)
                guard let http = response as? HTTPURLResponse else { throw CatalogError.invalidPayload }
                guard (200..<300).contains(http.statusCode) else { throw CatalogError.badResponse(http.statusCode) }
                let value = try unwrap(data)
                return APIResponse(value: value, base: host)
            } catch {
                lastError = error
            }
        }
        throw lastError
    }

    private static func unwrap(_ data: Data) throws -> Any {
        let trimmed = String(decoding: data, as: UTF8.self)
        guard let start = trimmed.firstIndex(of: "{"),
              let jsonData = String(trimmed[start...]).data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any] else {
            throw CatalogError.invalidPayload
        }
        if let code = object["code"] as? Int, code != 200 { throw CatalogError.badResponse(code) }
        guard let payload = object["data"] else { return object }
        if let encrypted = payload as? String {
            if let decrypted = decrypt(encrypted),
               let bytes = decrypted.data(using: .utf8),
               let value = try? JSONSerialization.jsonObject(with: bytes) {
                return value
            }
            if let bytes = encrypted.data(using: .utf8),
               let value = try? JSONSerialization.jsonObject(with: bytes) {
                return value
            }
            throw CatalogError.invalidPayload
        }
        return payload
    }

    private static func decrypt(_ base64: String) -> String? {
        guard let input = Data(base64Encoded: base64, options: .ignoreUnknownCharacters), !input.isEmpty else { return nil }
        var output = Data(count: input.count + kCCBlockSizeAES128)
        var moved = 0
        let options = CCOptions(kCCOptionPKCS7Padding | kCCOptionECBMode)
        let status = key.withUnsafeBytes { keyBytes in
            input.withUnsafeBytes { inputBytes in
                output.withUnsafeMutableBytes { outputBytes in
                    CCCrypt(CCOperation(kCCDecrypt), CCAlgorithm(kCCAlgorithmAES), options,
                            keyBytes.baseAddress, key.count, nil,
                            inputBytes.baseAddress, input.count,
                            outputBytes.baseAddress, output.count, &moved)
                }
            }
        }
        guard status == kCCSuccess else { return nil }
        output.removeSubrange(moved..<output.count)
        return String(data: output, encoding: .utf8)
    }

    private static func numericID(_ raw: String) throws -> String {
        let digits = raw.filter(\.isNumber)
        guard !digits.isEmpty else { throw CatalogError.invalidID }
        return digits
    }

    private static func string(_ value: Any?) -> String? {
        if let value = value as? String { return value }
        if let value = value as? NSNumber { return value.stringValue }
        return nil
    }
}
