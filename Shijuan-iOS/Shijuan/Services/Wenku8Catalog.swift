import Foundation
import CoreFoundation

enum Wenku8Catalog {
    static let sourceName = "轻小说文库"

    struct ChapterBody {
        let title: String
        let content: String
    }

    private struct Entry {
        let id: String
        let title: String
        let author: String
        let latestVolume: String
    }

    private static let entries: [Entry] = {
        guard let url = Bundle.main.url(forResource: "wenku8_index", withExtension: "tsv"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        return text.split(whereSeparator: { $0.isNewline }).compactMap { line in
            let fields = line.split(separator: "\t", omittingEmptySubsequences: false)
            guard fields.count >= 2 else { return nil }
            let id = String(fields[0]).trimmingCharacters(in: .whitespacesAndNewlines)
            let title = String(fields[1]).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !id.isEmpty, !title.isEmpty else { return nil }
            return Entry(id: id,
                         title: title,
                         author: fields.count > 2 ? String(fields[2]) : "",
                         latestVolume: fields.count > 3 ? String(fields[3]) : "")
        }
    }()

    static func search(keyword: String) -> [RemoteBook] {
        let query = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return [] }
        return entries.compactMap { entry -> (Entry, Int)? in
            let title = entry.title.localizedCaseInsensitiveCompare(query) == .orderedSame
            let titlePrefix = entry.title.lowercased().hasPrefix(query.lowercased())
            let titleContains = entry.title.localizedCaseInsensitiveContains(query)
            let authorContains = entry.author.localizedCaseInsensitiveContains(query)
            guard title || titlePrefix || titleContains || authorContains else { return nil }
            return (entry, title ? 4 : titlePrefix ? 3 : titleContains ? 2 : 1)
        }
        .sorted { $0.1 == $1.1 ? $0.0.title.localizedCompare($1.0.title) == .orderedAscending : $0.1 > $1.1 }
        .prefix(60)
        .compactMap { match in
            let entry = match.0
            guard let id = Int(entry.id),
                  let bookURL = URL(string: "https://www.wenku8.cc/book/\(id).htm") else { return nil }
            let group = id / 1000
            let cover = URL(string: "https://img.wenku8.com/image/\(group)/\(id)/\(id)s.jpg")
            return RemoteBook(title: entry.title,
                              author: entry.author.isEmpty ? "未知作者" : entry.author,
                              url: bookURL,
                              sourceName: sourceName,
                              coverURL: cover,
                              intro: entry.latestVolume.isEmpty ? "轻小说文库" : "最新：\(entry.latestVolume)")
        }
    }

    static func downloadChapters(bookID: Int) async throws -> [ChapterBody] {
        var urls: [URL] = []
        if let proxyURL = URL(string: "https://wenku8-proxy.us.ci/down.php?type=utf8&node=1&id=\(bookID)") {
            urls.append(proxyURL)
        }
        urls.append(contentsOf: (1...3).compactMap {
            URL(string: "https://dl.wenku8.com/down.php?type=utf8&node=\($0)&id=\(bookID)")
        })
        let bodies = await withTaskGroup(of: [ChapterBody]?.self, returning: [ChapterBody]?.self) { group in
            for url in urls {
                group.addTask {
                    guard let text = try? await fetchText(from: url) else { return nil }
                    return parseChapters(text)
                }
            }
            for await chapters in group {
                if let chapters, !chapters.isEmpty {
                    group.cancelAll()
                    return chapters
                }
            }
            return nil
        }
        guard let bodies, !bodies.isEmpty else { throw Wenku8CatalogError.downloadFailed }
        return bodies
    }

    static func bookID(from url: URL) -> Int? {
        let pattern = #"/book/(\d+)\.htm"#
        guard let range = url.absoluteString.range(of: pattern, options: .regularExpression) else { return nil }
        let match = url.absoluteString[range]
        return Int(match.replacingOccurrences(of: #"\D"#, with: "", options: .regularExpression))
    }

    private static func fetchText(from url: URL) async throws -> String {
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1",
                         forHTTPHeaderField: "User-Agent")
        request.setValue("https://www.wenku8.net/", forHTTPHeaderField: "Referer")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
              data.count >= 500 else { throw Wenku8CatalogError.downloadFailed }
        let text = decode(data)
        let lowercased = text.lowercased()
        guard !lowercased.contains("<html"), !lowercased.contains("just a moment") else {
            throw Wenku8CatalogError.downloadFailed
        }
        return text
    }

    private static func decode(_ data: Data) -> String {
        if data.starts(with: [0xEF, 0xBB, 0xBF]) {
            return String(decoding: data.dropFirst(3), as: UTF8.self)
        }
        if let utf8 = String(data: data, encoding: .utf8) { return utf8 }
        let encoding = CFStringConvertEncodingToNSStringEncoding(
            CFStringConvertIANACharSetNameToEncoding("GB18030" as CFString)
        )
        return String(data: data, encoding: String.Encoding(rawValue: encoding)) ?? String(decoding: data, as: UTF8.self)
    }

    private static func parseChapters(_ text: String) -> [ChapterBody] {
        let chapterPattern = #"^(?:(?:第\s*[一二三四五六七八九十百千万\d]+\s*[卷部篇集]\s*)?第\s*[一二三四五六七八九十百千万\d]+\s*[章节回话].*|第\s*[一二三四五六七八九十百千万\d]+\s*[卷部篇集].*|序[曲章]?.*|尾声.*|终章.*|后记.*|插图.*|插画.*|番外.*|引子.*|幕间.*)$"#
        guard let chapterRegex = try? NSRegularExpression(pattern: chapterPattern),
              let volumeRegex = try? NSRegularExpression(pattern: #"^第[一二三四五六七八九十百千万\d]+[卷部篇集]"#) else { return [] }

        var result: [ChapterBody] = []
        var currentTitle = ""
        var currentLines: [String] = []
        func flush() {
            guard !currentTitle.isEmpty else { return }
            let body = currentLines.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
            let isVolume = volumeRegex.firstMatch(in: currentTitle, range: NSRange(currentTitle.startIndex..., in: currentTitle)) != nil
            if !body.isEmpty || !isVolume {
                result.append(ChapterBody(title: currentTitle, content: body))
            }
            currentLines = []
        }

        for (index, line) in text.components(separatedBy: .newlines).enumerated() {
            let title = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if index < 6 && (title.localizedCaseInsensitiveContains("WenKu8") || (title.hasPrefix("<") && title.hasSuffix(">"))) {
                continue
            }
            let range = NSRange(title.startIndex..., in: title)
            if !title.isEmpty, title.count <= 45, chapterRegex.firstMatch(in: title, range: range) != nil {
                flush()
                currentTitle = title
            } else if !currentTitle.isEmpty {
                currentLines.append(line)
            }
        }
        flush()
        return result
    }
}

private enum Wenku8CatalogError: LocalizedError {
    case downloadFailed
    var errorDescription: String? { "文库下载节点暂时不可用或未能解析章节" }
}
