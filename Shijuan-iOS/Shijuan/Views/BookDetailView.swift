import SwiftUI

struct BookDetailView: View {
    @EnvironmentObject private var state: AppState
    let book: Book

    private var cachedCount: Int { book.chapters.filter(\.isCached).count }

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text(book.title).font(.title2.bold())
                    Text(book.author).foregroundStyle(.secondary)
                    ProgressView(value: book.progress)
                    Text("已缓存 \(cachedCount)/\(book.chapters.count) 章")
                        .font(.caption).foregroundStyle(.secondary)
                }.padding(.vertical, 8)
                Button("缓存未完成章节") { cacheRemaining() }
            }
            Section("目录") {
                ForEach(book.chapters) { chapter in
                    NavigationLink {
                        ReaderView(chapter: chapter)
                    } label: {
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
    }

    private func cacheRemaining() {
        guard let index = state.books.firstIndex(of: book) else { return }
        state.books[index].chapters = state.books[index].chapters.map { chapter in
            var copy = chapter
            copy.isCached = true
            return copy
        }
        state.books[index].isDownloaded = true
    }
}
