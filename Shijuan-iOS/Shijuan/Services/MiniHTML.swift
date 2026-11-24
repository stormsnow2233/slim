import Foundation

/// Tiny HTML DOM + CSS-subset selector engine used by RuleEngine.
/// Supports: tag, .class, #id, descendant space, child >, [attr], [attr=v].
final class MiniHTMLNode {
    var tag: String
    var attributes: [String: String] = [:]
    var children: [MiniHTMLNode] = []
    var textContent: String = ""
    weak var parent: MiniHTMLNode?

    init(tag: String) { self.tag = tag.lowercased() }

    var ownText: String { textContent }

    var allText: String {
        var out = textContent
        for child in children { out += child.allText }
        return out
    }

    func attribute(_ name: String) -> String? { attributes[name.lowercased()] }

    func select(_ selector: String) -> [MiniHTMLNode] {
        var results: [MiniHTMLNode] = []
        walk { node in
            if Self.matches(node, selector: selector) { results.append(node) }
        }
        return results
    }

    func first(_ selector: String) -> MiniHTMLNode? { select(selector).first }

    private func walk(_ visit: (MiniHTMLNode) -> Void) {
        visit(self)
        for child in children { child.walk(visit) }
    }

    static func matches(_ node: MiniHTMLNode, selector: String) -> Bool {
        let parts = selector.split(separator: ">", omittingEmptySubsequences: true)
            .map { $0.trimmingCharacters(in: .whitespaces) }
        if parts.isEmpty { return matchesSimple(node, selector: selector) }
        return matchesChain(node, parts: parts)
    }

    private static func matchesChain(_ node: MiniHTMLNode, parts: [String]) -> Bool {
        guard matchesSimple(node, selector: parts.last!) else { return false }
        var current = node
        // ">" chain: each part must match an ancestor (descendant combination via " " already split).
        for part in parts.dropLast().reversed() {
            var found = false
            // Treat inner " " within a part as descendant requirement handled by simple match;
            // full descendant logic:
            let spaceParts = part.split(separator: " ").map { String($0) }
            for sp in spaceParts.reversed() {
                var ancestor = current.parent
                found = false
                while let a = ancestor {
                    if matchesSimple(a, selector: sp) {
                        current = a; found = true; break
                    }
                    ancestor = a.parent
                }
                if !found { return false }
            }
            if !found { return false }
        }
        return true
    }

    static func matchesSimple(_ node: MiniHTMLNode, selector: String) -> Bool {
        let trimmed = selector.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return true }
        var ok = true
        let regex = try? NSRegularExpression(pattern: #"([.#]?[\w-]+|\[[^\]]+\])"#)
        let ns = trimmed as NSString
        let tokens = regex?.matches(in: trimmed, range: NSRange(location: 0, length: ns.length)) ?? []
        for m in tokens {
            let token = ns.substring(with: m.range)
            if token.hasPrefix(".") {
                let classes = (node.attributes["class"] ?? "").split(separator: " ").map(String.init)
                ok = ok && classes.contains(String(token.dropFirst()))
            } else if token.hasPrefix("#") {
                ok = ok && node.attributes["id"] == String(token.dropFirst())
            } else if token.hasPrefix("[") {
                let inner = String(token.dropFirst().dropLast())
                let kv = inner.split(separator: "=", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
                guard let attr = kv.first else { ok = false; break }
                if kv.count == 1 {
                    ok = ok && node.attributes[attr.lowercased()] != nil
                } else {
                    let value = kv[1].trimmingCharacters(in: CharacterSet(charactersIn: "\"'"))
                    ok = ok && node.attributes[attr.lowercased()] == value
                }
            } else {
                ok = ok && node.tag == token.lowercased()
            }
            if !ok { break }
        }
        return ok
    }
}

enum MiniHTML {
    /// Parse HTML into a DOM tree. This is a tolerant parser: unclosed tags,
    /// missing quotes and malformed markup are handled best-effort.
    static func parse(_ html: String) -> MiniHTMLNode {
        let root = MiniHTMLNode(tag: "#document")
        var stack: [MiniHTMLNode] = [root]
        var pendingText = ""

        // Preprocess: drop comments, script/style bodies.
        var cleaned = html.replacingOccurrences(of: "(?s)<!--.*?-->", with: "", options: .regularExpression)
        cleaned = cleaned.replacingOccurrences(of: "(?is)<(script|style)[^>]*>.*?</\\1>", with: "", options: .regularExpression)

        let tagRegex = try? NSRegularExpression(pattern: #"<(/?)([\w-]+)((?:[^>"']|"[^"]*"|'[^']*')*)(/?)>"#)
        let ns = cleaned as NSString
        var cursor = cleaned.startIndex
        let matches = tagRegex?.matches(in: cleaned, range: NSRange(location: 0, length: ns.length)) ?? []

        func flushText() {
            let decoded = Self.decodeEntities(pendingText)
            if !decoded.isEmpty {
                let node = MiniHTMLNode(tag: "#text")
                node.textContent = decoded
                let parent = stack.last!
                node.parent = parent
                parent.children.append(node)
            }
            pendingText = ""
        }

        for m in matches {
            let tagStart = cleaned.index(cursor, offsetBy: m.range.location - cleaned.distance(from: cleaned.startIndex, to: cursor), limitedBy: cleaned.endIndex) ?? cursor
            let textRange = cursor..<tagStart
            pendingText += String(cleaned[textRange])
            cursor = cleaned.index(cleaned.startIndex, offsetBy: m.range.location + m.range.length)

            let closing = ns.substring(with: m.range(at: 1)) == "/"
            let tag = ns.substring(with: m.range(at: 2)).lowercased()
            let attrsRaw = ns.substring(with: m.range(at: 3))
            let selfClosing = !ns.substring(with: m.range(at: 4)).isEmpty
                || ["br", "img", "meta", "link", "input", "hr", "area", "col", "embed", "source", "wbr"].contains(tag)

            if closing {
                // Pop until matching tag (tolerate mismatches).
                if let idx = stack.lastIndex(where: { $0.tag == tag }) {
                    stack.removeSubrange((idx + 1)...)
                }
                flushText()
            } else {
                flushText()
                let node = MiniHTMLNode(tag: tag)
                let attrRegex = try? NSRegularExpression(pattern: #"([\w-]+)\s*=\s*("([^"]*)"|'([^']*)'|[^\s">]+)"#)
                let ans = attrsRaw as NSString
                for am in attrRegex?.matches(in: attrsRaw, range: NSRange(location: 0, length: ans.length)) ?? [] {
                    let key = ans.substring(with: am.range(at: 1)).lowercased()
                    var value = am.range(at: 3).location != NSNotFound
                        ? ans.substring(with: am.range(at: 3))
                        : ans.substring(with: am.range(at: 2))
                    value = Self.decodeEntities(value)
                    node.attributes[key] = value
                }
                let parent = stack.last!
                node.parent = parent
                parent.children.append(node)
                if !selfClosing { stack.append(node) }
            }
        }
        pendingText += String(cleaned[cursor...])
        flushText()
        return root
    }

    static func decodeEntities(_ s: String) -> String {
        var t = s
        for (e, c) in [("&nbsp;", " "), ("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"),
                       ("&quot;", "\""), ("&apos;", "'"), ("&#39;", "'"), ("&ldquo;", "“"), ("&rdquo;", "”")] {
            t = t.replacingOccurrences(of: e, with: c)
        }
        if let regex = try? NSRegularExpression(pattern: "&#(x[0-9a-fA-F]+|\\d+);") {
            let ns = t as NSString
            let replacements: [(NSRange, String)] = regex.matches(in: t, range: NSRange(location: 0, length: ns.length)).compactMap { m -> (NSRange, String)? in
                let body = ns.substring(with: NSRange(location: m.range.location + 2, length: m.range.length - 3))
                if body.hasPrefix("x") || body.hasPrefix("X") {
                    guard let v = UInt32(body.dropFirst(), radix: 16), v < 0x10000, v != 0 else { return nil }
                    return (m.range, String(UnicodeScalar(v)!))
                } else {
                    guard let v = UInt32(body), v < 0x10000, v != 0 else { return nil }
                    return (m.range, String(UnicodeScalar(v)!))
                }
            }
            for (range, replacement) in replacements.reversed() {
                t = (t as NSString).replacingCharacters(in: range, with: replacement)
            }
        }
        return t
    }
}
