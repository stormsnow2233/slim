import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var state: AppState

    var body: some View {
        TabView {
            ShelfView()
                .tabItem { Label("书架", systemImage: "books.vertical") }
            SearchView()
                .tabItem { Label("搜索", systemImage: "magnifyingglass") }
            SettingsView()
                .tabItem { Label("设置", systemImage: "gearshape") }
        }
        .preferredColorScheme(state.theme == .miuix ? .light : nil)
    }
}

private struct ShelfView: View {
    @EnvironmentObject private var state: AppState

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
                            BookDetailView(book: book)
                        } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                Text(book.title).font(.headline)
                                Text(book.author).font(.subheadline).foregroundStyle(.secondary)
                                ProgressView(value: book.progress)
                            }
                            .padding(.vertical, 6)
                        }
                    }
                }
            }
            .navigationTitle("书架")
        }
    }
}

private struct SearchView: View {
    @EnvironmentObject private var state: AppState

    var body: some View {
        NavigationStack {
            List(state.filteredBooks) { book in
                VStack(alignment: .leading) {
                    Text(book.title).font(.headline)
                    Text(book.author).foregroundStyle(.secondary)
                }
            }
            .searchable(text: $state.searchText, prompt: "搜索书名或作者")
            .navigationTitle("搜索")
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
                Section("关于") {
                    LabeledContent("应用", value: "拾卷 iOS")
                    LabeledContent("版本", value: "0.1.0")
                }
            }
            .navigationTitle("设置")
        }
    }
}
