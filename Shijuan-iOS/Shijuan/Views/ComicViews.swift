import SwiftUI
import UIKit

struct ComicSearchView: View {
    @EnvironmentObject private var state: AppState
    @State private var keyword = ""
    @State private var results: [Comic] = []
    @State private var isSearching = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List {
                if isSearching { ProgressView("正在搜索…") }
                if let error { Text(error).foregroundStyle(.red) }
                ForEach(results) { comic in
                    NavigationLink { ComicDetailView(comic: comic) } label: {
                        HStack(spacing: 12) {
                            if !comic.cover.isEmpty {
                                ComicImageView(
                                    url: comic.cover,
                                    referer: comic.apiBase.map { $0.hasSuffix("/") ? $0 : $0 + "/" }
                                        ?? (state.proxy.jmBaseURL.absoluteString + "/")
                                )
                                    .frame(width: 64, height: 88)
                                    .clipShape(RoundedRectangle(cornerRadius: 8))
                            }
                            VStack(alignment: .leading, spacing: 5) {
                                Text(comic.name).font(.headline)
                                Text(comic.author.isEmpty ? comic.sourceName : comic.author)
                                    .font(.subheadline).foregroundStyle(.secondary)
                                if !comic.intro.isEmpty {
                                    Text(comic.intro).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                                }
                            }
                        }
                    }
                }
                if !isSearching && error == nil && results.isEmpty && !keyword.isEmpty {
                    Text("没有找到结果").foregroundStyle(.secondary)
                }
            }
            .searchable(text: $keyword, prompt: "搜索漫画")
            .navigationTitle("漫画")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("搜索") {
                        let query = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !query.isEmpty else { return }
                        isSearching = true
                        error = nil
                        Task {
                            do {
                                results = try await ComicCatalog.search(keyword: query, base: state.proxy.jmBaseURL)
                            } catch {
                                self.error = "搜索失败：\(error.localizedDescription)"
                            }
                            isSearching = false
                        }
                    }.disabled(keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isSearching)
                }
            }
        }
    }
}

struct ComicDetailView: View {
    @EnvironmentObject private var state: AppState
    let comic: Comic
    @State private var chapters: [ComicChapter] = []
    @State private var isLoading = false
    @State private var error: String?

    private var jmBase: URL {
        guard let apiBase = comic.apiBase, let url = URL(string: apiBase) else { return state.proxy.jmBaseURL }
        return url
    }

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 6) {
                    Text(comic.name).font(.title2.bold())
                    if !comic.author.isEmpty { Text(comic.author).foregroundStyle(.secondary) }
                    if !comic.intro.isEmpty { Text(comic.intro).font(.caption).foregroundStyle(.secondary) }
                }
            }
            Section("章节") {
                if isLoading { ProgressView("正在加载章节…") }
                if let error { Text(error).foregroundStyle(.red) }
                ForEach(chapters) { chapter in
                    NavigationLink { ComicReaderView(chapter: chapter, base: jmBase) } label: {
                        Text(chapter.name)
                    }
                }
            }
        }
        .navigationTitle(comic.name)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard chapters.isEmpty else { return }
            isLoading = true
            error = nil
            do {
                chapters = try await ComicCatalog.chapters(comic: comic, base: jmBase)
            } catch let chaptersError {
                error = "加载章节失败：\(chaptersError.localizedDescription)"
            }
            isLoading = false
        }
    }
}

struct ComicReaderView: View {
    let chapter: ComicChapter
    let base: URL
    @State private var pages: ComicPages?
    @State private var error: String?

    var body: some View {
        ScrollView {
            if let pages {
                LazyVStack(spacing: 4) {
                    ForEach(Array(pages.images.enumerated()), id: \.offset) { _, imageURL in
                        ComicImageView(url: imageURL, referer: pages.referer)
                    }
                }
            } else if let error {
                Text(error).foregroundStyle(.red).padding()
            } else {
                ProgressView("正在加载图片…").padding()
            }
        }
        .navigationTitle(chapter.name)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if pages == nil {
                do { pages = try await ComicCatalog.pages(chapter: chapter, base: base) }
                catch let pagesError { error = "加载图片失败：\(pagesError.localizedDescription)" }
            }
        }
    }
}

private struct ComicImageView: View {
    let url: String
    let referer: String
    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFit()
            } else if failed {
                Text("图片加载失败").font(.caption).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task(id: url) {
            image = nil
            failed = false
            guard let imageURL = URL(string: url) else {
                failed = true
                return
            }
            var request = URLRequest(url: imageURL)
            request.timeoutInterval = 25
            request.setValue("Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                             forHTTPHeaderField: "User-Agent")
            request.setValue(referer, forHTTPHeaderField: "Referer")
            request.setValue("1", forHTTPHeaderField: "X-JM")
            do {
                let (data, response) = try await URLSession.shared.data(for: request)
                guard let http = response as? HTTPURLResponse,
                      (200..<300).contains(http.statusCode),
                      let loadedImage = UIImage(data: data) else {
                    failed = true
                    return
                }
                image = JmImageDescrambler.descramble(loadedImage, imageURL: imageURL)
            } catch {
                failed = true
            }
        }
    }
}
