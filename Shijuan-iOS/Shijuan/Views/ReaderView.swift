import SwiftUI

struct ReaderView: View {
    let chapter: Chapter
    @State private var fontSize: Double = 18

    var body: some View {
        ScrollView {
            Text("这是阅读器占位内容。后续将接入 Android 版的书源解析、章节正文缓存和阅读进度同步。\n\n当前章节：\(chapter.title)")
                .font(.system(size: fontSize))
                .lineSpacing(8)
                .padding()
        }
        .navigationTitle(chapter.title)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Slider(value: $fontSize, in: 14...28, step: 1) {
                        Text("字号")
                    }
                } label: {
                    Image(systemName: "textformat.size")
                }
            }
        }
    }
}
