import SwiftUI
import WebKit

/// Sign-in to AnimeLib on the official site inside a web view. The user types the password into
/// the AnimeLib site itself; the app only picks up the resulting API token from local storage.
///
/// anilib.me sits behind a Cloudflare firewall that blocks many VPN / mobile IPs, so the official
/// mirrors are tried in order and the user can switch manually.
struct LibLoginView: View {
    var onDone: (Bool) -> Void = { _ in }
    @Environment(\.dismiss) private var dismiss
    @State private var mirrorIndex = 0
    @State private var status = "Войдите в свой аккаунт AnimeLib"
    @State private var finished = false
    @State private var reloadToken = 0

    static let mirrors = ["https://v4.animelib.org/ru", "https://animelib.org/ru", "https://anilib.me/ru"]

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Text(status)
                    .font(.footnote)
                    .foregroundStyle(Theme.secondaryText)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(10)
                    .background(Theme.card)
                LoginWebView(url: URL(string: Self.mirrors[mirrorIndex])!,
                             onBlocked: { mirrorBlocked() },
                             onToken: { access, refresh in tokenReceived(access, refresh) })
                    .id("\(mirrorIndex)-\(reloadToken)")
            }
            .navigationTitle("Вход в AnimeLib")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Отмена") {
                        onDone(false)
                        dismiss()
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        ForEach(Self.mirrors.indices, id: \.self) { i in
                            Button {
                                mirrorIndex = i
                                reloadToken += 1
                                status = "Зеркало: \(host(i))"
                            } label: {
                                Label(host(i), systemImage: i == mirrorIndex ? "checkmark" : "globe")
                            }
                        }
                    } label: {
                        Image(systemName: "globe")
                    }
                }
            }
        }
    }

    private func host(_ i: Int) -> String {
        URL(string: Self.mirrors[i])?.host ?? Self.mirrors[i]
    }

    private func mirrorBlocked() {
        DebugLog.log("animelib: login blocked on \(host(mirrorIndex))")
        if mirrorIndex + 1 < Self.mirrors.count {
            mirrorIndex += 1
            status = "Сайт заблокировал вход, пробуем зеркало \(host(mirrorIndex))…"
        } else {
            status = "Все зеркала заблокировали вход. Попробуйте выключить или сменить VPN и выберите зеркало вручную (🌐)."
        }
    }

    private func tokenReceived(_ access: String, _ refresh: String?) {
        guard !finished else { return }
        finished = true
        LibSession.token = access
        LibSession.refreshToken = refresh
        status = "Готово, проверяем аккаунт…"
        DebugLog.log("animelib: token received via \(host(mirrorIndex))")
        Task {
            await LibAPI.loadProfile()
            LibAPI.clearCache()
            onDone(true)
            dismiss()
        }
    }
}

private struct LoginWebView: UIViewRepresentable {
    let url: URL
    let onBlocked: () -> Void
    let onToken: (String, String?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onBlocked: onBlocked, onToken: onToken) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        // Keep WebKit's real user agent (matching the device's iOS version) and only add the
        // Safari suffix; a spoofed UA that disagrees with the engine trips bot protection.
        config.applicationNameForUserAgent = "Version/18.0 Mobile/15E148 Safari/604.1"
        let web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = context.coordinator
        web.load(URLRequest(url: url))
        context.coordinator.start(polling: web)
        return web
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        coordinator.timer?.invalidate()
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        let onBlocked: () -> Void
        let onToken: (String, String?) -> Void
        var timer: Timer?
        private var reportedBlock = false

        init(onBlocked: @escaping () -> Void, onToken: @escaping (String, String?) -> Void) {
            self.onBlocked = onBlocked
            self.onToken = onToken
        }

        /// Every value in localStorage that mentions an access token (the site keeps its auth
        /// state as JSON there after the OAuth redirect).
        private let tokenScript = """
        (function(){
          var out=[];
          for (var i=0;i<localStorage.length;i++){
            var v=localStorage.getItem(localStorage.key(i));
            if (v && v.indexOf('access_token')>=0) out.push(v);
          }
          return JSON.stringify(out);
        })()
        """

        private let blockScript = """
        (function(){
          var t=(document.title||'')+' '+((document.body&&document.body.innerText)||'').slice(0,400);
          return /you have been blocked|attention required|access denied|error 1020|доступ запрещ/i.test(t);
        })()
        """

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            webView.evaluateJavaScript(blockScript) { [weak self] result, _ in
                guard let self, (result as? Bool) == true, !self.reportedBlock else { return }
                self.reportedBlock = true
                self.timer?.invalidate()
                DispatchQueue.main.async { self.onBlocked() }
            }
        }

        func start(polling web: WKWebView) {
            timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self, weak web] _ in
                guard let self, let web else { return }
                web.evaluateJavaScript(self.tokenScript) { result, _ in
                    guard let text = result as? String, let data = text.data(using: .utf8),
                          let values = try? JSONSerialization.jsonObject(with: data) as? [String] else { return }
                    for value in values {
                        guard let d = value.data(using: .utf8),
                              let json = try? JSONSerialization.jsonObject(with: d),
                              let access = Self.find("access_token", in: json) else { continue }
                        self.timer?.invalidate()
                        self.onToken(access, Self.find("refresh_token", in: json))
                        return
                    }
                }
            }
        }

        static func find(_ key: String, in json: Any) -> String? {
            if let dict = json as? [String: Any] {
                if let v = dict[key] as? String, !v.isEmpty { return v }
                for value in dict.values { if let v = find(key, in: value) { return v } }
            } else if let array = json as? [Any] {
                for value in array { if let v = find(key, in: value) { return v } }
            }
            return nil
        }
    }
}
