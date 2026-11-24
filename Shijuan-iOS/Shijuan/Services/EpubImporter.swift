import Foundation
import Compression

/// Minimal dependency-free EPUB importer.
/// Reads ZIP central directory entries, inflates them with the system
/// Compression framework, parses container.xml/OPF with XMLParser, and splits
/// spine documents into chapters.
enum EpubImporter {
    enum ImportError: LocalizedError {
        case notZip
        case noContainer
        case noSpine
        var errorDescription: String? {
            switch self {
            case .notZip: return "文件不是有效的 EPUB（ZIP）包"
            case .noContainer: return "EPUB 缺少 container.xml"
            case .noSpine: return "EPUB 缺少可读的目录结构"
            }
        }
    }

    struct Entry {
        let name: String
        let method: UInt16
        let compressedSize: Int
        let uncompressedSize: Int
        let localHeaderOffset: Int
    }

    static func importBook(from url: URL, title fallback: String) throws -> [String] {
        let granted = url.startAccessingSecurityScopedResource()
        defer { if granted { url.stopAccessingSecurityScopedResource() } }
        let data = try Data(contentsOf: url)
        let entries = try readCentralDirectory(data)
        guard !entries.isEmpty else { throw ImportError.notZip }

        func fileData(_ name: String) -> Data? {
            guard let entry = entries.first(where: { $0.name == name }) else { return nil }
            return try? inflate(entry, from: data)
        }

        // META-INF/container.xml -> rootfile path
        guard let container = fileData("META-INF/container.xml"),
              let rootPath = parseContainer(container) else { throw ImportError.noContainer }
        guard let opf = fileData(rootPath) else { throw ImportError.noContainer }
        let opfDir = (rootPath as NSString).deletingLastPathComponent
        let book = parseOPF(opf, opfDir: opfDir)
        guard !book.spine.isEmpty else { throw ImportError.noSpine }

        // XHTML -> plain paragraphs, one per spine document (chapter-like split).
        var chapters: [String] = []
        for href in book.spine {
            guard let doc = fileData(href),
                  let text = htmlToText(doc) else { continue }
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty { chapters.append(trimmed) }
        }
        guard !chapters.isEmpty else { throw ImportError.noSpine }
        _ = fallback
        return chapters
    }

    // MARK: - ZIP central directory

    private static func readCentralDirectory(_ data: Data) throws -> [Entry] {
        let bytes = [UInt8](data)
        // Locate EOCD (0x06054b50) scanning backwards from the tail.
        guard data.count >= 22 else { throw ImportError.notZip }
        var eocd = -1
        let searchStart = max(0, data.count - 22 - 65536)
        var i = data.count - 22
        while i >= searchStart {
            if bytes[i] == 0x50, bytes[i+1] == 0x4b, bytes[i+2] == 0x05, bytes[i+3] == 0x06 { eocd = i; break }
            i -= 1
        }
        guard eocd >= 0 else { throw ImportError.notZip }
        func le16(_ o: Int) -> UInt16 { UInt16(bytes[o]) | (UInt16(bytes[o+1]) << 8) }
        func le32(_ o: Int) -> UInt32 { UInt32(bytes[o]) | (UInt32(bytes[o+1]) << 8) | (UInt32(bytes[o+2]) << 16) | (UInt32(bytes[o+3]) << 24) }
        let count = Int(le16(eocd + 10))
        var offset = Int(le32(eocd + 16))
        var entries: [Entry] = []
        for _ in 0..<count where offset + 46 <= data.count {
            guard bytes[offset] == 0x50, bytes[offset+1] == 0x4b, bytes[offset+2] == 0x01, bytes[offset+3] == 0x02 else { break }
            let method = le16(offset + 10)
            let cSize = Int(le32(offset + 20))
            let uSize = Int(le32(offset + 24))
            let nameLen = Int(le16(offset + 28))
            let extraLen = Int(le16(offset + 30))
            let commentLen = Int(le16(offset + 32))
            let localOffset = Int(le32(offset + 42))
            if offset + 46 + nameLen <= data.count {
                let nameData = data.subdata(in: (offset + 46)..<(offset + 46 + nameLen))
                let name = String(decoding: nameData, as: UTF8.self)
                entries.append(Entry(name: name, method: method, compressedSize: cSize,
                                     uncompressedSize: uSize, localHeaderOffset: localOffset))
            }
            offset += 46 + nameLen + extraLen + commentLen
        }
        return entries
    }

    private static func inflate(_ entry: Entry, from data: Data) throws -> Data {
        let bytes = [UInt8](data)
        let o = entry.localHeaderOffset
        guard o + 30 <= data.count, bytes[o] == 0x50, bytes[o+1] == 0x4b, bytes[o+2] == 0x03, bytes[o+3] == 0x04 else {
            throw ImportError.notZip
        }
        func le16(_ i: Int) -> Int { Int(bytes[i]) | (Int(bytes[i+1]) << 8) }
        let nameLen = le16(o + 26)
        let extraLen = le16(o + 28)
        let start = o + 30 + nameLen + extraLen
        let end = start + entry.compressedSize
        guard end <= data.count else { throw ImportError.notZip }
        let compressed = data.subdata(in: start..<end)
        switch entry.method {
        case 0:
            return compressed
        case 8:
            // Raw deflate; strip the 2-byte zlib header if present.
            var payload = compressed
            if payload.count >= 2, payload[0] == 0x78 { payload = payload.subdata(in: 2..<payload.count) }
            let dstCapacity = max(entry.uncompressedSize, compressed.count * 8)
            var dst = Data(count: dstCapacity)
            let written = dst.withUnsafeMutableBytes { dstPtr in
                payload.withUnsafeBytes { srcPtr in
                    compression_decode_buffer(dstPtr.bindMemory(to: UInt8.self).baseAddress!, dstCapacity,
                                              srcPtr.bindMemory(to: UInt8.self).baseAddress!, payload.count,
                                              nil, COMPRESSION_ZLIB)
                }
            }
            guard written > 0 else { throw ImportError.notZip }
            dst.removeSubrange(written..<dst.count)
            return dst
        default:
            throw ImportError.notZip
        }
    }

    // MARK: - XML parsing

    private final class ContainerParser: NSObject, XMLParserDelegate {
        var rootfilePath: String?
        func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String] = [:]) {
            if name == "rootfile", let p = attributes["full-path"], !p.isEmpty { rootfilePath = p }
        }
    }

    private static func parseContainer(_ data: Data) -> String? {
        let parser = XMLParser(data: data)
        let delegate = ContainerParser()
        parser.delegate = delegate
        parser.parse()
        return delegate.rootfilePath
    }

    private final class OPFParser: NSObject, XMLParserDelegate {
        var manifest: [String: String] = [:]   // id -> href
        var spine: [String] = []
        var title: String = ""
        private var inTitle = false
        private var inManifest = false
        private var inSpine = false

        func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String] = [:]) {
            switch name {
            case "manifest": inManifest = true
            case "spine": inSpine = true
            case "title": inTitle = true
            case "item" where inManifest:
                if let id = attributes["id"], let href = attributes["href"], let type = attributes["media-type"],
                   type.contains("html") { manifest[id] = href }
            case "itemref" where inSpine:
                if let idref = attributes["idref"], let href = manifest[idref] { spine.append(href) }
            default: break
            }
        }
        func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
            switch name {
            case "manifest": inManifest = false
            case "spine": inSpine = false
            case "title": inTitle = false
            default: break
            }
        }
        func parser(_ parser: XMLParser, foundCharacters string: String) {
            if inTitle { title += string }
        }
    }

    private static func parseOPF(_ data: Data, opfDir: String) -> (title: String, spine: [String]) {
        let parser = XMLParser(data: data)
        let delegate = OPFParser()
        parser.delegate = delegate
        parser.parse()
        let resolved = delegate.spine.compactMap { href -> String? in
            if opfDir.isEmpty { return href }
            return (opfDir as NSString).appendingPathComponent(href)
        }
        return (delegate.title.trimmingCharacters(in: .whitespacesAndNewlines), resolved)
    }

    // MARK: - HTML -> text

    private static func htmlToText(_ data: Data) -> String? {
        guard var text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) else { return nil }
        // Strip head/script/style, keep paragraph structure.
        text = text.replacingOccurrences(of: "(?is)<(head|script|style)[^>]*>.*?</\\1>", with: "", options: .regularExpression)
        text = text.replacingOccurrences(of: "(?i)<br\\s*/?>", with: "\n", options: .regularExpression)
        text = text.replacingOccurrences(of: "</(p|div|h[1-6])>", with: "\n", options: [.regularExpression, .caseInsensitive])
        text = text.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
        for (entity, char) in [("&nbsp;", " "), ("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"),
                               ("&quot;", "\""), ("&#39;", "'"), ("&apos;", "'")] {
            text = text.replacingOccurrences(of: entity, with: char)
        }
        text = text.replacingOccurrences(of: "&#(\\d+);", with: "", options: .regularExpression)
        return text
            .components(separatedBy: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .joined(separator: "\n")
    }
}
