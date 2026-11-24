import Foundation

/// Executes RuleSource definitions over the network using MiniHTML for parsing.
/// Mirrors the Android Rules.kt subset: `selector@field`, `||` fallback,
/// `##regex##replacement`, text/html/href/src/absUrl field extraction and
/// relative URL resolution.
struct RuleEngine {
    let source: RuleSource
    var timeout: TimeInterval = 15

    private static let userAgents = [
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    ]

    private func baseURL() throws -> URL {
        for b in source.bases {
            if let url = URL(string: b.trimmingCharacters(in: .whitespaces)),
               url.scheme == "http" || url.scheme == "https", url.host != nil {
                return url
            }
        }
        throw RuleEngineError.badBase
    }

    private func request(_ url: URL) async throws -> String {
        var req = URLRequest(url: url)
        req.timeoutInterval = timeout
        req.setValue(Self.userAgents[0], forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: req)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        // Honor charset if declared; default to UTF-8.
        if let charset = (http.value(forHTTPHeaderField: "Content-Type") ?? "")
            .split(separator: ";").map({ $0.trimmingCharacters(in: .whitespaces).lowercased() })
            .first(where: { $0.hasPrefix("charset=") }) {
            let name = String(charset.dropFirst("charset=".count))
            let encoding = CFStringConvertIANACharSetNameToEncoding(name as CFString)
            if encoding != kCFStringEncodingInvalidId,
               let decoded = String(data: data, encoding: String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(encoding))) {
                return decoded
            }
        }
        return String(decoding: data, as: UTF8.self)
    }

    private func resolve(_ raw: String, relativeTo base: URL) -> URL? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if let url = URL(string: trimmed), url.scheme != nil { return url }
        return URL(string: trimmed, relativeTo: base)?.absoluteURL
    }

    // MARK: - Expression evaluation

    /// Evaluates an expression like `.booklist a@href##/ads/##` against a node tree.
    static func evaluate(expression: String, on node: MiniHTMLNode, base: URL?) -> String {
        var expr = expression.trimmingCharacters(in: .whitespaces)
        // ##regex##replacement rewrite
        if let range = expr.range(of: "##") {
            let head = String(expr[..<range.lowerBound])
            let rest = String(expr[range.upperBound...])
            var pattern = rest
            var replacement = ""
            if let r2 = rest.range(of: "##") {
                pattern = String(rest[..<r2.lowerBound])
                replacement = String(rest[r2.upperBound...])
            }
            if let first = evaluateSelector(head, on: node, base: base) {
                return first.replacingOccurrences(of: pattern, with: replacement, options: .regularExpression)
            }
            return ""
        }
        return evaluateSelector(expr, on: node, base: base) ?? ""
    }

    private static func evaluateSelector(_ expr: String, on node: MiniHTMLNode, base: URL?) -> String? {
        // `||` fallback chain
        for alternative in splitAlternatives(expr) {
            if let value = evaluateSingle(alternative, on: node, base: base),
               !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return value
            }
        }
        return nil
    }

    private static func splitAlternatives(_ expr: String) -> [String] {
        var parts: [String] = []
        var current = ""
        var i = expr.startIndex
        while i < expr.endIndex {
            if expr[i] == "|", expr.index(after: i) < expr.endIndex, expr[expr.index(after: i)] == "|" {
                parts.append(current)
                current = ""
                i = expr.index(i, offsetBy: 2)
            } else {
                current.append(expr[i])
                i = expr.index(after: i)
            }
        }
        parts.append(current)
        return parts
    }

    private static func evaluateSingle(_ expr: String, on node: MiniHTMLNode, base: URL?) -> String? {
        let trimmed = expr.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }
        let (selector, field) = splitAtField(trimmed)
        let effectiveSelector = selector.trimmingCharacters(in: .whitespaces)
        let target = effectiveSelector.isEmpty ? node : node.first(effectiveSelector)
        guard let target else { return nil }
        return extractField(target, field: field, base: base)
    }

    private static func splitAtField(_ expr: String) -> (String, String) {
        guard let at = expr.lastIndex(of: "@") else { return (expr, "text") }
        // "@field" must follow a selector; a lone "@" means root text.
        let field = String(expr[expr.index(after: at)...]).lowercased()
        if field.isEmpty { return (String(expr[..<at]), "text") }
        return (String(expr[..<at]), field)
    }

    private static func extractField(_ node: MiniHTMLNode, field: String, base: URL?) -> String? {
        switch field {
        case "text", "texts":
            return node.allText.trimmingCharacters(in: .whitespacesAndNewlines)
        case "html":
            return innerHTML(node)
        case "outer":
            return outerHTML(node)
        case "own":
            return node.ownText.trimmingCharacters(in: .whitespacesAndNewlines)
        case "href", "src", "absurl":
            guard let raw = node.attribute(field == "absurl" ? "href" : field) else { return nil }
            guard let base else { return raw }
            return resolveURL(raw, base: base)
        default:
            return node.attribute(field)
        }
    }

    private static func resolveURL(_ raw: String, base: URL) -> String {
        if let url = URL(string: raw.trimmingCharacters(in: .whitespaces)), url.scheme != nil { return url.absoluteString }
        return URL(string: raw.trimmingCharacters(in: .whitespaces), relativeTo: base)?.absoluteURL.absoluteString ?? raw
    }

    private static func innerHTML(_ node: MiniHTMLNode) -> String {
        node.children.map { render($0) }.joined()
    }

    private static func outerHTML(_ node: MiniHTMLNode) -> String { render(node) }

    private static func render(_ node: MiniHTMLNode) -> String {
        if node.tag == "#text" { return node.textContent }
        if node.tag == "#document" { return node.children.map { render($0) }.joined() }
        let attrs = node.attributes.map { " \($0.key)=\"\($0.value)" }.joined()
        let inner = node.children.map { render($0) }.joined()
        return "<\(node.tag)\(attrs)>\(inner)</\(node.tag)>"
    }

    // MARK: - Operations

    func search(keyword: String) async throws -> [RemoteBook] {
        guard let rule = source.search, let base = try? baseURL() else { return [] }
        let templated = rule.url
            .replacingOccurrences(of: "{key}", with: keyword.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? keyword)
            .replacingOccurrences(of: "{page}", with: "1")
        guard let url = resolve(templated, relativeTo: base) else { throw RuleEngineError.badURL }
        let html = try await request(url)
        let dom = MiniHTML.parse(html)
        let rows = rule.list.isEmpty ? [dom] : dom.select(rule.list)
        var results: [RemoteBook] = []
        for row in rows.prefix(100) {
            let title = Self.evaluate(expression: rule.fields["title"] ?? "@text", on: row, base: base)
            let rawURL = Self.evaluate(expression: rule.fields["url"] ?? "a@href", on: row, base: base)
            guard let bookURL = resolve(rawURL, relativeTo: base), !title.isEmpty else { continue }
            let author = rule.fields["author"].map { Self.evaluate(expression: $0, on: row, base: base) } ?? ""
            let intro = rule.fields["intro"].map { Self.evaluate(expression: $0, on: row, base: base) } ?? ""
            let coverRaw = rule.fields["cover"].map { Self.evaluate(expression: $0, on: row, base: base) } ?? ""
            results.append(RemoteBook(title: title, author: author.isEmpty ? "未知作者" : author,
                                      url: bookURL, sourceName: source.name,
                                      coverURL: coverRaw.isEmpty ? nil : resolve(coverRaw, relativeTo: base),
                                      intro: intro))
        }
        return results
    }

    func detail(bookURL: URL) async throws -> RemoteDetail {
        let base = try baseURL()
        let html = try await request(bookURL)
        let dom = MiniHTML.parse(html)
        let rule = source.detail ?? RuleDetail()
        let title = rule.title.isEmpty ? dom.first("h1")?.allText.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            : Self.evaluate(expression: rule.title, on: dom, base: base)
        let author = rule.author.isEmpty ? "" : Self.evaluate(expression: rule.author, on: dom, base: base)
        let intro = rule.intro.isEmpty ? "" : Self.evaluate(expression: rule.intro, on: dom, base: base)
        // TOC may live on the same page or a separate one via toc rule.
        var chapters = try await toc(from: dom, base: base, pageURL: bookURL)
        if chapters.isEmpty, let tocURL = dom.first("a") {
            // No-op fallback; keep empty.
            _ = tocURL
        }
        return RemoteDetail(title: title.isEmpty ? "未命名" : title,
                            author: author.isEmpty ? "未知作者" : author,
                            intro: intro,
                            chapters: chapters)
    }

    private func toc(from dom: MiniHTMLNode, base: URL, pageURL: URL) async throws -> [ChapterSummary] {
        guard let rule = source.toc else { return [] }
        var rows = dom.select(rule.list)
        // Follow toc pagination links up to 10 pages when the rule declares `nextPage`.
        if let nextPageExpr = (try? JSONDecoder().decode(RuleTocNext.self, from: Data()))?.nextPage {
            _ = nextPageExpr // placeholder; pagination handled below via attribute scan
        }
        var nextURL: URL? = nil
        let nextNode = dom.select("a").first(where: {
            let text = $0.allText.trimmingCharacters(in: .whitespaces)
            return text == "下一页" || text == "下页" || text == "下一頁"
        })
        if let nextNode, let href = nextNode.attribute("href") {
            nextURL = resolve(href, relativeTo: pageURL)
        }
        var page = 0
        while page < 10, let url = nextURL {
            let html = try await request(url)
            let nextDom = MiniHTML.parse(html)
            rows += nextDom.select(rule.list)
            nextURL = nextDom.select("a").first(where: {
                let text = $0.allText.trimmingCharacters(in: .whitespaces)
                return text == "下一页" || text == "下页" || text == "下一頁"
            }).flatMap { $0.attribute("href") }.flatMap { resolve($0, relativeTo: url) }
            page += 1
        }
        var seen = Set<String>()
        var chapters: [ChapterSummary] = []
        for row in rows {
            let name = Self.evaluate(expression: rule.name, on: row, base: base)
            let rawURL = Self.evaluate(expression: rule.url, on: row, base: base)
            guard let chapterURL = resolve(rawURL, relativeTo: base), !name.isEmpty,
                  seen.insert(chapterURL.absoluteString).inserted else { continue }
            chapters.append(ChapterSummary(id: chapters.count, title: name, url: chapterURL))
        }
        return chapters
    }

    func content(chapterURL: URL) async throws -> String {
        let base = try baseURL()
        let rule = source.content
        let html = try await request(chapterURL)
        var text: String
        if let rule, !rule.body.isEmpty {
            let dom = MiniHTML.parse(html)
            text = Self.evaluate(expression: rule.body, on: dom, base: base)
            if text.isEmpty { text = dom.first(rule.body.split(separator: "@").first.map(String.init) ?? rule.body)?.allText ?? "" }
        } else {
            let dom = MiniHTML.parse(html)
            // Common content containers fallback.
            let candidate = dom.select("#content, .content, .readcontent, .chapter-content, #chaptercontent").first
            text = candidate.map { $0.allText } ?? dom.allText
        }
        text = text.replacingOccurrences(of: "(?m)^\\s*[-–—_·*\\s]{4,}\\s*$", with: "", options: .regularExpression)
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

private struct RuleTocNext: Codable { var nextPage: String? }
