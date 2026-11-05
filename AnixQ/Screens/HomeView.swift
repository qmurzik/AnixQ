import SwiftUI

@MainActor
final class HomeModel: ObservableObject {
    struct Featured: Identifiable {
        let title: Title
        let background: URL?
        var id: Int { title.id }
    }

    @Published var featured: [Featured] = []
    @Published var ongoing: [Title] = []
    @Published var updated: [Title] = []
    @Published var popular: [Title] = []
    @Published var top: [Title] = []
    @Published var newest: [Title] = []
    @Published var forYou: [Title] = []
    @Published var nextSeasons: [Title] = []
    private var nextSeasonsLoaded = Date.distantPast

    /// For finished titles: the next main entry of the franchise (Shikimori order) the user hasn't
    /// started or bookmarked yet.
    func loadNextSeasons(library: LibraryStore) async {
        guard Date().timeIntervalSince(nextSeasonsLoaded) > 30 * 60 else { return }
        nextSeasonsLoaded = Date()
        var finished: [Title] = library.titles(in: .completed)
        for e in library.history where e.progress > 0.92 && !finished.contains(where: { $0.id == e.title.id }) {
            finished.append(e.title)
        }
        let known = Set(library.history.map(\.title.shikiId)).union(library.bookmarks.values.map(\.title.shikiId))
        var out: [Title] = []
        for t in finished.prefix(8) {
            guard let sid = t.shikiId else { continue }
            let nodes = await ShikiAPI.franchise(sid)
            guard let me = nodes.first(where: { $0.shikiId == sid }) else { continue }
            let next = nodes.first { $0.date > me.date && $0.isMain && !known.contains($0.shikiId)
                && $0.date < Date() }
            if let next, let title = await LibAPI.title(shikiId: next.shikiId, names: [next.name]),
               !out.contains(where: { $0.id == title.id }) {
                out.append(title)
            }
        }
        nextSeasons = out
    }
    @Published var loaded = false
    @Published var error: String?

    // MARK: Snapshot — last home screen is shown instantly on launch, then refreshed.

    private struct Snapshot: Codable {
        var featured: [Title]
        var backgrounds: [Int: URL]
        var ongoing, updated, popular, top, newest: [Title]
    }

    private static let snapshotURL: URL = FileManager.default
        .urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("home.json")

    init() {
        guard let data = try? Data(contentsOf: Self.snapshotURL),
              let s = try? JSONDecoder().decode(Snapshot.self, from: data) else { return }
        featured = s.featured.map { Featured(title: $0, background: s.backgrounds[$0.id]) }
        ongoing = s.ongoing; updated = s.updated; popular = s.popular; top = s.top; newest = s.newest
        loaded = !ongoing.isEmpty || !popular.isEmpty
    }

    private func saveSnapshot() {
        let s = Snapshot(featured: featured.map(\.title),
                         backgrounds: Dictionary(featured.compactMap { f in f.background.map { (f.title.id, $0) } },
                                                 uniquingKeysWith: { a, _ in a }),
                         ongoing: ongoing, updated: updated, popular: popular, top: top, newest: newest)
        if let data = try? JSONEncoder().encode(s) { try? data.write(to: Self.snapshotURL, options: .atomic) }
    }

    private var lastLoad = Date.distantPast

    /// "Для вас": titles similar to what the user watched recently, minus what they already know.
    func loadForYou(seeds: [Title], known: Set<Int>) async {
        guard !seeds.isEmpty else { forYou = []; return }
        var seen = known
        var out: [Title] = []
        let tasks = seeds.prefix(5).map { seed in Task { await LibAPI.similar(seed.slugURL) } }
        for t in tasks {
            for title in await t.value where seen.insert(title.id).inserted { out.append(title) }
        }
        forYou = Array(out.prefix(24))
    }

    func load(force: Bool = false) async {
        guard force || Date().timeIntervalSince(lastLoad) > 15 * 60 else { return }
        lastLoad = Date()
        error = nil
        func fetch(_ q: CatalogQuery) -> Task<[Title]?, Never> {
            Task { try? await LibAPI.catalog(q, page: 1).items }
        }
        let o = fetch(CatalogQuery(status: .ongoing, sort: .popular))
        let u = fetch(CatalogQuery(sort: .updated))
        let p = fetch(CatalogQuery(sort: .popular))
        let t = fetch(CatalogQuery(sort: .rating))
        let n = fetch(CatalogQuery(sort: .newest))
        let ongoing = await o.value, updated = await u.value, popular = await p.value
        let top = await t.value, newest = await n.value
        // Keep what is on screen (cached snapshot) if a request failed.
        if let ongoing, !ongoing.isEmpty { self.ongoing = Array(ongoing.prefix(24)) }
        if let updated, !updated.isEmpty { self.updated = Array(updated.prefix(24)) }
        if let popular, !popular.isEmpty { self.popular = Array(popular.prefix(24)) }
        if let top, !top.isEmpty { self.top = Array(top.prefix(24)) }
        if let newest, !newest.isEmpty { self.newest = Array(newest.filter { $0.statusId != 3 }.prefix(24)) }
        if self.ongoing.isEmpty && self.popular.isEmpty {
            error = "Не удалось загрузить AnimeLib. Проверьте подключение."
            lastLoad = .distantPast
        }
        loaded = true

        // Hero: popular ongoings with their wide background art.
        let picks = Array(self.ongoing.prefix(7))
        let backgrounds: [Int: URL] = await withTaskGroup(of: (Int, URL?).self) { group in
            for t in picks { group.addTask { (t.id, await LibAPI.background(t.slugURL)) } }
            var out: [Int: URL] = [:]
            for await (id, url) in group { if let url { out[id] = url } }
            return out
        }
        let fresh = picks.filter { backgrounds[$0.id] != nil }.map { Featured(title: $0, background: backgrounds[$0.id]) }
        if !fresh.isEmpty { featured = fresh }
        saveSnapshot()
    }
}

struct HomeView: View {
    @StateObject private var model = HomeModel()
    @EnvironmentObject private var library: LibraryStore
    @ObservedObject private var updater = WatchingUpdater.shared

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 30) {
                if !model.featured.isEmpty {
                    HeroCarousel(items: model.featured)
                } else {
                    Color.clear.frame(height: 90)
                }

                if !library.history.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        SectionTitle("Продолжить просмотр")
                        ScrollView(.horizontal, showsIndicators: false) {
                            LazyHStack(spacing: 14) {
                                ForEach(library.history.prefix(15)) { entry in
                                    NavigationLink(value: entry.title) { ContinueCard(entry: entry) }
                                        .buttonStyle(PressableStyle())
                                        .contextMenu {
                                            Button {
                                                library.removeFromHistory(entry)
                                            } label: {
                                                Label("Убрать из «Продолжить»", systemImage: "eye.slash")
                                            }
                                        }
                                }
                            }
                            .padding(.horizontal)
                        }
                    }
                }

                if !model.loaded {
                    ShelfSkeleton()
                    ShelfSkeleton()
                }

                Shelf(title: "Вышли новые серии",
                      titles: library.history.map(\.title).filter { updater.newCount($0.id) > 0 }
                        + library.watchingTitles.filter { t in updater.newCount(t.id) > 0
                            && !library.history.contains { $0.title.id == t.id } })
                Shelf(title: "Смотрю", titles: library.watchingTitles)
                Shelf(title: "Продолжение франшиз", titles: model.nextSeasons)
                Shelf(title: "Для вас", titles: model.forYou)
                Shelf(title: "Новые серии", titles: model.updated,
                      seeAll: CatalogRoute(title: "Новые серии", query: CatalogQuery(sort: .updated)))
                Shelf(title: "Онгоинги", titles: model.ongoing,
                      seeAll: CatalogRoute(title: "Онгоинги", query: CatalogQuery(status: .ongoing, sort: .popular)))
                Shelf(title: "Популярное", titles: model.popular,
                      seeAll: CatalogRoute(title: "Популярное", query: CatalogQuery(sort: .popular)))
                Shelf(title: "Лучшее по рейтингу", titles: model.top,
                      seeAll: CatalogRoute(title: "Лучшее по рейтингу", query: CatalogQuery(sort: .rating)))
                Shelf(title: "Новинки каталога", titles: model.newest,
                      seeAll: CatalogRoute(title: "Новинки", query: CatalogQuery(sort: .newest)))

                if let e = model.error {
                    ErrorState(message: e) { Task { await model.load(force: true) } }
                }
            }
            .padding(.bottom, 24)
        }
        .ignoresSafeArea(edges: .top)
        .toolbar(.hidden, for: .navigationBar)
        .appDestinations()
        .refreshable { await model.load(force: true) }
        .task {
            await model.load()
            let known = Set(library.history.map(\.title.id)).union(library.bookmarks.values.map(\.title.id))
            let seeds = library.history.map(\.title) + library.watchingTitles + library.titles(in: .favorite)
            await model.loadForYou(seeds: seeds, known: known)
            await model.loadNextSeasons(library: library)
        }
    }
}

struct HeroCarousel: View {
    let items: [HomeModel.Featured]
    @State private var index = 0
    @EnvironmentObject private var library: LibraryStore
    private let timer = Timer.publish(every: 7, on: .main, in: .common).autoconnect()

    var body: some View {
        TabView(selection: $index) {
            ForEach(Array(items.enumerated()), id: \.element.id) { i, item in
                slide(item).tag(i)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .always))
        .frame(height: Layout.heroHeight)
        .onReceive(timer) { _ in
            guard items.count > 1 else { return }
            withAnimation(.easeInOut(duration: 0.6)) { index = (index + 1) % items.count }
        }
    }

    private func slide(_ item: HomeModel.Featured) -> some View {
        ZStack(alignment: .bottom) {
            RemoteImage(url: item.background ?? item.title.coverURL)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            LinearGradient(stops: [
                .init(color: .black.opacity(0.35), location: 0),
                .init(color: .clear, location: 0.25),
                .init(color: .clear, location: 0.45),
                .init(color: Theme.background.opacity(0.85), location: 0.8),
                .init(color: Theme.background, location: 1)
            ], startPoint: .top, endPoint: .bottom)

            VStack(spacing: 12) {
                Text(item.title.title)
                    .font(.system(size: 30, weight: .bold))
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .shadow(color: .black.opacity(0.4), radius: 10)
                Text(item.title.metaLine)
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(.secondary)
                HStack(spacing: 10) {
                    NavigationLink(value: item.title) {
                        Label("Смотреть", systemImage: "play.fill")
                    }
                    .buttonStyle(PrimaryButtonStyle())

                    BookmarkMenu(title: item.title) {
                        Image(systemName: library.bookmark(for: item.title.slugURL) == nil ? "plus" : "checkmark")
                    }
                    .buttonStyle(GlassButtonStyle())
                    .frame(width: 50)
                }
                .padding(.horizontal, 40)
                .frame(maxWidth: 520)
            }
            .padding(.bottom, 44)
        }
        .clipped()
    }
}

/// "+" menu with every bookmark folder, synced to AnimeLib / Shikimori by LibraryStore.
struct BookmarkMenu<Label: View>: View {
    let title: Title
    @ViewBuilder var label: () -> Label
    @EnvironmentObject private var library: LibraryStore

    var body: some View {
        Menu {
            let current = library.bookmark(for: title.slugURL)
            ForEach(BookmarkStatus.allCases) { s in
                Button {
                    library.setBookmark(title: title, status: s)
                } label: {
                    SwiftUI.Label(s.title, systemImage: current == s ? "checkmark" : s.icon)
                }
            }
            if current != nil {
                Divider()
                Button(role: .destructive) {
                    library.setBookmark(title: title, status: nil)
                } label: {
                    SwiftUI.Label("Удалить из закладок", systemImage: "trash")
                }
            }
        } label: {
            label()
        }
    }
}
