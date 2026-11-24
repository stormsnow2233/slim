import Foundation

/// Minimal rule-driven source model, mirroring the Android Sources.kt/Rules.kt
/// subset: GET search, detail/toc/content CSS expressions, `||` fallbacks,
/// `##regex##replacement` rewrites and relative URL resolution.
struct RuleSource: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var baseURL: String
    var mirrors: [String] = []
    var enabled: Bool = true
    var search: RuleSearch?
    var detail: RuleDetail?
    var toc: RuleToc?
    var content: RuleContent?

    private enum CodingKeys: String, CodingKey {
        case id, name, base, baseURL, mirrors, enabled, search, detail, toc, content
    }

    init(id: String, name: String, baseURL: String, mirrors: [String] = [], enabled: Bool = true,
         search: RuleSearch? = nil, detail: RuleDetail? = nil, toc: RuleToc? = nil, content: RuleContent? = nil) {
        self.id = id; self.name = name; self.baseURL = baseURL
        self.mirrors = mirrors; self.enabled = enabled
        self.search = search; self.detail = detail; self.toc = toc; self.content = content
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? c.decode(String.self, forKey: .id)) ?? UUID().uuidString
        name = (try? c.decode(String.self, forKey: .name)) ?? id
        baseURL = (try? c.decode(String.self, forKey: .base)) ?? (try? c.decode(String.self, forKey: .baseURL)) ?? ""
        mirrors = (try? c.decode([String].self, forKey: .mirrors)) ?? []
        enabled = (try? c.decode(Bool.self, forKey: .enabled)) ?? true
        search = try? c.decode(RuleSearch.self, forKey: .search)
        detail = try? c.decode(RuleDetail.self, forKey: .detail)
        toc = try? c.decode(RuleToc.self, forKey: .toc)
        content = try? c.decode(RuleContent.self, forKey: .content)
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(name, forKey: .name)
        try c.encode(baseURL, forKey: .base)
        try c.encode(mirrors, forKey: .mirrors)
        try c.encode(enabled, forKey: .enabled)
        try c.encodeIfPresent(search, forKey: .search)
        try c.encodeIfPresent(detail, forKey: .detail)
        try c.encodeIfPresent(toc, forKey: .toc)
        try c.encodeIfPresent(content, forKey: .content)
    }

    var bases: [String] {
        var seen = Set<String>(); var out: [String] = []
        for b in ([baseURL] + mirrors) where !b.trimmingCharacters(in: .whitespaces).isEmpty {
            if seen.insert(b).inserted { out.append(b) }
        }
        return out
    }
}

struct RuleSearch: Codable, Hashable {
    var url: String            // template with {key}
    var list: String = ""      // selector; empty = root
    var fields: [String: String] = [:]  // logical field -> expression
    private enum CodingKeys: String, CodingKey { case url, list, selector, fields }
    init(url: String, list: String = "", fields: [String: String] = [:]) { self.url = url; self.list = list; self.fields = fields }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        url = (try? c.decode(String.self, forKey: .url)) ?? ""
        list = (try? c.decode(String.self, forKey: .list)) ?? (try? c.decode(String.self, forKey: .selector)) ?? ""
        fields = (try? c.decode([String: String].self, forKey: .fields)) ?? [:]
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(url, forKey: .url)
        try c.encode(list, forKey: .list)
        try c.encode(fields, forKey: .fields)
    }
}

struct RuleDetail: Codable, Hashable {
    var title: String = ""
    var author: String = ""
    var intro: String = ""
    private enum CodingKeys: String, CodingKey { case title, name, author, intro, description }
    init(title: String = "", author: String = "", intro: String = "") { self.title = title; self.author = author; self.intro = intro }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        title = (try? c.decode(String.self, forKey: .title)) ?? (try? c.decode(String.self, forKey: .name)) ?? ""
        author = (try? c.decode(String.self, forKey: .author)) ?? ""
        intro = (try? c.decode(String.self, forKey: .intro)) ?? (try? c.decode(String.self, forKey: .description)) ?? ""
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(title, forKey: .title)
        try c.encode(author, forKey: .author)
        try c.encode(intro, forKey: .intro)
    }
}

struct RuleToc: Codable, Hashable {
    var list: String           // selector for chapter row nodes
    var name: String = "@text"
    var url: String = "@href"
    private enum CodingKeys: String, CodingKey { case list, selector, name, url }
    init(list: String, name: String = "@text", url: String = "@href") { self.list = list; self.name = name; self.url = url }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        list = (try? c.decode(String.self, forKey: .list)) ?? (try? c.decode(String.self, forKey: .selector)) ?? ""
        name = (try? c.decode(String.self, forKey: .name)) ?? "@text"
        url = (try? c.decode(String.self, forKey: .url)) ?? "@href"
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(list, forKey: .list)
        try c.encode(name, forKey: .name)
        try c.encode(url, forKey: .url)
    }
}

struct RuleContent: Codable, Hashable {
    var body: String = ""
    init(body: String) { self.body = body }
}

enum RuleEngineError: LocalizedError {
    case badBase, badURL
    var errorDescription: String? {
        switch self {
        case .badBase: return "书源 base 地址无效"
        case .badURL: return "书源请求地址无效"
        }
    }
}
