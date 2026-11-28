import SwiftUI
import WebKit

struct ComicSearchView: View {
    var body: some View {
        NavigationStack {
            List {
                Section("Android 版现有漫画源") {
                    ForEach(ComicCatalog.sources) { source in
                        NavigationLink {
                            ComicBrowserView(source: source)
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(source.name)
                                    .font(.headline)
                                Text(URL(string: source.baseURL)?.host ?? source.baseURL)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            .padding(.vertical, 4)
                        }
                    }
                }
                Section {
                    Text("选择来源后会在应用内打开源站。原生章节抓取与图片阅读尚未接入。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("漫画")
        }
    }
}

private struct ComicBrowserView: View {
    let source: ComicSourceEntry
    @State private var reloadID = UUID()

    var body: some View {
        Group {
            if let url = URL(string: source.baseURL) {
                ComicWebView(url: url)
                    .id(reloadID)
            } else {
                Text("来源地址无效")
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle(source.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    reloadID = UUID()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .accessibilityLabel("重新加载")
            }
        }
    }
}

private struct ComicWebView: UIViewRepresentable {
    let url: URL

    func makeUIView(context: Context) -> WKWebView {
        let webView = WKWebView()
        webView.allowsBackForwardNavigationGestures = true
        webView.load(URLRequest(url: url))
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
