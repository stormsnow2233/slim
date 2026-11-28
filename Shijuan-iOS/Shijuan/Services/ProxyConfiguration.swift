import Foundation

struct ProxyConfiguration: Codable {
    var wenku8BaseURL = URL(string: "https://wenku8-proxy.us.ci")!
    var customBaseURL = ""

    var isValidCustomURL: Bool {
        guard let url = URL(string: customBaseURL.trimmingCharacters(in: .whitespacesAndNewlines)) else { return false }
        return (url.scheme == "https" || url.scheme == "http") && url.host != nil
    }

    mutating func migrateDeprecatedDefaults() {
        if ["wenku8-proxy.pages.dev", "wenku8-proxy.storm-snow.workers.dev"].contains(wenku8BaseURL.host ?? "") {
            wenku8BaseURL = URL(string: "https://wenku8-proxy.us.ci")!
        }
    }
}
