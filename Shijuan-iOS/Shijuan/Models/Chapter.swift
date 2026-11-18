import Foundation

struct Chapter: Identifiable, Hashable {
    let id: Int
    let title: String
    var isCached: Bool = false
}
