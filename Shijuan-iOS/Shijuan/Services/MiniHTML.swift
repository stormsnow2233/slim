import Foundation

/// Tiny HTML DOM + CSS-subset selector engine used by RuleEngine.
/// Supports common CSS selectors, combinators, attribute operators, and text/nth-child pseudos.
final class MiniHTMLNode {
    private enum Relation: Equatable {
        case descendant
        case child
    }

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
        splitSelectorGroups(selector).contains { matchesChain(node, selector: $0) }
    }

    private static func splitSelectorGroups(_ selector: String) -> [String] {
        var groups: [String] = []
        var current = ""
        var bracketDepth = 0
        var parenthesisDepth = 0
        var quote: Character?

        for character in selector {
            if let activeQuote = quote {
                current.append(character)
                if character == activeQuote { quote = nil }
                continue
            }
            if (character == "\"" || character == "'") && (bracketDepth > 0 || parenthesisDepth > 0) {
                quote = character
                current.append(character)
            } else if character == "[" {
                bracketDepth += 1
                current.append(character)
            } else if character == "]" {
                bracketDepth = max(0, bracketDepth - 1)
                current.append(character)
            } else if character == "(" {
                parenthesisDepth += 1
                current.append(character)
            } else if character == ")" {
                parenthesisDepth = max(0, parenthesisDepth - 1)
                current.append(character)
            } else if character == "," && bracketDepth == 0 && parenthesisDepth == 0 {
                groups.append(current.trimmingCharacters(in: .whitespacesAndNewlines))
                current = ""
            } else {
                current.append(character)
            }
        }
        groups.append(current.trimmingCharacters(in: .whitespacesAndNewlines))
        return groups.filter { !$0.isEmpty }
    }

    private static func matchesChain(_ node: MiniHTMLNode, selector: String) -> Bool {
        var parts: [String] = []
        var relations: [Relation] = []
        var currentPart = ""
        var pendingRelation: Relation?
        var bracketDepth = 0
        var parenthesisDepth = 0
        var quote: Character?

        func appendPart() {
            let part = currentPart.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !part.isEmpty else { return }
            if !parts.isEmpty { relations.append(pendingRelation ?? .descendant) }
            parts.append(part)
            currentPart = ""
            pendingRelation = nil
        }

        for character in selector {
            if let activeQuote = quote {
                currentPart.append(character)
                if character == activeQuote { quote = nil }
                continue
            }
            if (character == "\"" || character == "'") && (bracketDepth > 0 || parenthesisDepth > 0) {
                quote = character
                currentPart.append(character)
            } else if character == "[" {
                bracketDepth += 1
                currentPart.append(character)
            } else if character == "]" {
                bracketDepth = max(0, bracketDepth - 1)
                currentPart.append(character)
            } else if character == "(" {
                parenthesisDepth += 1
                currentPart.append(character)
            } else if character == ")" {
                parenthesisDepth = max(0, parenthesisDepth - 1)
                currentPart.append(character)
            } else if bracketDepth == 0 && parenthesisDepth == 0 && character == ">" {
                appendPart()
                pendingRelation = .child
            } else if bracketDepth == 0 && parenthesisDepth == 0 && character.isWhitespace {
                appendPart()
                if !parts.isEmpty && pendingRelation == nil { pendingRelation = .descendant }
            } else {
                currentPart.append(character)
            }
        }
        appendPart()
        guard let last = parts.last, matchesSimple(node, selector: last) else { return false }
        guard parts.count > 1 else { return true }

        var current = node
        for index in stride(from: parts.count - 2, through: 0, by: -1) {
            if relations[index] == .child {
                guard let parent = current.parent, matchesSimple(parent, selector: parts[index]) else { return false }
                current = parent
            } else {
                var ancestor = current.parent
                var match: MiniHTMLNode?
                while let candidate = ancestor {
                    if matchesSimple(candidate, selector: parts[index]) {
                        match = candidate
                        break
                    }
                    ancestor = candidate.parent
                }
                guard let match else { return false }
                current = match
            }
        }
        return true
    }

    static func matchesSimple(_ node: MiniHTMLNode, selector: String) -> Bool {
        let trimmed = selector.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return true }
        var ok = true
        var baseSelector = trimmed
        var textPredicates: [(String, String)] = []
        var childIndex: (ofType: Bool, index: Int)?

        if let pseudoRegex = try? NSRegularExpression(pattern: #":(contains|matches)\(([^()]*)\)"#) {
            let ns = baseSelector as NSString
            let range = NSRange(location: 0, length: ns.length)
            for match in pseudoRegex.matches(in: baseSelector, range: range) {
                textPredicates.append((ns.substring(with: match.range(at: 1)), ns.substring(with: match.range(at: 2))))
            }
            baseSelector = pseudoRegex.stringByReplacingMatches(
                in: baseSelector,
                range: range,
                withTemplate: ""
            )
        }
        if let nthRegex = try? NSRegularExpression(pattern: #":(nth-child|nth-of-type)\((\d+)\)"#),
           let match = nthRegex.firstMatch(in: baseSelector, range: NSRange(location: 0, length: (baseSelector as NSString).length)),
           let kindRange = Range(match.range(at: 1), in: baseSelector),
           let indexRange = Range(match.range(at: 2), in: baseSelector),
           let index = Int(baseSelector[indexRange]) {
            childIndex = (ofType: baseSelector[kindRange] == "nth-of-type", index: index)
            baseSelector = nthRegex.stringByReplacingMatches(
                in: baseSelector,
                range: NSRange(location: 0, length: (baseSelector as NSString).length),
                withTemplate: ""
            )
        }
        let text = node.allText
        for (kind, pattern) in textPredicates {
            if kind == "contains" {
                ok = ok && text.localizedCaseInsensitiveContains(pattern)
            } else if let regex = try? NSRegularExpression(pattern: pattern),
                      regex.firstMatch(in: text, range: NSRange(location: 0, length: (text as NSString).length)) == nil {
                ok = false
            }
        }
        if let childIndex {
            let siblings = node.parent?.children.filter { $0.tag != "#text" } ?? []
            let peers = childIndex.ofType ? siblings.filter { $0.tag == node.tag } : siblings
            guard let position = peers.firstIndex(where: { $0 === node }),
                  position + 1 == childIndex.index else { return false }
        }

        let regex = try? NSRegularExpression(pattern: #"([.#]?[\w-]+|\[[^\]]+\])"#)
        let ns = baseSelector as NSString
        let tokens = regex?.matches(in: baseSelector, range: NSRange(location: 0, length: ns.length)) ?? []
        for m in tokens {
            let token = ns.substring(with: m.range)
            if token.hasPrefix(".") {
                let classes = (node.attributes["class"] ?? "").split(separator: " ").map(String.init)
                ok = ok && classes.contains(String(token.dropFirst()))
            } else if token.hasPrefix("#") {
                ok = ok && node.attributes["id"] == String(token.dropFirst())
            } else if token.hasPrefix("[") {
                let inner = String(token.dropFirst().dropLast())
                let operators = ["*=", "^=", "$=", "~=", "|=", "="]
                if let operation = operators.first(where: { inner.contains($0) }),
                   let range = inner.range(of: operation) {
                    let attr = inner[..<range.lowerBound].trimmingCharacters(in: .whitespaces).lowercased()
                    let expected = inner[range.upperBound...]
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                        .trimmingCharacters(in: CharacterSet(charactersIn: "\"'"))
                    guard let actual = node.attributes[attr] else { ok = false; break }
                    switch operation {
                    case "*=": ok = ok && actual.contains(expected)
                    case "^=": ok = ok && actual.hasPrefix(expected)
                    case "$=": ok = ok && actual.hasSuffix(expected)
                    case "~=": ok = ok && actual.split(whereSeparator: { $0.isWhitespace }).contains { String($0) == expected }
                    case "|=": ok = ok && (actual == expected || actual.hasPrefix(expected + "-"))
                    default: ok = ok && actual == expected
                    }
                } else {
                    let attr = inner.trimmingCharacters(in: .whitespaces).lowercased()
                    ok = ok && node.attributes[attr] != nil
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
                flushText()
                // Pop through the matching tag; malformed nesting is tolerated.
                if let idx = stack.lastIndex(where: { $0.tag == tag }), idx > 0 {
                    stack.removeSubrange(idx..<stack.count)
                }
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
