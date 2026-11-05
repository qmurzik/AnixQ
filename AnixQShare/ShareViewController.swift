import UIKit
import SwiftUI
import UniformTypeIdentifiers

/// "Что за аниме?" in the system share sheet: recognises the characters on a shared image
/// (AnimeTrace), finds the title on Shikimori and hands it to AnixQ (open or add to plans).
final class ShareViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        let model = ShareModel()
        model.close = { [weak self] in self?.extensionContext?.completeRequest(returningItems: nil) }
        model.open = { [weak self] url in self?.openInApp(url) }
        let host = UIHostingController(rootView: ShareView(model: model))
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(host.view)
        host.didMove(toParent: self)
        Task { await model.run(items: extensionContext?.inputItems as? [NSExtensionItem] ?? []) }
    }

    /// Extensions can't call UIApplication.shared; walk the responder chain to reach it.
    private func openInApp(_ url: URL) {
        var responder: UIResponder? = self
        while let r = responder {
            if let app = r as? UIApplication {
                app.open(url, options: [:], completionHandler: nil)
                break
            }
            responder = r.next
        }
        extensionContext?.completeRequest(returningItems: nil)
    }
}

struct ShareMatch: Identifiable {
    let shikiId: Int
    let title: String
    let original: String
    let characters: [String]
    var id: Int { shikiId }
}

@MainActor
final class ShareModel: ObservableObject {
    @Published var image: UIImage?
    @Published var matches: [ShareMatch] = []
    @Published var state = "Узнаём персонажей…"
    @Published var done = false
    var close: () -> Void = {}
    var open: (URL) -> Void = { _ in }

    func run(items: [NSExtensionItem]) async {
        guard let provider = items.flatMap({ $0.attachments ?? [] })
            .first(where: { $0.hasItemConformingToTypeIdentifier(UTType.image.identifier) }) else {
            state = "Не нашли картинку"
            done = true
            return
        }
        let loaded: UIImage? = await withCheckedContinuation { cont in
            provider.loadItem(forTypeIdentifier: UTType.image.identifier) { item, _ in
                if let url = item as? URL, let data = try? Data(contentsOf: url) { cont.resume(returning: UIImage(data: data)) }
                else if let data = item as? Data { cont.resume(returning: UIImage(data: data)) }
                else if let img = item as? UIImage { cont.resume(returning: img) }
                else { cont.resume(returning: nil) }
            }
        }
        guard let img = loaded else { state = "Не удалось открыть картинку"; done = true; return }
        image = img
        let works = await recognise(img)
        var out: [ShareMatch] = []
        for (work, chars) in works.prefix(3) {
            if let hit = await shikiSearch(work) { out.append(ShareMatch(shikiId: hit.0, title: hit.1, original: work, characters: chars)) }
        }
        matches = out
        state = out.isEmpty ? "Не удалось узнать. Нужен кадр, где видно лицо персонажа." : ""
        done = true
    }

    func url(for m: ShareMatch, plan: Bool) -> URL? {
        var c = URLComponents()
        c.scheme = "anixq"
        c.host = plan ? "plan" : "shiki"
        c.path = "/\(m.shikiId)"
        c.queryItems = [URLQueryItem(name: "n", value: m.title), URLQueryItem(name: "o", value: m.original)]
        return c.url
    }

    // MARK: Network (self-contained: extensions have a small memory budget)

    private func recognise(_ image: UIImage) async -> [(String, [String])] {
        let scale = min(1, 800 / max(image.size.width, image.size.height))
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let small = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        guard let b64 = small.jpegData(compressionQuality: 0.85)?.base64EncodedString() else { return [] }
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        for model in ["pre_stable", "anime_model_lovelive"] {
            var req = URLRequest(url: URL(string: "https://api.animetrace.com/v1/search")!)
            req.httpMethod = "POST"
            req.timeoutInterval = 40
            req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            req.httpBody = Data(("is_multi=1&ai_detect=0&model=\(model)&base64=" + (b64.addingPercentEncoding(withAllowedCharacters: allowed) ?? b64)).utf8)
            guard let (data, _) = try? await URLSession.shared.data(for: req),
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let boxes = json["data"] as? [[String: Any]] else { continue }
            var score: [String: (Int, [String])] = [:]
            for box in boxes {
                for (rank, c) in (box["character"] as? [[String: Any]] ?? []).prefix(3).enumerated() {
                    guard let work = c["work"] as? String else { continue }
                    var e = score[work] ?? (0, [])
                    e.0 += rank == 0 ? 3 : 1
                    if let ch = c["character"] as? String, !e.1.contains(ch) { e.1.append(ch) }
                    score[work] = e
                }
            }
            let sorted = score.sorted { $0.value.0 > $1.value.0 }.map { ($0.key, $0.value.1) }
            if !sorted.isEmpty { return sorted }
        }
        return []
    }

    private func shikiSearch(_ q: String) async -> (Int, String)? {
        var c = URLComponents(string: "https://shikimori.io/api/animes")!
        c.queryItems = [URLQueryItem(name: "search", value: q), URLQueryItem(name: "limit", value: "1"),
                        URLQueryItem(name: "order", value: "popularity")]
        var req = URLRequest(url: c.url!)
        req.setValue("AnixQ", forHTTPHeaderField: "User-Agent")
        guard let (data, _) = try? await URLSession.shared.data(for: req),
              let list = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]],
              let first = list.first, let id = first["id"] as? Int else { return nil }
        let ru = (first["russian"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? (first["name"] as? String ?? q)
        return (id, ru)
    }
}

struct ShareView: View {
    @ObservedObject var model: ShareModel

    var body: some View {
        NavigationStack {
            List {
                if let img = model.image {
                    Image(uiImage: img).resizable().scaledToFit().frame(maxHeight: 200)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                        .frame(maxWidth: .infinity)
                }
                if !model.done {
                    HStack { Spacer(); ProgressView(model.state); Spacer() }
                } else if !model.state.isEmpty {
                    Text(model.state).foregroundStyle(.secondary)
                }
                ForEach(model.matches) { m in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(m.title).font(.headline)
                        if !m.characters.isEmpty {
                            Label(m.characters.prefix(2).joined(separator: ", "), systemImage: "person.fill")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        HStack(spacing: 10) {
                            Button {
                                if let u = model.url(for: m, plan: false) { model.open(u) }
                            } label: {
                                Label("Открыть", systemImage: "play.fill").frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.borderedProminent)
                            Button {
                                if let u = model.url(for: m, plan: true) { model.open(u) }
                            } label: {
                                Label("В планы", systemImage: "bookmark").frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                    .padding(.vertical, 4)
                }
            }
            .navigationTitle("Что за аниме?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { model.close() } } }
        }
        .tint(Color(red: 1.0, green: 0.27, blue: 0.42))
    }
}
