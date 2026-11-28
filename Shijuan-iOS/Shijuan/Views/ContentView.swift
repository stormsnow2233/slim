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
    @State private var selectedTab = HomeTab.shelf

    var body: some View {
        TabView(selection: $selectedTab) {
            ShelfView(showingImporter: $showingImporter, importError: $importError, exportDocument: $exportDocument, showingExporter: $showingExporter)
                .tag(HomeTab.shelf)
            SearchView()
                .tag(HomeTab.search)
            ComicSearchView()
                .tag(HomeTab.comics)
            SettingsView()
                .tag(HomeTab.settings)
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .safeAreaInset(edge: .bottom, spacing: 0) {
            HStack(spacing: 0) {
                ForEach(HomeTab.allCases) { tab in
                    Button {
                        guard selectedTab != tab else { return }
                        var transaction = Transaction()
                        transaction.disablesAnimations = true
                        withTransaction(transaction) { selectedTab = tab }
                    } label: {
                        VStack(spacing: 4) {
                            Image(systemName: tab.icon)
                                .font(.system(size: 18, weight: selectedTab == tab ? .semibold : .regular))
                            Text(tab.title)
                                .font(.system(size: 10, weight: selectedTab == tab ? .semibold : .regular))
                        }
                        .foregroundStyle(selectedTab == tab ? Color.accentColor : Color.secondary)
                        .frame(maxWidth: .infinity)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(tab.title)
                    .accessibilityAddTraits(selectedTab == tab ? .isSelected : [])
                }
            }
            .padding(.top, 8)
            .padding(.bottom, 5)
            .background(.regularMaterial)
            .overlay(alignment: .top) { Divider() }
        }
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

private enum HomeTab: String, CaseIterable, Identifiable {
    case shelf, search, comics, settings

    var id: Self { self }
    var title: String {
        switch self {
        case .shelf: return "书架"
        case .search: return "搜索"
        case .comics: return "漫画"
        case .settings: return "设置"
        }
    }
    var icon: String {
        switch self {
        case .shelf: return "books.vertical"
        case .search: return "magnifyingglass"
        case .comics: return "photo.on.rectangle.angled"
        case .settings: return "gearshape"
        }
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
            state.addRemoteBook(RemoteBook(title: book.title, author: book.author, url: url,
                                           sourceName: book.sourceName, coverURL: book.coverURL, intro: book.intro))
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
                Section("搜索结果") {
                    if state.isSearching { ProgressView("正在搜索…") }
                    ForEach(state.remoteResults) { book in
                        RemoteBookRow(book: book)
                    }
                    if let error = state.searchError { Text(error).foregroundStyle(.red) }
                    if !state.isSearching && state.remoteResults.isEmpty && state.searchError == nil {
                        Text("输入书名或作者后搜索").foregroundStyle(.secondary)
                    }
                }
            }
            .searchable(text: $state.searchText, prompt: "搜索书名或作者")
            .onSubmit(of: .search) { state.searchRemote() }
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
                Section("外观") {
                    Text("采用 iOS 原生界面，并跟随系统明暗模式。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Section("书源代理") {
                    TextField("自定义代理地址（可选）", text: $state.proxy.customBaseURL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Text("Wenku8：\(state.proxy.wenku8BaseURL.host ?? "未配置")")
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
                    Text("支持 TXT/PDF/EPUB 导入、规则书源、在线阅读与 AniList 漫画目录。")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("设置")
        }
    }
}
