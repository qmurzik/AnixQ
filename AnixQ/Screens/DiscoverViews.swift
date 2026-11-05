import SwiftUI
import PhotosUI
import CoreSpotlight
import UniformTypeIdentifiers

// MARK: - Search by screenshot (trace.moe)

struct TraceResult: Identifiable, Hashable {
    let id = UUID()
    let anilistId: Int
    let malId: Int?
    let names: [String]
    let cover: URL?
    let episode: String?
    let from: Double
    let similarity: Double
    let image: URL?
    /// Other matching moments of the same title (trace.moe returns several per anime).
    var moments = 1

    var title: String { names.first ?? "?" }
}

enum TraceMoe {
    static func search(_ data: Data) async throws -> [TraceResult] {
        var req = URLRequest(url: URL(string: "https://api.trace.moe/search?cutBorders&anilistInfo")!)
        req.httpMethod = "POST"
        req.setValue("image/jpeg", forHTTPHeaderField: "Content-Type")
        req.httpBody = data
        req.timeoutInterval = 40
        let (body, response) = try await URLSession.shared.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            DebugLog.log("trace: HTTP \(status) \(String(data: body.prefix(200), encoding: .utf8) ?? "")")
            if status == 402 || status == 429 { throw LibError.server("Лимит поиска trace.moe исчерпан, попробуйте позже") }
            throw LibError.badStatus(status)
        }
        let json = try JSONSerialization.jsonObject(with: body) as? JSON ?? [:]
        if let err = json.str("error") { throw LibError.server("trace.moe: \(err)") }
        // Group by anime: keep the best moment per title, count the rest.
        var byAnime: [Int: TraceResult] = [:]
        var order: [Int] = []
        for r in json.arr("result") {
            guard let a = r.obj("anilist"), let id = a.int("id") else { continue }
            if var existing = byAnime[id] {
                existing.moments += 1
                byAnime[id] = existing
                continue
            }
            let t = a.obj("title") ?? [:]
            let names = [t.str("romaji"), t.str("english"), t.str("native")].compactMap { $0 }
                + (a["synonyms"] as? [String] ?? [])
            let cover = a.obj("coverImage").flatMap { $0.str("large") ?? $0.str("medium") }.flatMap(URL.init(string:))
            byAnime[id] = TraceResult(anilistId: id, malId: a.int("idMal"), names: names, cover: cover,
                                      episode: r.str("episode"), from: r.double("from") ?? 0,
                                      similarity: r.double("similarity") ?? 0,
                                      image: r.str("image").flatMap(URL.init(string:)))
            order.append(id)
        }
        let results = order.compactMap { byAnime[$0] }
        DebugLog.log("trace: " + results.prefix(4).map { "\($0.title) [al \($0.anilistId), mal \($0.malId ?? 0)] \(Int($0.similarity * 100))%" }
            .joined(separator: "; "))
        return results
    }

    /// AnimeLib match: AniList id, then MAL id (= Shikimori id, which AnimeLib fills more often),
    /// then an exact title match.
    static func resolve(_ r: TraceResult) async -> Title? {
        func norm(_ s: String?) -> String { (s ?? "").lowercased().filter { $0.isLetter || $0.isNumber } }
        let wanted = Set(r.names.map(norm)).subtracting([""])
        var candidates: [Title] = []
        for name in r.names.prefix(3) {
            let found = (try? await LibAPI.search(name)) ?? []
            if let hit = found.first(where: { $0.anilistId == r.anilistId }) { return hit }
            if let mal = r.malId, let hit = found.first(where: { $0.shikiId == mal }) { return hit }
            candidates += found
        }
        if let mal = r.malId, let hit = await LibAPI.title(shikiId: mal, names: Array(r.names.prefix(2))) { return hit }
        let byName = candidates.first { [$0.name, $0.engName].contains { wanted.contains(norm($0)) } }
        if byName == nil { DebugLog.log("trace: no AnimeLib match for \(r.title)") }
        return byName
    }
}

struct TraceSearchView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var pick: PhotosPickerItem?
    @State private var image: UIImage?
    @State private var results: [TraceResult] = []
    @State private var searching = false
    @State private var error: String?
    @State private var resolving: UUID?
    @State private var path = NavigationPath()
    @State private var crop = CGRect(x: 0, y: 0, width: 1, height: 1)
    @State private var characterMatches: [CharacterMatch] = []
    @State private var recognising = false
    @State private var didSearch = false

    var body: some View {
        NavigationStack(path: $path) {
            List {
                Section {
                    PhotosPicker(selection: $pick, matching: .images) {
                        Label(image == nil ? "Выбрать кадр" : "Другой кадр", systemImage: "photo.on.rectangle")
                    }
                    if let image {
                        CropEditor(image: image, crop: $crop)
                            .frame(height: 280)
                            .listRowInsets(EdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 8))
                        Button {
                            Task { await runSearch() }
                        } label: {
                            HStack {
                                if searching { ProgressView().tint(.black) } else { Image(systemName: "sparkle.magnifyingglass") }
                                Text("Найти аниме")
                            }
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(searching)
                        .listRowInsets(EdgeInsets(top: 4, leading: 12, bottom: 8, trailing: 12))
                    }
                } footer: {
                    Text(image == nil
                         ? "Кадр из аниме — найдём тайтл, серию и момент."
                         : "Оставьте в рамке только само видео: без кнопок плеера, статус-бара и субтитров. Область видео подбирается автоматически.")
                }
                if searching || recognising {
                    HStack { Spacer(); ProgressView("Узнаём персонажей…"); Spacer() }
                }
                if let error { Text(error).foregroundStyle(.orange) }
                if !characterMatches.isEmpty {
                    Section {
                        ForEach(characterMatches) { m in
                            if let t = m.title {
                                HStack(spacing: 8) {
                                NavigationLink(value: t) {
                                    VStack(alignment: .leading, spacing: 4) {
                                        TitleRow(title: t)
                                        if !m.characters.isEmpty {
                                            Label(m.characters.prefix(2).joined(separator: ", "), systemImage: "person.fill")
                                                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                        }
                                        // Exact episode/moment when trace.moe confidently found the same title.
                                        if let scene = scene(for: t) {
                                            Label([scene.episode.map { "Серия \($0)" }, "момент \(time(scene.from))"]
                                                .compactMap { $0 }.joined(separator: " · "), systemImage: "film")
                                                .font(.caption.weight(.semibold)).foregroundStyle(Theme.accent)
                                        }
                                    }
                                }
                                    PlanButton(title: t)
                                }
                            } else {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(m.work).font(.subheadline.weight(.semibold))
                                    Text("Нет на AnimeLib").font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    } header: {
                        Text("Узнали по персонажу")
                    }
                } else if !recognising, !searching, let best = results.first, best.similarity >= 0.9 {
                    // Character recognition found nothing: fall back to a confident scene match only.
                    Section { bestCard(best) } header: { Text("Совпадение по кадру") }
                } else if !recognising, !searching, image != nil, error == nil, didSearch {
                    Text("Не удалось узнать. Попробуйте кадр, где крупно видно лицо персонажа.")
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Поиск по кадру")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { dismiss() } } }
            .appDestinations()
            .onChange(of: pick) { item in Task { await load(item) } }
        }
    }

    private func bestCard(_ r: TraceResult) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                Theme.card.frame(width: 80, height: 114).overlay { RemoteImage(url: r.cover) }
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                VStack(alignment: .leading, spacing: 4) {
                    Text(r.title).font(.headline).lineLimit(3)
                    if r.names.count > 1 { Text(r.names[1]).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                    Text([r.episode.map { "Серия \($0)" }, "момент \(time(r.from))"].compactMap { $0 }.joined(separator: " · "))
                        .font(.subheadline)
                    Text("Совпадение \(Int(r.similarity * 100))%")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(r.similarity > 0.9 ? .green : .orange)
                }
            }
            if let img = r.image {
                RemoteImage(url: img).frame(height: 150).clipShape(RoundedRectangle(cornerRadius: 10))
            }
            Button {
                Task { await open(r) }
            } label: {
                HStack {
                    if resolving == r.id { ProgressView().tint(.black) } else { Image(systemName: "play.fill") }
                    Text("Открыть тайтл")
                }
            }
            .buttonStyle(PrimaryButtonStyle())
        }
        .padding(.vertical, 4)
    }

    private func row(_ r: TraceResult) -> some View {
        HStack(spacing: 12) {
            Theme.card.frame(width: 44, height: 62).overlay { RemoteImage(url: r.cover) }
                .clipShape(RoundedRectangle(cornerRadius: 6))
            VStack(alignment: .leading, spacing: 3) {
                Text(r.title).font(.subheadline.weight(.semibold)).lineLimit(2).foregroundStyle(.primary)
                Text([r.episode.map { "Серия \($0)" }, time(r.from)].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if resolving == r.id { ProgressView() } else {
                Text("\(Int(r.similarity * 100))%").font(.caption.weight(.bold))
                    .foregroundStyle(r.similarity > 0.9 ? .green : .orange)
            }
        }
    }

    /// trace.moe result for the same title (MAL id = Shikimori id), only when confident.
    private func scene(for t: Title) -> TraceResult? {
        results.first { $0.similarity >= 0.9 && ($0.malId != nil && $0.malId == t.shikiId || $0.anilistId == t.anilistId) }
    }

    private func time(_ s: Double) -> String { String(format: "%d:%02d", Int(s) / 60, Int(s) % 60) }

    private func load(_ item: PhotosPickerItem?) async {
        guard let item, let data = try? await item.loadTransferable(type: Data.self),
              let img = UIImage(data: data) else {
            error = "Не удалось открыть изображение"
            return
        }
        // Normalise orientation and size (~1600 px), then let the user adjust the crop.
        let scale = min(1, 1600 / max(img.size.width, img.size.height))
        let size = CGSize(width: img.size.width * scale, height: img.size.height * scale)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let small = UIGraphicsImageRenderer(size: size, format: format).image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }
        image = small
        crop = CropEditor.detectVideo(in: small)
        results = []
        error = nil
        // Clean landscape frames need no adjustment: search right away.
        if crop == CGRect(x: 0, y: 0, width: 1, height: 1), small.size.width > small.size.height {
            await runSearch()
        }
    }

    private func runSearch() async {
        guard let image else { return }
        searching = true
        results = []
        error = nil
        defer { searching = false }
        let frame = CropEditor.cropped(image, to: crop)
        // Character recognition in parallel with scene search.
        characterMatches = []
        didSearch = false
        recognising = true
        Task {
            var found = await AnimeTraceAPI.search(frame)
            for i in found.indices { found[i].title = await AnimeTraceAPI.resolve(found[i]) }
            characterMatches = found.filter { $0.title != nil } + found.filter { $0.title == nil }.prefix(1)
            recognising = false
        }
        guard let jpeg = frame.jpegData(compressionQuality: 0.85) else { return }
        DebugLog.log(String(format: "trace: searching %.0fx%.0f crop %.2f,%.2f %.2fx%.2f", frame.size.width, frame.size.height,
                            crop.minX, crop.minY, crop.width, crop.height))
        // Scene search only adds the episode/moment; its errors are not shown to the user.
        results = (try? await TraceMoe.search(jpeg)) ?? []
        didSearch = true
    }

    static func mirrored(_ image: UIImage) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: image.size, format: format).image { ctx in
            ctx.cgContext.translateBy(x: image.size.width, y: 0)
            ctx.cgContext.scaleBy(x: -1, y: 1)
            image.draw(at: .zero)
        }
    }

    private func open(_ r: TraceResult) async {
        resolving = r.id
        defer { resolving = nil }
        if let t = await TraceMoe.resolve(r) {
            path.append(t)
        } else {
            error = "«\(r.title)» не нашёлся на AnimeLib"
        }
    }
}

// MARK: - "Что посмотреть?"

struct RandomPickView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var library: LibraryStore
    @State private var genres: [Genre] = []
    @State private var selectedGenres: Set<Int> = []
    @State private var type: CatalogType?
    @State private var onlyFinished = true
    @State private var minScore: Double = 7
    @State private var pick: Title?
    @State private var summary: String?
    @State private var loading = false
    @State private var pool: [Title] = []

    var body: some View {
        NavigationStack {
            Form {
                if let pick {
                    Section {
                        NavigationLink(value: pick) {
                            HStack(alignment: .top, spacing: 14) {
                                PosterImage(url: pick.coverURL, radius: 10).frame(width: 100)
                                VStack(alignment: .leading, spacing: 6) {
                                    Text(pick.title).font(.headline)
                                    Text(pick.metaLine).font(.caption).foregroundStyle(.secondary)
                                    if let s = pick.score { ScoreBadge(score: s) }
                                    if let summary {
                                        Text(summary).font(.caption).foregroundStyle(.secondary).lineLimit(6)
                                    }
                                }
                            }
                        }
                    }
                }
                Section {
                    Button {
                        Task { await roll() }
                    } label: {
                        HStack {
                            Spacer()
                            if loading { ProgressView() } else { Label(pick == nil ? "Подобрать" : "Другой вариант", systemImage: "dice.fill").bold() }
                            Spacer()
                        }
                    }
                }
                Section("Фильтры") {
                    Picker("Тип", selection: $type) {
                        Text("Любой").tag(CatalogType?.none)
                        ForEach(CatalogType.allCases) { Text($0.title).tag(CatalogType?.some($0)) }
                    }
                    Toggle("Только завершённые", isOn: $onlyFinished)
                    VStack(alignment: .leading) {
                        Text("Рейтинг от \(String(format: "%.1f", minScore))")
                        Slider(value: $minScore, in: 0...9.5, step: 0.5)
                    }
                }
                Section("Жанры") {
                    if genres.isEmpty { ProgressView() }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 8)], spacing: 8) {
                        ForEach(genres) { g in
                            let on = selectedGenres.contains(g.id)
                            Text(g.name).font(.caption.weight(.semibold))
                                .frame(maxWidth: .infinity).padding(.vertical, 7)
                                .background(on ? AnyShapeStyle(Theme.accent) : AnyShapeStyle(.thinMaterial),
                                            in: Capsule())
                                .onTapGesture {
                                    if on { selectedGenres.remove(g.id) } else { selectedGenres.insert(g.id) }
                                    pool = []
                                }
                        }
                    }
                }
            }
            .navigationTitle("Что посмотреть?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { dismiss() } } }
            .appDestinations()
            .task { if genres.isEmpty { genres = await LibAPI.genres() } }
            .onChange(of: type) { _ in pool = [] }
            .onChange(of: onlyFinished) { _ in pool = [] }
            .onChange(of: minScore) { _ in pool = [] }
        }
    }

    private func roll() async {
        loading = true
        defer { loading = false }
        if pool.isEmpty {
            var q = CatalogQuery(sort: .rating)
            q.genres = selectedGenres
            if let type { q.types = [type] }
            if onlyFinished { q.statuses = [.finished] }
            let known = Set(library.history.map(\.title.id)).union(library.bookmarks.values.map(\.title.id))
            for page in [1, Int.random(in: 2...4)] {
                let items = (try? await LibAPI.catalog(q, page: page).items) ?? []
                pool += items.filter { ($0.score ?? 0) >= minScore && !known.contains($0.id) }
            }
        }
        guard !pool.isEmpty else { pick = nil; summary = "Ничего не подошло — ослабьте фильтры"; return }
        let choice = pool.remove(at: Int.random(in: 0..<pool.count))
        pick = choice
        summary = nil
        summary = (try? await LibAPI.detail(choice.slugURL))?.summary
    }
}

// MARK: - Spotlight

/// Puts bookmarked and recently watched titles into system search.
enum SpotlightIndexer {
    static let domain = "ru.qmods.anixq.titles"

    static func update(_ titles: [Title]) {
        let items = titles.prefix(300).map { t -> CSSearchableItem in
            let attrs = CSSearchableItemAttributeSet(contentType: .content)
            attrs.title = t.title
            attrs.alternateNames = [t.name, t.engName].compactMap { $0 }
            attrs.contentDescription = t.metaLine
            attrs.thumbnailURL = t.thumbURL
            return CSSearchableItem(uniqueIdentifier: "title:" + t.slugURL, domainIdentifier: domain, attributeSet: attrs)
        }
        CSSearchableIndex.default().deleteSearchableItems(withDomainIdentifiers: [domain]) { _ in
            CSSearchableIndex.default().indexSearchableItems(Array(items)) { error in
                if let error { DebugLog.log("spotlight: \(error.localizedDescription)") }
            }
        }
    }
}


/// One-tap "Хочу посмотреть": puts the title into Запланировано (synced to AnimeLib / Shikimori).
struct PlanButton: View {
    let title: Title
    @EnvironmentObject private var library: LibraryStore

    var body: some View {
        let current = library.bookmark(for: title.slugURL)
        Button {
            if current == nil { library.setBookmark(title: title, status: .planned) }
        } label: {
            Image(systemName: current == nil ? "bookmark" : "bookmark.fill")
                .font(.title3)
                .foregroundStyle(current == nil ? Theme.accent : .secondary)
                .frame(width: 40, height: 40)
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(current == nil ? "Хочу посмотреть" : current!.title)
    }
}
