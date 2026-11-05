import SwiftUI

// MARK: - Navigation

struct GenreRoute: Hashable { let genre: Genre }
struct CatalogRoute: Hashable { let title: String; let query: CatalogQuery }
struct ShikiRoute: Hashable { let shikiId: Int; let names: [String] }

extension View {
    /// Push destinations shared by every tab.
    func appDestinations() -> some View {
        self
            .navigationDestination(for: Title.self) { TitleView(title: $0) }
            .navigationDestination(for: GenreRoute.self) { r in
                CatalogListView(title: r.genre.name, query: CatalogQuery(genre: r.genre.id))
            }
            .navigationDestination(for: CatalogRoute.self) { CatalogListView(title: $0.title, query: $0.query) }
            .navigationDestination(for: ShikiRoute.self) { ResolveShikiView(route: $0) }
            .navigationDestination(for: PersonRoute.self) { PersonView(route: $0) }
    }
}

// MARK: - Quick actions

/// Long-press menu on any poster: bookmark without opening the title, or share it.
struct TitleQuickActions: ViewModifier {
    let title: Title
    @EnvironmentObject private var library: LibraryStore

    func body(content: Content) -> some View {
        content.contextMenu {
            let current = library.bookmark(for: title.slugURL)
            ForEach([BookmarkStatus.watching, .planned, .favorite, .completed]) { s in
                Button {
                    library.setBookmark(title: title, status: current == s ? nil : s)
                } label: {
                    Label(current == s ? "Убрать из «\(s.title)»" : s.title,
                          systemImage: current == s ? "checkmark" : s.icon)
                }
            }
            if let url = URL(string: "https://anilib.me/ru/anime/\(title.slugURL)") {
                ShareLink(item: url) { Label("Поделиться", systemImage: "square.and.arrow.up") }
            }
        } preview: {
            VStack(alignment: .leading, spacing: 8) {
                PosterImage(url: title.coverURL, radius: 12).frame(width: 200)
                Text(title.title).font(.headline).lineLimit(2)
                Text(title.metaLine).font(.caption).foregroundStyle(.secondary)
            }
            .padding()
            .frame(width: 232)
        }
    }
}

extension View {
    func titleQuickActions(_ title: Title) -> some View { modifier(TitleQuickActions(title: title)) }
}

// MARK: - Cards

struct PosterImage: View {
    let url: URL?
    var radius: CGFloat = 10

    var body: some View {
        Theme.card
            .aspectRatio(2.0 / 3.0, contentMode: .fit)
            .overlay { RemoteImage(url: url) }
            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).stroke(Theme.stroke, lineWidth: 0.5))
    }
}

struct PosterCard: View {
    let title: Title
    var showMeta = true
    @ObservedObject private var updater = WatchingUpdater.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            PosterImage(url: title.coverURL)
                .overlay(alignment: .topTrailing) {
                    if let s = title.score { ScoreBadge(score: s).padding(6) }
                }
                .overlay(alignment: .bottomLeading) {
                    let n = updater.newCount(title.id)
                    if n > 0 {
                        Text(n == 1 ? "Новая серия" : "+\(n) серий")
                            .font(.system(size: 10, weight: .bold))
                            .padding(.horizontal, 7).padding(.vertical, 4)
                            .background(Theme.accent, in: Capsule())
                            .foregroundStyle(.white)
                            .padding(6)
                    }
                }
            Text(title.title)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.primary)
                .lineLimit(2, reservesSpace: true)
            if showMeta {
                Text([title.typeLabel, title.year.map(String.init)].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
    }
}

struct PosterGrid: View {
    let titles: [Title]
    var onLast: (() -> Void)? = nil
    private let columns = [GridItem(.adaptive(minimum: 105, maximum: 170), spacing: 12, alignment: .top)]

    var body: some View {
        LazyVGrid(columns: columns, spacing: 18) {
            ForEach(titles) { t in
                NavigationLink(value: t) { PosterCard(title: t) }
                    .buttonStyle(PressableStyle())
                    .titleQuickActions(t)
                    .onAppear { if t.id == titles.last?.id { onLast?() } }
            }
        }
    }
}

struct Shelf: View {
    let title: String
    let titles: [Title]
    var seeAll: CatalogRoute? = nil
    var width: CGFloat = 118

    var body: some View {
        if !titles.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                SectionTitle(title: title) {
                    if let seeAll {
                        NavigationLink(value: seeAll) {
                            Text("См. все").font(.subheadline)
                        }
                    }
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: 12) {
                        ForEach(titles) { t in
                            NavigationLink(value: t) { PosterCard(title: t).frame(width: width) }
                                .buttonStyle(PressableStyle())
                                .titleQuickActions(t)
                        }
                    }
                    .padding(.horizontal)
                }
            }
        }
    }
}

struct ShelfSkeleton: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            RoundedRectangle(cornerRadius: 6).fill(Theme.card).frame(width: 180, height: 22).padding(.horizontal)
            HStack(spacing: 12) {
                ForEach(0..<4, id: \.self) { _ in
                    RoundedRectangle(cornerRadius: 10).fill(Theme.card).frame(width: 118, height: 177)
                        .overlay(ShimmerView().clipShape(RoundedRectangle(cornerRadius: 10)))
                }
            }
            .padding(.horizontal)
        }
    }
}

/// 16:9 "Up Next" card with progress.
struct ContinueCard: View {
    let entry: WatchEntry
    var width: CGFloat = 260

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack(alignment: .bottomLeading) {
                RemoteImage(url: entry.title.coverURL)
                LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .center, endPoint: .bottom)
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 6) {
                        Image(systemName: entry.progress > 0.92 ? "forward.end.fill" : "play.fill").font(.caption)
                        if entry.progress > 0.92, let n = Int(entry.episodeNumber) {
                            // Finished: offer the next episode right away.
                            Text("Дальше · серия \(n + 1)").font(.subheadline.weight(.semibold))
                        } else {
                            Text("Серия \(entry.episodeNumber)").font(.subheadline.weight(.semibold))
                        }
                        if entry.duration > 0, entry.progress <= 0.92 {
                            Text("· осталось \(Int(max(0, entry.duration - entry.position) / 60)) мин")
                                .font(.caption).foregroundStyle(.white.opacity(0.75))
                        }
                    }
                    .foregroundStyle(.white)
                    ProgressView(value: entry.progress)
                        .tint(.white)
                        .background(Color.white.opacity(0.25))
                        .scaleEffect(x: 1, y: 0.7, anchor: .center)
                }
                .padding(10)
            }
            .frame(width: width, height: width * 9 / 16)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

            Text(entry.title.title).font(.subheadline.weight(.semibold)).lineLimit(1)
            Text(entry.teamName).font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }
        .frame(width: width, alignment: .leading)
    }
}

struct TitleRow: View {
    let title: Title
    var trailing: String? = nil

    var body: some View {
        HStack(spacing: 12) {
            PosterImage(url: title.thumbURL, radius: 8).frame(width: 56)
            VStack(alignment: .leading, spacing: 3) {
                Text(title.title).font(.subheadline.weight(.semibold)).lineLimit(2)
                if let o = title.originalTitle {
                    Text(o).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Text(title.metaLine).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
            if let trailing {
                Text(trailing).font(.caption.weight(.semibold)).foregroundStyle(Theme.accent)
            } else if let s = title.score {
                ScoreBadge(score: s)
            }
        }
        .contentShape(Rectangle())
    }
}

// MARK: - Paging

@MainActor
final class CatalogLoader: ObservableObject {
    @Published private(set) var items: [Title] = []
    @Published private(set) var isLoading = false
    @Published private(set) var error: String?
    private var page = 1
    private var hasMore = true
    private var generation = 0
    private(set) var query = CatalogQuery()

    func reset(_ q: CatalogQuery) async {
        generation += 1
        query = q
        items = []
        page = 1
        hasMore = true
        error = nil
        isLoading = false
        await loadMore()
    }

    func loadMore() async {
        guard !isLoading, hasMore else { return }
        let gen = generation
        isLoading = true
        defer { if gen == generation { isLoading = false } }
        do {
            let r = try await LibAPI.catalog(query, page: page)
            guard gen == generation else { return }
            let known = Set(items.map(\.id))
            items += r.items.filter { !known.contains($0.id) }
            hasMore = r.hasMore && !r.items.isEmpty
            page += 1
        } catch {
            guard gen == generation else { return }
            if (error as? URLError)?.code == .cancelled { return }
            self.error = error.localizedDescription
        }
    }
}

struct CatalogListView: View {
    let title: String
    let query: CatalogQuery
    @StateObject private var loader = CatalogLoader()

    var body: some View {
        ScrollView {
            PosterGrid(titles: loader.items) { Task { await loader.loadMore() } }
                .padding()
            if loader.isLoading {
                ProgressView().padding()
            } else if let e = loader.error {
                ErrorState(message: e) { Task { await loader.reset(query) } }
            } else if loader.items.isEmpty {
                EmptyState(icon: "tray", title: "Ничего не найдено")
            }
        }
        .navigationTitle(title)
        .task { if loader.items.isEmpty { await loader.reset(query) } }
        .refreshable { await loader.reset(query) }
    }
}

/// Schedule entries come from Shikimori; this finds the matching AnimeLib title.
struct ResolveShikiView: View {
    let route: ShikiRoute
    @State private var title: Title?
    @State private var failed = false

    var body: some View {
        Group {
            if let title {
                TitleView(title: title)
            } else if failed {
                EmptyState(icon: "questionmark.folder", title: "Не нашли этот тайтл на AnimeLib",
                           subtitle: route.names.first)
            } else {
                ProgressView()
            }
        }
        .task {
            if title == nil {
                title = await LibAPI.title(shikiId: route.shikiId, names: route.names)
                failed = title == nil
            }
        }
    }
}
