import SwiftUI

struct BookDetailView: View {
    @EnvironmentObject private var state: AppState
    let bookID: UUID
    @State private var isCaching = false
    @State private var isLoadingDetail = false

    private var book: Book? { state.books.first(where: { $0.id == bookID }) }

    var body: some View {
        Group {
            if let book {
                List {
                    Section {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(book.title).font(.title2.bold())
                            Text(book.author).foregroundStyle(.secondary)
                            ProgressView(value: book.progress)
                            Text("已缓存 \(book.chapters.filter(\.isCached).count)/\(book.chapters.count) 章")
                                .font(.caption).foregroundStyle(.secondary)
                        }.padding(.vertical, 8)
                        Button { cacheRemaining() } label: {
                            HStack {
                                Text(isCaching ? "正在缓存…" : "缓存未完成章节")
                                Spacer()
                                if isCaching { ProgressView() }
                            }
                        }.disabled(isCaching || book.chapters.allSatisfy(\.isCached))
                        if isCaching {
                            Button(role: .destructive) { state.pauseCaching() } label: {
                                Text("暂停缓存")
                            }
                        }
                        if state.isCachingPaused && !state.pausedCacheBookIDs.isEmpty {
                            Button { state.resumeCaching() } label: {
                                Text("恢复缓存（\(state.pausedCacheBookIDs.count) 本未完成）")
                            }
                        }
                        if book.sourceURL != nil && book.chapters.isEmpty {
                            Button {
                                isLoadingDetail = true
                                Task {
                                    await state.loadDetailForBook(bookID: book.id)
                                    isLoadingDetail = false
                                }
                            } label: {
                                HStack {
                                    Text(isLoadingDetail ? "正在加载目录…" : "加载在线目录")
                                    Spacer()
                                    if isLoadingDetail { ProgressView() }
                                }
                            }.disabled(isLoadingDetail)
                        }
                    }
                    Section("目录") {
                        ForEach(book.chapters) { chapter in
                            NavigationLink { ReaderView(bookID: book.id, chapterID: chapter.id) } label: {
                                HStack {
                                    Text(chapter.title)
                                    Spacer()
                                    if chapter.isCached { Image(systemName: "checkmark.circle.fill").foregroundStyle(.green) }
                                }
                            }
                        }
                    }
                }
                .navigationTitle("详情")
                .navigationBarTitleDisplayMode(.inline)
                .task(id: book.id) {
                    if book.sourceURL != nil && book.chapters.isEmpty {
                        isLoadingDetail = true
                        await state.loadDetailForBook(bookID: book.id)
                        isLoadingDetail = false
                    }
                }
            } else {
                Text("书籍不存在").foregroundStyle(.secondary)
            }
        }
    }

    private func cacheRemaining() {
        guard let current = book else { return }
        isCaching = true
        Task {
            await state.cacheRemaining(bookID: current.id)
            isCaching = false
        }
    }
}
