import Foundation
import WebKit
import SwiftUI

/// Best-effort Cloudflare clearance: loads the challenge page in a hidden
/// WKWebView, waits for the JS challenge to pass, then copies the acquired
/// cookies into the shared URLSession cookie storage so RuleEngine /
/// RemoteCatalog requests include them.
@MainActor
final class CloudflareHandler: ObservableObject {
    static let shared = CloudflareHandler()
    @Published private(set) var isSolving = false

    struct SolvingWindow: Identifiable {
        let id = UUID()
        let url: URL
        let completion: (Bool) -> Void

        func finish(_ ok: Bool) { completion(ok) }
    }
    @Published private(set) var pending: SolvingWindow?

    /// Presents a hidden WebView offscreen and waits for clearance cookies.
    func solve(url: URL, completion: @escaping (Bool) -> Void) {
        isSolving = true
        pending = SolvingWindow(url: url, completion: { [weak self] ok in
            Task { @MainActor in
                self?.isSolving = false
                self?.pending = nil
                completion(ok)
            }
        })
    }

    /// Called by the presenting view when the WebView finished navigation.
    func handleNavigationCompletion(success: Bool) {
        pending?.finish(success)
    }
}

struct CloudflareSolverView: UIViewRepresentable {
    let url: URL
    let timeout: TimeInterval
    let onFinish: (Bool) -> Void

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        context.coordinator.scheduleTimeout(webView: webView)
        webView.load(URLRequest(url: url))
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }

    final class Coordinator: NSObject, WKNavigationDelegate {
        let parent: CloudflareSolverView
        private var finished = false
        private var navigations = 0

        init(parent: CloudflareSolverView) { self.parent = parent }

        func scheduleTimeout(webView: WKWebView) {
            DispatchQueue.main.asyncAfter(deadline: .now() + parent.timeout) { [weak self, weak webView] in
                self?.finish(success: false)
            }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            navigations += 1
            // Give the JS challenge time to set cookies; after the second
            // navigation (post-challenge reload) assume clearance.
            let wait = navigations == 1 ? 5.0 : 2.0
            DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in
                self?.finish(success: true)
            }
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            finish(success: false)
        }

        private func finish(success: Bool) {
            guard !finished else { return }
            finished = true
            if success {
                let store = HTTPCookieStorage.shared
                let dataStore = WKWebsiteDataStore.nonPersistent()
                dataStore.httpCookieStore.getAllCookies { cookies in
                    for cookie in cookies {
                        store.setCookie(cookie)
                    }
                }
            }
            parent.onFinish(success)
        }
    }
}
