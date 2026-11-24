import Foundation

struct ProxyConfiguration: Codable {
    var wenku8BaseURL = URL(string: "https://wenku8-proxy.pages.dev")!
    var jmBaseURL = URL(string: "https://jm-proxy.pages.dev")!
    var customBaseURL = ""

    var isValidCustomURL: Bool {
        guard let url = URL(string: customBaseURL.trimmingCharacters(in: .whitespacesAndNewlines)) else { return false }
        return url.scheme == "https" || url.scheme == "http"
    }
}
