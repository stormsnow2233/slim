import SwiftUI
import UIKit

struct ComicSearchView: View {
    @State private var keyword = ""
    @State private var results: [Comic] = []
    @State private var isShowingSearchResults = false
    @State private var isSearching = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List {
                if isSearching { ProgressView("正在加载…") }
                if let error { Text(error).foregroundStyle(.red) }
                if !isShowingSearchResults && !results.isEmpty {
                    Section("热门推荐") {
                        comicRows
                    }
                } else {
                    Section("搜索结果") {
                        comicRows
                    }
                }
                if !isSearching && error == nil && results.isEmpty {
                    Text(isShowingSearchResults ? "没有找到结果" : "暂时没有推荐内容")
                        .foregroundStyle(.secondary)
                }
                Section {
                    Text("作品信息来自 AniList。此目录仅提供作品元数据与条目链接，不提供章节阅读。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .searchable(text: $keyword, prompt: "搜索漫画")
            .navigationTitle("漫画")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "刷新" : "搜索") {
                        Task { await loadCatalog() }
                    }
                    .disabled(isSearching)
                }
            }
            .task {
                if results.isEmpty { await loadCatalog() }
            }
        }
    }

    @ViewBuilder
    private var comicRows: some View {
        ForEach(results) { comic in
            NavigationLink { ComicDetailView(comic: comic) } label: {
                HStack(spacing: 12) {
                    if !comic.cover.isEmpty {
                        ComicImageView(url: comic.cover)
                            .frame(width: 64, height: 88)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                    VStack(alignment: .leading, spacing: 5) {
                        Text(comic.name).font(.headline)
                        Text(comic.sourceName)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        if !comic.intro.isEmpty {
                            Text(comic.intro)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(2)
                        }
                    }
                }
            }
        }
    }

    @MainActor
    private func loadCatalog() async {
        let query = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        isSearching = true
        error = nil
        isShowingSearchResults = !query.isEmpty
        results = []
        do {
            results = query.isEmpty
                ? try await AniListComicCatalog.recommendations()
                : try await AniListComicCatalog.search(keyword: query)
        } catch {
            self.error = "加载失败：\(error.localizedDescription)"
        }
        isSearching = false
    }
}

struct ComicDetailView: View {
    let comic: Comic

    var body: some View {
        List {
            Section {
                HStack(alignment: .top, spacing: 14) {
                    if !comic.cover.isEmpty {
                        ComicImageView(url: comic.cover)
                            .frame(width: 96, height: 132)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        Text(comic.name).font(.title3.bold())
                        Text(comic.sourceName)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 4)
                if !comic.intro.isEmpty {
                    Text(comic.intro)
                        .font(.body)
                        .textSelection(.enabled)
                }
            }
            Section {
                if let url = URL(string: comic.url) {
                    Link("在 AniList 打开作品条目", destination: url)
                }
                Text("AniList 提供作品目录与元数据；章节及阅读请使用作品授权发行渠道。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle(comic.name)
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct ComicImageView: View {
    let url: String
    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFit()
            } else if failed {
                Image(systemName: "photo")
                    .foregroundStyle(.secondary)
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
            do {
                let (data, response) = try await URLSession.shared.data(for: request)
                guard let http = response as? HTTPURLResponse,
                      (200..<300).contains(http.statusCode),
                      let loadedImage = UIImage(data: data) else {
                    failed = true
                    return
                }
                image = loadedImage
            } catch {
                failed = true
            }
        }
    }
}
