import SwiftUI
import UIKit

struct ReaderView: View {
    let bookID: UUID
    let chapterID: Int
    @EnvironmentObject private var state: AppState
    @State private var fontSize: Double = 18
    @State private var lineSpacing: Double = 8
    @State private var readerTheme = 0
    @State private var showProgress = false
    @State private var bodyText = ""

    private var readerBackground: Color {
        switch readerTheme { case 1: return Color(red: 0.96, green: 0.91, blue: 0.78); case 2: return .black; default: return Color(uiColor: .systemBackground) }
    }
    private var readerForeground: Color { readerTheme == 2 ? .white : .primary }

    private var book: Book? { state.books.first(where: { $0.id == bookID }) }
    private var chapter: Chapter? { book?.chapters.first(where: { $0.id == chapterID }) }

    var body: some View {
        ScrollView {
            Text(bodyText.isEmpty ? "本章暂无正文。" : bodyText)
                .font(.system(size: fontSize))
                .lineSpacing(lineSpacing)
                .foregroundStyle(readerForeground)
                .textSelection(.enabled)
                .padding()
        }
        .background(readerBackground.ignoresSafeArea())
        .onAppear {
            updateProgress()
            bodyText = state.chapterContent(bookID: bookID, chapterID: chapterID)
            if bodyText.isEmpty, chapter?.url != nil {
                Task {
                    await state.loadChapter(bookID: bookID, chapterID: chapterID)
                    bodyText = state.chapterContent(bookID: bookID, chapterID: chapterID)
                }
            }
        }
        .navigationTitle(chapter?.title ?? "阅读")
        .overlay(alignment: .bottom) {
            if showProgress, let book, let index = book.chapters.firstIndex(where: { $0.id == chapterID }) {
                Text("第 \(index + 1)/\(book.chapters.count) 章")
                    .font(.caption).padding(6).background(.ultraThinMaterial).clipShape(Capsule()).padding(.bottom, 8)
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Slider(value: $fontSize, in: 14...28, step: 1) { Text("字号") }
                    Slider(value: $lineSpacing, in: 0...20, step: 1) { Text("行距") }
                    Picker("背景", selection: $readerTheme) {
                        Text("系统").tag(0); Text("护眼").tag(1); Text("夜间").tag(2)
                    }
                    Toggle("显示阅读进度", isOn: $showProgress)
                    Button("恢复默认") { fontSize = 18; lineSpacing = 8; readerTheme = 0 }
                } label: { Image(systemName: "textformat.size") }
            }
        }
    }

    private func updateProgress() {
        guard let book, let index = book.chapters.firstIndex(where: { $0.id == chapterID }), !book.chapters.isEmpty else { return }
        var updated = book
        updated.progress = Double(index + 1) / Double(book.chapters.count)
        state.updateBook(updated)
    }
}
