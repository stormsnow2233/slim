import SwiftUI

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
                        VStack(alignment: .leading) {
                            Text(comic.name).font(.headline)
                            Text(comic.author).font(.subheadline).foregroundStyle(.secondary)
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

    private var jmBase: URL { state.proxy.jmBaseURL }

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
                        if let url = URL(string: imageURL) {
                            AsyncImage(url: url) { phase in
                                switch phase {
                                case .success(let image):
                                    image.resizable().scaledToFit()
                                case .failure:
                                    Text("图片加载失败").font(.caption).foregroundStyle(.secondary)
                                        .frame(maxWidth: .infinity).padding()
                                default:
                                    ProgressView().frame(maxWidth: .infinity).padding()
                                }
                            }
                        }
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
