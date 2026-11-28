import SwiftUI
import UniformTypeIdentifiers

private struct TextExportDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.plainText] }
    var text: String
    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws {
        text = String(data: configuration.file.regularFileContents ?? Data(), encoding: .utf8) ?? ""
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

struct ContentView: View {
    @EnvironmentObject private var state: AppState
    @State private var showingImporter = false
    @State private var importError: String?
    @State private var exportDocument: TextExportDocument?
    @State private var showingExporter = false
    @State private var didRequestNotifications = false

    var body: some View {
        TabView {
            ShelfView(showingImporter: $showingImporter, importError: $importError, exportDocument: $exportDocument, showingExporter: $showingExporter)
                .tabItem { Label("书架", systemImage: "books.vertical") }
            SearchView()
                .tabItem { Label("搜索", systemImage: "magnifyingglass") }
            ComicSearchView()
                .tabItem { Label("漫画", systemImage: "photo.on.rectangle.angled") }
            SettingsView()
                .tabItem { Label("设置", systemImage: "gearshape") }
        }
        .preferredColorScheme(state.theme == .miuix ? .light : nil)
        .background(CloudflareSolverHost())
        .task {
            guard !didRequestNotifications else { return }
            didRequestNotifications = true
            await state.requestCacheNotifications()
        }
        .fileExporter(isPresented: $showingExporter, document: exportDocument, contentType: .plainText, defaultFilename: "拾卷导出.txt") { _ in }
        .fileImporter(isPresented: $showingImporter, allowedContentTypes: [.plainText, .pdf, UTType(filenameExtension: "epub") ?? .data], allowsMultipleSelection: false) { result in
            guard case .success(let urls) = result, let url = urls.first else { return }
            do { try state.addImportedBook(from: url) }
            catch { importError = error.localizedDescription }
        }
        .alert("导入失败", isPresented: Binding(get: { importError != nil }, set: { if !$0 { importError = nil } })) {
            Button("好", role: .cancel) { importError = nil }
        } message: { Text(importError ?? "未知错误") }
    }
}

private struct CloudflareSolverHost: View {
    @ObservedObject private var handler = CloudflareHandler.shared

    var body: some View {
        Group {
            if let pending = handler.pending {
                CloudflareSolverView(url: pending.url, timeout: 20) { solved in
                    handler.handleNavigationCompletion(success: solved)
                }
                .frame(width: 1, height: 1)
                .opacity(0.01)
                .accessibilityHidden(true)
            }
        }
    }
}

private struct ShelfView: View {
    @EnvironmentObject private var state: AppState
    @Binding var showingImporter: Bool
    @Binding var importError: String?
    @Binding var exportDocument: TextExportDocument?
    @Binding var showingExporter: Bool

    var body: some View {
        NavigationStack {
            List {
                if state.books.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "books.vertical")
                            .font(.system(size: 36))
                            .foregroundStyle(.secondary)
                        Text("书架为空").font(.headline)
                        Text("从搜索页添加一本书吧").foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 40)
                } else {
                    ForEach(state.books) { book in
                        NavigationLink {
                            BookDetailView(bookID: book.id)
                        } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                Text(book.title).font(.headline)
                                Text(book.author).font(.subheadline).foregroundStyle(.secondary)
                                ProgressView(value: book.progress)
                            }
                            .padding(.vertical, 6)
                        }
                        .swipeActions {
                            Button(role: .destructive) {
                                state.deleteBook(book)
                            } label: {
                                Label("删除", systemImage: "trash")
                            }
                        }
                    }
                }
            }
            .navigationTitle("书架")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { showingImporter = true } label: { Image(systemName: "square.and.arrow.down") }
                    Menu {
                        ForEach(state.books) { book in
                            Button(book.title) {
                                let text = book.chapters.map { chapter in
                                    let body = state.chapterContent(bookID: book.id, chapterID: chapter.id)
                                    return "\(chapter.title)\n\(body)"
                                }.joined(separator: "\n\n")
                                exportDocument = TextExportDocument(text: text)
                                showingExporter = true
                            }
                        }
                    } label: { Image(systemName: "square.and.arrow.up") }
                }
            }
        }
    }
}

private struct RemoteBookRow: View {
    @EnvironmentObject private var state: AppState
    let book: Book
    var body: some View {
        Button {
            guard let url = book.sourceURL else { return }
            state.addRemoteBook(RemoteBook(title: book.title, author: book.author, url: url, sourceName: book.sourceName))
        } label: {
            VStack(alignment: .leading) {
                Text(book.title).font(.headline)
                Text(book.author).foregroundStyle(.secondary)
            }
        }
    }
}

private struct SearchView: View {
    @EnvironmentObject private var state: AppState

    var body: some View {
        NavigationStack {
            List {
                Section("书架结果") {
                    ForEach(state.filteredBooks) { book in
                        NavigationLink { BookDetailView(bookID: book.id) } label: {
                            VStack(alignment: .leading) {
                                Text(book.title).font(.headline)
                                Text(book.author).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                Section("在线书源") {
                    if state.isSearching { ProgressView("正在搜索…") }
                    ForEach(state.remoteResults) { book in
                        RemoteBookRow(book: book)
                    }
                    if let error = state.searchError { Text(error).foregroundStyle(.red) }
                }
            }
            .searchable(text: $state.searchText, prompt: "搜索书名或作者")
            .navigationTitle("搜索")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("搜索") { state.searchRemote() }.disabled(state.searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || state.isSearching)
                }
            }
        }
    }
}

private struct AddSourceRow: View {
    @EnvironmentObject private var state: AppState
    @State private var name = ""
    @State private var url = ""
    @State private var error: String?

    var body: some View {
        VStack(spacing: 8) {
            TextField("名称", text: $name)
            TextField("https://example.com", text: $url).textInputAutocapitalization(.never).autocorrectionDisabled()
            Button("添加书源") {
                do { try state.addSource(name: name, baseURL: url); name = ""; url = "" }
                catch let addError { error = addError.localizedDescription }
            }
            if let error { Text(error).font(.caption).foregroundStyle(.red) }
        }
    }
}

private struct AddRuleSourceRow: View {
    @EnvironmentObject private var state: AppState
    @State private var json = ""
    @State private var error: String?

    var body: some View {
        VStack(spacing: 8) {
            Text("粘贴书源 JSON（支持单个对象或数组）")
                .font(.caption).foregroundStyle(.secondary)
            TextEditor(text: $json)
                .font(.system(size: 12, design: .monospaced))
                .frame(minHeight: 80)
                .border(.quaternary)
            Button("导入规则书源") {
                do { try state.addRuleSourceJSON(json); json = ""; error = nil }
                catch let importError { error = importError.localizedDescription }
            }
            .disabled(json.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            if let error { Text(error).font(.caption).foregroundStyle(.red) }
        }
    }
}

private struct SettingsView: View {
    @EnvironmentObject private var state: AppState

    var body: some View {
        NavigationStack {
            Form {
                Section("主题") {
                    Picker("界面风格", selection: $state.theme) {
                        ForEach(AppState.ThemeMode.allCases) { mode in
                            Text(mode.rawValue).tag(mode)
                        }
                    }
                }
                Section("书源代理") {
                    TextField("自定义代理地址（可选）", text: $state.proxy.customBaseURL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Text("Wenku8：\(state.proxy.wenku8BaseURL.host ?? "未配置")")
                        .font(.caption).foregroundStyle(.secondary)
                    Text("JM：\(state.proxy.jmBaseURL.host ?? "未配置")")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Section("自定义书源") {
                    ForEach(state.sources) { source in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(source.name)
                                Text(source.baseURL.absoluteString).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Toggle("启用", isOn: Binding(
                                get: { source.enabled },
                                set: { enabled in
                                    state.setSourceEnabled(source.id, enabled: enabled)
                                }
                            ))
                            .labelsHidden()
                        }
                        .swipeActions {
                            Button(role: .destructive) { state.removeSource(source) } label: { Label("删除", systemImage: "trash") }
                        }
                    }
                    AddSourceRow()
                }
                Section("规则书源") {
                    ForEach(state.ruleSources) { source in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(source.name)
                                Text(source.bases.first ?? "").font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Toggle("启用", isOn: Binding(
                                get: { source.enabled },
                                set: { enabled in state.setRuleSourceEnabled(source.id, enabled: enabled) }
                            )).labelsHidden()
                        }
                        .swipeActions {
                            Button(role: .destructive) { state.removeRuleSource(source) } label: { Label("删除", systemImage: "trash") }
                        }
                    }
                    AddRuleSourceRow()
                }
                Section("关于") {
                    LabeledContent("应用", value: "拾卷 iOS")
                    LabeledContent("版本", value: "0.2.0")
                    Text("支持 TXT/PDF/EPUB 导入、规则书源、在线阅读、漫画和章节缓存。")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("设置")
        }
    }
}
