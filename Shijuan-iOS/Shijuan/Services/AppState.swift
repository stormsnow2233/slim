import SwiftUI
import BackgroundTasks
import PDFKit
import UserNotifications

@MainActor
final class AppState: ObservableObject {
    @Published var books: [Book] { didSet { persist() } }
    @Published var searchText = ""
    @Published private(set) var remoteResults: [Book] = []
    @Published private(set) var isSearching = false
    @Published var searchError: String?
    @Published var proxy: ProxyConfiguration { didSet { persistProxy() } }
    @Published var sources: [SourceDescriptor] { didSet { persistSources() } }
    @Published var ruleSources: [RuleSource] { didSet { persistRuleSources() } }

    private let storageKey = "shijuan.ios.books.v2"
    private let proxyKey = "shijuan.ios.proxy.v1"
    private let sourcesKey = "shijuan.ios.sources.v1"
    private let ruleSourcesKey = "shijuan.ios.ruleSources.v1"

    private static func loadBuiltinRuleSources() -> [RuleSource] {
        guard let url = Bundle.main.url(forResource: "builtin_rules", withExtension: "json") else {
            NSLog("Bundled novel source rules are missing.")
            return []
        }
        do {
            return try JSONDecoder().decode([RuleSource].self, from: Data(contentsOf: url))
        } catch {
            NSLog("Bundled novel source rules could not be decoded: %@", error.localizedDescription)
            return []
        }
    }

    init() {
        if let data = UserDefaults.standard.data(forKey: proxyKey),
           var savedProxy = try? JSONDecoder().decode(ProxyConfiguration.self, from: data) {
            savedProxy.migrateDeprecatedDefaults()
            proxy = savedProxy
        } else {
            proxy = ProxyConfiguration()
        }
        if let data = UserDefaults.standard.data(forKey: sourcesKey),
           let savedSources = try? JSONDecoder().decode([SourceDescriptor].self, from: data) {
            sources = savedSources
        } else {
            sources = []
        }
        let builtinRuleSources = Self.loadBuiltinRuleSources()
        if let data = UserDefaults.standard.data(forKey: ruleSourcesKey),
           let savedRuleSources = try? JSONDecoder().decode([RuleSource].self, from: data) {
            let builtinIDs = Set(builtinRuleSources.map(\.id))
            let mergedBuiltins = builtinRuleSources.map { builtin in
                savedRuleSources.first(where: { $0.id == builtin.id }) ?? builtin
            }
            ruleSources = mergedBuiltins + savedRuleSources.filter { !builtinIDs.contains($0.id) }
        } else {
            ruleSources = builtinRuleSources
        }
        if let data = UserDefaults.standard.data(forKey: storageKey),
           let saved = try? JSONDecoder().decode([Book].self, from: data) {
            books = saved
        } else {
            books = [
                Book(title: "从零开始的魔法书", author: "虎走かける", progress: 0.42, isDownloaded: true,
                     chapters: (1...12).map { Chapter(id: $0, title: "第\($0)章", content: "这是示例章节内容。\n\n你可以通过导入 TXT 文件替换示例书籍。", isCached: $0 <= 5) }),
                Book(title: "Re:从零开始的异世界生活", author: "长月达平", progress: 0.18,
                     chapters: (1...8).map { Chapter(id: $0, title: "第\($0)章") })
            ]
        }
    }

    var filteredBooks: [Book] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return books }
        return books.filter { $0.title.localizedCaseInsensitiveContains(query) || $0.author.localizedCaseInsensitiveContains(query) }
    }

    func searchRemote() {
        let keyword = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !keyword.isEmpty else { remoteResults = []; return }
        isSearching = true
        searchError = nil
        remoteResults = Wenku8Catalog.search(keyword: keyword).map { remote in
            Book(title: remote.title, author: remote.author, sourceURL: remote.url,
                 sourceName: remote.sourceName, coverURL: remote.coverURL, intro: remote.intro)
        }
        let urls = sources.filter(\.enabled).map(\.baseURL)
        let activeRuleSources = ruleSources.filter(\.enabled)
        guard !urls.isEmpty || !activeRuleSources.isEmpty else {
            if remoteResults.isEmpty { searchError = "本地文库索引没有匹配结果；可在设置中导入规则书源。" }
            isSearching = false
            return
        }
        Task {
            let outcomes = await withTaskGroup(of: ([RemoteBook], String?).self, returning: [([RemoteBook], String?)].self) { group in
                for url in urls {
                    group.addTask {
                        do { return (try await RemoteCatalog(proxyBaseURL: url).search(keyword: keyword), nil) }
                        catch { return ([], error.localizedDescription) }
                    }
                }
                for source in activeRuleSources {
                    group.addTask {
                        do {
                            let books = try await RuleEngine(source: source).search(keyword: keyword).map { remote in
                                RemoteBook(title: remote.title, author: remote.author, url: remote.url,
                                           sourceName: remote.sourceName, coverURL: remote.coverURL, intro: remote.intro)
                            }
                            return (books, nil)
                        } catch {
                            return ([], "\(source.name)：\(error.localizedDescription)")
                        }
                    }
                }
                var values: [([RemoteBook], String?)] = []
                for await value in group { values.append(value) }
                return values
            }
            var seen = Set(remoteResults.map { "\($0.title.lowercased())|\($0.author.lowercased())" })
            let onlineResults = outcomes.flatMap { $0.0 }.compactMap { item -> Book? in
                let key = "\(item.title.lowercased())|\(item.author.lowercased())"
                guard seen.insert(key).inserted else { return nil }
                return Book(title: item.title, author: item.author, progress: 0,
                            isDownloaded: false, chapters: [], sourceURL: item.url,
                            sourceName: item.sourceName, coverURL: item.coverURL, intro: item.intro)
            }
            remoteResults.append(contentsOf: onlineResults)
            let errors = outcomes.compactMap { $0.1 }
            if remoteResults.isEmpty {
                searchError = errors.first ?? "没有找到结果"
            } else if let error = errors.first {
                searchError = "部分书源加载失败：\(error)"
            }
            isSearching = false
        }
    }

    func persist() {
        guard let data = try? JSONEncoder().encode(books) else { return }
        UserDefaults.standard.set(data, forKey: storageKey)
        persistProxy()
    }

    private func persistProxy() {
        if let data = try? JSONEncoder().encode(proxy) {
            UserDefaults.standard.set(data, forKey: proxyKey)
        }
    }

    private func persistSources() {
        if let data = try? JSONEncoder().encode(sources) {
            UserDefaults.standard.set(data, forKey: sourcesKey)
        }
    }

    private func persistRuleSources() {
        if let data = try? JSONEncoder().encode(ruleSources) {
            UserDefaults.standard.set(data, forKey: ruleSourcesKey)
        }
    }

    // MARK: - Rule-driven sources (Android Sources.kt compatible subset)

    enum RuleSourceError: LocalizedError {
        case invalidJSON, missingBase, noRules
        var errorDescription: String? {
            switch self {
            case .invalidJSON: return "书源 JSON 解析失败"
            case .missingBase: return "书源缺少 base 地址"
            case .noRules: return "书源缺少搜索或目录规则"
            }
        }
    }

    /// Imports one JSON object or a JSON array of rule sources.
    func addRuleSourceJSON(_ json: String) throws {
        let trimmed = json.trimmingCharacters(in: .whitespacesAndNewlines)
        var decoded: [RuleSource] = []
        if trimmed.hasPrefix("[") {
            decoded = try JSONDecoder().decode([RuleSource].self, from: Data(trimmed.utf8))
        } else {
            decoded = [try JSONDecoder().decode(RuleSource.self, from: Data(trimmed.utf8))]
        }
        guard !decoded.isEmpty else { throw RuleSourceError.invalidJSON }
        for source in decoded {
            guard !source.bases.isEmpty else { throw RuleSourceError.missingBase }
            guard source.search != nil || source.toc != nil else { throw RuleSourceError.noRules }
        }
        ruleSources.append(contentsOf: decoded)
    }

    func removeRuleSource(_ source: RuleSource) {
        ruleSources.removeAll { $0.id == source.id }
    }

    func setRuleSourceEnabled(_ id: String, enabled: Bool) {
        guard let index = ruleSources.firstIndex(where: { $0.id == id }) else { return }
        ruleSources[index].enabled = enabled
    }

    /// Fetches detail/toc through the rule engine when the book belongs to a
    /// rule source (sourceName matches the rule source name), falling back to
    /// the proxy catalog otherwise.
    func loadDetailForBook(bookID: UUID) async {
        guard let index = books.firstIndex(where: { $0.id == bookID }) else { return }
        let book = books[index]
        if let ruleSource = ruleSources.first(where: { $0.name == book.sourceName }),
           let bookURL = book.sourceURL {
            do {
                let engine = RuleEngine(source: ruleSource)
                let detail = try await engine.detail(bookURL: bookURL)
                await MainActor.run { mergeRemoteDetail(bookID: bookID, detail: detail) }
                return
            } catch {
                await MainActor.run { searchError = "规则书源详情加载失败: \(error.localizedDescription)" }
            }
        }
        await loadRemoteDetail(bookID: bookID)
    }

    /// Loads chapter content when empty (UI entry point). Downloads route
    /// through loadChapter, which already prefers the rule engine.
    func loadContentForChapter(bookID: UUID, chapterID: Int) async {
        guard let chapter = books.first(where: { $0.id == bookID })?.chapters.first(where: { $0.id == chapterID }),
              !chapter.isCached, chapter.content.isEmpty, chapter.url != nil else { return }
        await loadChapter(bookID: bookID, chapterID: chapterID)
    }

    func addSource(name: String, baseURL: String) throws {
        guard let url = URL(string: baseURL.trimmingCharacters(in: .whitespacesAndNewlines)),
              url.scheme == "http" || url.scheme == "https", url.host != nil else { throw SourceError.invalidURL }
        sources.append(SourceDescriptor(name: name.isEmpty ? (url.host ?? "自定义书源") : name, baseURL: url))
    }

    func removeSource(_ source: SourceDescriptor) {
        sources.removeAll { $0.id == source.id }
    }

    func setSourceEnabled(_ sourceID: UUID, enabled: Bool) {
        guard let index = sources.firstIndex(where: { $0.id == sourceID }) else { return }
        sources[index].enabled = enabled
    }

    enum SourceError: LocalizedError { case invalidURL; var errorDescription: String? { "书源地址无效" } }

    func addRemoteBook(_ remote: RemoteBook) {
        guard !books.contains(where: { $0.title == remote.title && $0.author == remote.author }) else { return }
        books.insert(Book(title: remote.title, author: remote.author, sourceURL: remote.url,
                          sourceName: remote.sourceName, coverURL: remote.coverURL, intro: remote.intro), at: 0)
    }

    func mergeRemoteDetail(bookID: UUID, detail: RemoteDetail) {
        guard let index = books.firstIndex(where: { $0.id == bookID }) else { return }
        if !detail.title.isEmpty { books[index].title = detail.title }
        if !detail.author.isEmpty { books[index].author = detail.author }
        if !detail.intro.isEmpty { books[index].intro = detail.intro }
        if let coverURL = detail.coverURL { books[index].coverURL = coverURL }
        books[index].chapters = detail.chapters.map { Chapter(id: $0.id, title: $0.title, url: $0.url, isCached: $0.isCached) }
        books[index].isDownloaded = !detail.chapters.isEmpty && detail.chapters.allSatisfy(\.isCached)
    }

    // MARK: - File-backed chapter bodies

    /// Chapter content is persisted as files via ContentStore; the in-memory
    /// `Chapter.content` is only a cache for the currently shown chapter.
    private func storedContent(bookID: UUID, chapterID: Int) -> String {
        ContentStore.shared.content(bookID: bookID, chapterID: chapterID) ?? ""
    }

    /// Removes the downloaded bodies of an imported book from disk.
    func deleteBook(_ book: Book) {
        ContentStore.shared.remove(bookID: book.id)
        books.removeAll { $0.id == book.id }
    }

    /// Loads a remote book's directory using the configured proxy.
    func loadRemoteDetail(bookID: UUID) async {
        guard let book = books.first(where: { $0.id == bookID }),
              let sourceURL = book.sourceURL else { return }
        if book.sourceName == Wenku8Catalog.sourceName {
            guard let aid = Wenku8Catalog.bookID(from: sourceURL) else {
                searchError = "无法识别文库书籍编号"
                return
            }
            do { try await loadWenku8Book(bookID: bookID, aid: aid) }
            catch { searchError = "加载文库目录失败：\(error.localizedDescription)" }
            return
        }
        do {
            let remote = RemoteBook(title: book.title, author: book.author, url: sourceURL,
                                    sourceName: book.sourceName)
            let base = catalogBase(for: book)
            let detail = try await RemoteCatalog(proxyBaseURL: base).detail(for: remote)
            mergeRemoteDetail(bookID: bookID, detail: detail)
        } catch {
            searchError = "加载目录失败：\(error.localizedDescription)"
        }
    }

    private func loadWenku8Book(bookID: UUID, aid: Int) async throws {
        guard let book = books.first(where: { $0.id == bookID }) else { return }
        let bodies = try await Wenku8Catalog.downloadChapters(bookID: aid)
        let summaries = bodies.enumerated().map { index, body in
            ChapterSummary(id: index, title: body.title,
                           url: URL(string: "wenku8://\(aid)/chapter/\(index)")!,
                           isCached: true)
        }
        for (index, body) in bodies.enumerated() {
            ContentStore.shared.save(body.content, bookID: bookID, chapterID: index)
        }
        mergeRemoteDetail(bookID: bookID,
                          detail: RemoteDetail(title: book.title, author: book.author,
                                               intro: book.intro, chapters: summaries,
                                               coverURL: book.coverURL))
    }

    private func catalogBase(for book: Book) -> URL {
        if let source = sources.first(where: { $0.name == book.sourceName }) { return source.baseURL }
        if proxy.isValidCustomURL, let url = URL(string: proxy.customBaseURL) { return url }
        return proxy.wenku8BaseURL
    }

    /// Loads one chapter: first through the rule engine when applicable,
    /// falling back to the proxy catalog. Body is written to ContentStore.
    func loadChapter(bookID: UUID, chapterID: Int) async {
        guard let book = books.first(where: { $0.id == bookID }),
              let chapter = book.chapters.first(where: { $0.id == chapterID }),
              let url = chapter.url else { return }
        do {
            let content: String
            if let ruleSource = ruleSources.first(where: { $0.name == book.sourceName }) {
                content = try await RuleEngine(source: ruleSource).content(chapterURL: url)
            } else {
                let base = catalogBase(for: book)
                content = try await RemoteCatalog(proxyBaseURL: base).content(for: ChapterSummary(id: chapter.id, title: chapter.title, url: url))
            }
            guard !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw URLError(.cannotDecodeContentData)
            }
            ContentStore.shared.save(content, bookID: bookID, chapterID: chapterID)
            updateChapter(bookID: bookID, chapterID: chapterID, content: "", cached: true)
        } catch {
            searchError = "加载章节失败：\(error.localizedDescription)"
        }
    }

    /// Resolves a chapter's displayable body, reading from files on demand.
    func chapterContent(bookID: UUID, chapterID: Int) -> String {
        storedContent(bookID: bookID, chapterID: chapterID)
    }

    // MARK: - Cloudflare clearance

    /// Detects a Cloudflare block (403/503 from the chapter URL) and surfaces
    /// the challenge for the hidden WKWebView solver.
    func loadChapterWithCloudflareRetry(bookID: UUID, chapterID: Int) async {
        do {
            try await loadChapterOrThrow(bookID: bookID, chapterID: chapterID)
        } catch let urlError as URLError where urlError.code == .httpTooManyRedirects {
            if let chapter = books.first(where: { $0.id == bookID })?.chapters.first(where: { $0.id == chapterID }),
               let url = chapter.url {
                let solved = await solveCloudflare(url: url)
                if solved {
                    try? await loadChapterOrThrow(bookID: bookID, chapterID: chapterID)
                } else {
                    searchError = "Cloudflare 验证未通过，请稍后重试"
                }
            }
        } catch {
            searchError = "加载章节失败：\(error.localizedDescription)"
        }
    }

    /// Like loadChapter but throws instead of only setting searchError.
    private func loadChapterOrThrow(bookID: UUID, chapterID: Int) async throws {
        guard let book = books.first(where: { $0.id == bookID }),
              let chapter = book.chapters.first(where: { $0.id == chapterID }),
              let url = chapter.url else { return }
        let content: String
        if let ruleSource = ruleSources.first(where: { $0.name == book.sourceName }) {
            content = try await RuleEngine(source: ruleSource).content(chapterURL: url)
        } else {
            let base = catalogBase(for: book)
            content = try await RemoteCatalog(proxyBaseURL: base).content(for: ChapterSummary(id: chapter.id, title: chapter.title, url: url))
        }
        guard !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw URLError(.cannotDecodeContentData)
        }
        ContentStore.shared.save(content, bookID: bookID, chapterID: chapterID)
        updateChapter(bookID: bookID, chapterID: chapterID, content: "", cached: true)
    }

    private func solveCloudflare(url: URL) async -> Bool {
        await withCheckedContinuation { continuation in
            CloudflareHandler.shared.solve(url: url) { solved in
                continuation.resume(returning: solved)
            }
        }
    }

    func cacheRemaining(bookID: UUID) async {
        guard let book = books.first(where: { $0.id == bookID }) else { return }
        let pending = books.first(where: { $0.id == bookID })?.chapters.filter {
            !$0.isCached && $0.url != nil
        } ?? []
        // Limit concurrency so a source is not flooded while still making a large
        // cache operation responsive. Each chapter persists independently, so a
        // cancelled task safely resumes from the remaining chapters later.
        await withTaskGroup(of: Void.self) { group in
            var iterator = pending.makeIterator()
            var active = 0
            while active < 3, let chapter = iterator.next() {
                active += 1
                group.addTask { [weak self] in
                    guard let self else { return }
                    await self.loadChapter(bookID: bookID, chapterID: chapter.id)
                }
            }
            while active > 0 {
                if Task.isCancelled || isCachingPaused { group.cancelAll(); return }
                await group.next()
                active -= 1
                if let chapter = iterator.next() {
                    active += 1
                    group.addTask { [weak self] in
                        guard let self else { return }
                        await self.loadChapter(bookID: bookID, chapterID: chapter.id)
                    }
                }
            }
        }
        // Record that this book has an unfinished batch so it can be resumed.
        if let refreshed = books.first(where: { $0.id == bookID }),
           refreshed.chapters.contains(where: { !$0.isCached && $0.url != nil }) {
            pausedCacheBookIDs.insert(bookID)
        } else {
            pausedCacheBookIDs.remove(bookID)
        }
    }

    // MARK: - Cache pause / resume

    @Published private(set) var isCachingPaused = false
    @Published private(set) var pausedCacheBookIDs: Set<UUID> = []

    private var cacheTask: Task<Void, Never>?

    func pauseCaching() {
        isCachingPaused = true
        cacheTask?.cancel()
    }

    /// Resumes unfinished batches, one book at a time.
    func resumeCaching() {
        guard isCachingPaused else { return }
        isCachingPaused = false
        let ids = pausedCacheBookIDs
        pausedCacheBookIDs = []
        cacheTask = Task { [weak self] in
            for id in ids {
                guard let self, !Task.isCancelled else { return }
                await self.cacheRemaining(bookID: id)
            }
        }
    }

    /// Schedules a best-effort background refresh for unfinished remote chapters.
    /// The task is intentionally idempotent: it re-reads persisted books when iOS launches it.
    func scheduleCacheRefresh() {
        let request = BGProcessingTaskRequest(identifier: "com.storm.shijuan.ios.cache")
        request.requiresNetworkConnectivity = true
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    func requestCacheNotifications() async {
        let center = UNUserNotificationCenter.current()
        _ = try? await center.requestAuthorization(options: [.alert, .sound])
    }

    private func postCacheNotification(book: Book, completed: Bool) {
        let content = UNMutableNotificationContent()
        content.title = completed ? "缓存完成" : "缓存未完成"
        content.body = completed ? book.title : "《\(book.title)》仍有章节未缓存"
        content.sound = .default
        let request = UNNotificationRequest(identifier: "cache-\(book.id.uuidString)", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    func handleCacheRefresh(task: BGTask) {
        guard let task = task as? BGProcessingTask else { return }
        scheduleCacheRefresh()
        let work = Task { @MainActor in
            for book in books where !book.isDownloaded && book.sourceURL != nil {
                await cacheRemaining(bookID: book.id)
                if let refreshed = books.first(where: { $0.id == book.id }) {
                    postCacheNotification(book: refreshed, completed: refreshed.isDownloaded)
                }
            }
            task.setTaskCompleted(success: true)
        }
        task.expirationHandler = { work.cancel() }
    }

    func updateBook(_ book: Book) {
        guard let index = books.firstIndex(where: { $0.id == book.id }) else { return }
        books[index] = book
    }

    func updateChapter(bookID: UUID, chapterID: Int, content: String? = nil, cached: Bool? = nil) {
        guard let bookIndex = books.firstIndex(where: { $0.id == bookID }),
              let chapterIndex = books[bookIndex].chapters.firstIndex(where: { $0.id == chapterID }) else { return }
        if let content { books[bookIndex].chapters[chapterIndex].content = content }
        if let cached { books[bookIndex].chapters[chapterIndex].isCached = cached }
        let chapters = books[bookIndex].chapters
        // An empty online directory is not a successfully downloaded book.
        books[bookIndex].isDownloaded = !chapters.isEmpty && chapters.allSatisfy(\.isCached)
    }

    func addImportedBook(from url: URL) throws {
        let granted = url.startAccessingSecurityScopedResource()
        defer { if granted { url.stopAccessingSecurityScopedResource() } }
        let ext = url.pathExtension.lowercased()
        if ext == "pdf" {
            guard let document = PDFDocument(url: url) else { throw ImportError.invalidFormat }
            let text = (0..<document.pageCount).compactMap { document.page(at: $0)?.string }.joined(separator: "\n\n")
            guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw ImportError.empty }
            let title = url.deletingPathExtension().lastPathComponent
            let chapters = splitImportedText(text)
            books.insert(Book(title: title, author: "本地导入", progress: 0, isDownloaded: true,
                              chapters: chapters, isImported: true), at: 0)
            return
        }
        if ext == "epub" {
            let texts = try EpubImporter.importBook(from: url, title: url.deletingPathExtension().lastPathComponent)
            let chapters = texts.enumerated().map { idx, body in
                Chapter(id: idx, title: "第\(idx + 1)节", content: body, isCached: true)
            }
            books.insert(Book(title: url.deletingPathExtension().lastPathComponent,
                              author: "本地导入", progress: 0, isDownloaded: true,
                              chapters: chapters, isImported: true), at: 0)
            return
        }
        guard ext == "txt" || ext == "text" || ext.isEmpty else { throw ImportError.invalidFormat }
        let data = try Data(contentsOf: url)
        let text = decodeImportedText(data)
        let title = url.deletingPathExtension().lastPathComponent
        let regex = try NSRegularExpression(pattern: "(?m)^\\s*((第[0-9一二三四五六七八九十百千万]+[章回节卷集幕篇部][^\\r\\n]{0,40})|(Chapter\\s*\\d+[^\\r\\n]{0,40})|(序章|楔子|尾声|后记|番外)[^\\r\\n]{0,40})\\s*$")
        let ns = text as NSString
        let matches = regex.matches(in: text, range: NSRange(location: 0, length: ns.length))
        var chapters: [Chapter] = []
        if matches.count >= 2 {
            for index in matches.indices {
                let start = matches[index].range.location
                let end = index + 1 < matches.count ? matches[index + 1].range.location : ns.length
                let name = ns.substring(with: matches[index].range).trimmingCharacters(in: .whitespacesAndNewlines)
                let body = ns.substring(with: NSRange(location: start, length: end - start)).trimmingCharacters(in: .whitespacesAndNewlines)
                chapters.append(Chapter(id: index, title: name.isEmpty ? "第\(index + 1)章" : name, content: body, isCached: true))
            }
        } else if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            let chunkSize = 7000
            var offset = 0
            while offset < ns.length {
                let end = min(offset + chunkSize, ns.length)
                chapters.append(Chapter(id: chapters.count, title: "第\(chapters.count + 1)部分", content: ns.substring(with: NSRange(location: offset, length: end - offset)), isCached: true))
                offset = end
            }
        }
        guard !chapters.isEmpty else { throw ImportError.empty }
        books.insert(Book(title: title, author: "本地导入", progress: 0, isDownloaded: true,
                          chapters: chapters, isImported: true), at: 0)
    }

    private func decodeImportedText(_ data: Data) -> String {
        if let utf8 = String(data: data, encoding: .utf8) { return utf8 }
        let encoding = CFStringConvertEncodingToNSStringEncoding(
            CFStringConvertIANACharSetNameToEncoding("GB18030" as CFString)
        )
        if let decoded = String(data: data, encoding: String.Encoding(rawValue: encoding)) {
            return decoded
        }
        return String(decoding: data, as: UTF8.self)
    }

    private func splitImportedText(_ text: String) -> [Chapter] {
        let ns = text as NSString
        let regex = try? NSRegularExpression(pattern: "(?m)^\\s*((第[0-9一二三四五六七八九十百千万]+[章回节卷集幕篇部][^\\r\\n]{0,40})|(Chapter\\s*\\d+[^\\r\\n]{0,40})|(序章|楔子|尾声|后记|番外)[^\\r\\n]{0,40})\\s*$")
        let matches = regex?.matches(in: text, range: NSRange(location: 0, length: ns.length)) ?? []
        if matches.count >= 2 {
            return matches.indices.map { index in
                let start = matches[index].range.location
                let end = index + 1 < matches.count ? matches[index + 1].range.location : ns.length
                let name = ns.substring(with: matches[index].range).trimmingCharacters(in: .whitespacesAndNewlines)
                return Chapter(id: index, title: name.isEmpty ? "第\(index + 1)章" : name,
                               content: ns.substring(with: NSRange(location: start, length: end - start)).trimmingCharacters(in: .whitespacesAndNewlines), isCached: true)
            }
        }
        let chunkSize = 7000
        return stride(from: 0, to: ns.length, by: chunkSize).map { offset in
            let end = min(offset + chunkSize, ns.length)
            return Chapter(id: offset / chunkSize, title: "第\(offset / chunkSize + 1)部分",
                           content: ns.substring(with: NSRange(location: offset, length: end - offset)), isCached: true)
        }
    }

    enum ImportError: LocalizedError {
        case empty
        case invalidFormat
        var errorDescription: String? {
            switch self {
            case .empty: return "文件中没有可读文本"
            case .invalidFormat: return "仅支持 TXT、纯文本、PDF 和 EPUB 文件"
            }
        }
    }
}
