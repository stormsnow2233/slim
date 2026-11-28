import Foundation

struct ComicSourceEntry: Identifiable {
    var id: String { name }
    let name: String
    let baseURL: String
}

enum ComicCatalog {
    static let sources = [
        ComicSourceEntry(name: "漫画柜", baseURL: "https://m.manhuagui.com"),
        ComicSourceEntry(name: "包子漫画", baseURL: "https://cn.webmota.com"),
        ComicSourceEntry(name: "动漫屋", baseURL: "https://m.dm5.com"),
        ComicSourceEntry(name: "漫画人", baseURL: "https://www.manhuaren.com"),
        ComicSourceEntry(name: "极速漫画", baseURL: "https://m.1kkk.com"),
    ]
}
