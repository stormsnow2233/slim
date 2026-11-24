import Foundation

/// Stores chapter bodies as files under Application Support so the UserDefaults
/// book JSON stays small (mirrors the Android file-backed cache behavior).
final class ContentStore {
    static let shared = ContentStore()

    private let root: URL
    private var memory: [String: String] = [:]

    private init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        root = base.appendingPathComponent("ChapterContents", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    private func fileURL(bookID: UUID, chapterID: Int) -> URL {
        root.appendingPathComponent("\(bookID.uuidString)_\(chapterID).txt")
    }

    func content(bookID: UUID, chapterID: Int) -> String? {
        let key = "\(bookID.uuidString)_\(chapterID)"
        if let cached = memory[key] { return cached }
        let url = fileURL(bookID: bookID, chapterID: chapterID)
        guard let text = try? String(contentsOf: url, encoding: .utf8), !text.isEmpty else { return nil }
        memory[key] = text
        return text
    }

    func save(_ text: String, bookID: UUID, chapterID: Int) {
        let key = "\(bookID.uuidString)_\(chapterID)"
        memory[key] = text
        let url = fileURL(bookID: bookID, chapterID: chapterID)
        try? text.write(to: url, atomically: true, encoding: .utf8)
    }

    func remove(bookID: UUID) {
        let prefix = "\(bookID.uuidString)_"
        for key in memory.keys where key.hasPrefix(prefix) { memory.removeValue(forKey: key) }
        if let files = try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) {
            for file in files where file.lastPathComponent.hasPrefix(prefix) {
                try? FileManager.default.removeItem(at: file)
            }
        }
    }
}
