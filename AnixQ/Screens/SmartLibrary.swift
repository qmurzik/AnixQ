import SwiftUI

/// "Для меня": today's picks, recommendations from Shikimori scores, unwatched episodes,
/// abandoned titles and franchise continuations.
@MainActor
final class SmartLibraryModel: ObservableObject {
    struct ShikiPick: Identifiable, Hashable {
        let id: Int
        let name: String
        let russian: String?
        let image: URL?
        let score: Double?
        /// Titles from the user's list that led to this pick.
        var because: [String]
        var weight: Double
        var title: String { russian?.nilIfEmpty ?? name }
    }

    @Published private(set) var recommendations: [ShikiPick] = []
    @Published private(set) var continuations: [Title] = []
    @Published private(set) var airingToday: [ShikiCalendarItem] = []
    @Published private(set) var loadingRecs = false
    @Published private(set) var recsNote: String?
    private var loaded = Date.distantPast

    func load(library: LibraryStore) async {
        guard Date().timeIntervalSince(loaded) > 20 * 60 else { return }
        loaded = Date()
        async let airing: Void = loadAiring(library: library)
        async let recs: Void = loadRecommendations(library: library)
        async let next: Void = loadContinuations(library: library)
        _ = await (airing, recs, next)
    }

    private func loadAiring(library: LibraryStore) async {
        guard let items = try? await ShikiAPI.calendar() else { return }
        let followed = Set((library.watchingTitles + library.history.prefix(30).map(\.title)).compactMap(\.shikiId))
        let cal = Calendar.current
        airingToday = items.filter { followed.contains($0.shikiId) && cal.isDateInToday($0.nextEpisodeAt) }
            .sorted { $0.nextEpisodeAt < $1.nextEpisodeAt }
    }

    /// Titles the user rated highly on Shikimori → their "similar" lists, weighted by the score.
    private func loadRecommendations(library: LibraryStore) async {
        guard ShikiSession.isLoggedIn else {
            recsNote = "Подключите Shikimori — рекомендации строятся по вашим оценкам."
            return
        }
        loadingRecs = true
        defer { loadingRecs = false }
        guard let rates = try? await ShikiAPI.animeRates() else {
            recsNote = "Не удалось загрузить список Shikimori."
            return
        }
        let known = Set(rates.map(\.shikiId)).union(library.bookmarks.values.compactMap(\.title.shikiId))
        let seeds = rates.filter { $0.score >= 7 }.sorted { $0.score > $1.score }.prefix(10)
        guard !seeds.isEmpty else {
            recsNote = "Поставьте оценки 7+ хотя бы паре тайтлов на Shikimori — и здесь появятся рекомендации."
            return
        }
        recsNote = nil
        var picks: [Int: ShikiPick] = [:]
        await withTaskGroup(of: (ShikiAPI.RemoteRate, [JSON]).self) { group in
            for seed in seeds {
                group.addTask {
                    let list = (try? await ShikiAPI.request("/api/animes/\(seed.shikiId)/similar", auth: false)) as? [JSON] ?? []
                    return (seed, Array(list.prefix(12)))
                }
            }
            for await (seed, list) in group {
                for (rank, x) in list.enumerated() {
                    guard let id = x.int("id"), !known.contains(id) else { continue }
                    let w = Double(seed.score - 5) * (1.0 - Double(rank) * 0.05)
                    let seedName = seed.russian?.nilIfEmpty ?? seed.name
                    if var p = picks[id] {
                        p.weight += w
                        if !p.because.contains(seedName) { p.because.append(seedName) }
                        picks[id] = p
                    } else {
                        picks[id] = ShikiPick(id: id, name: x.str("name") ?? "", russian: x.str("russian"),
                                              image: ShikiAPI.absolute(x.obj("image")?.str("original")),
                                              score: x.double("score"), because: [seedName], weight: w)
                    }
                }
            }
        }
        recommendations = Array(picks.values.sorted {
            $0.weight + ($0.score ?? 0) * 0.3 > $1.weight + ($1.score ?? 0) * 0.3
        }.prefix(30))
    }

    /// Finished titles → the next main entry of the franchise not in the library yet.
    private func loadContinuations(library: LibraryStore) async {
        var finished = library.titles(in: .completed) + library.titles(in: .favorite)
        for e in library.history where e.progress > 0.92 && !finished.contains(where: { $0.id == e.title.id }) {
            finished.append(e.title)
        }
        let known = Set(library.history.compactMap(\.title.shikiId)).union(library.bookmarks.values.compactMap(\.title.shikiId))
        var out: [Title] = []
        for t in finished.prefix(14) {
            guard let sid = t.shikiId else { continue }
            let nodes = await ShikiAPI.franchise(sid)
            guard let me = nodes.first(where: { $0.shikiId == sid }) else { continue }
            if let next = nodes.first(where: { $0.date > me.date && $0.isMain && !known.contains($0.shikiId) && $0.date < Date() }),
               let title = await LibAPI.title(shikiId: next.shikiId, names: [next.name]),
               !out.contains(where: { $0.id == title.id }) {
                out.append(title)
            }
        }
        continuations = out
    }
}

extension LibraryStore {
    /// Started but not touched for a month, not finished and not marked as dropped / on hold.
    var abandoned: [WatchEntry] {
        let month = Date().addingTimeInterval(-30 * 86400)
        return history.filter { e in
            let status = bookmark(for: e.title.slugURL)
            return e.date < month && e.progress < 0.92 && status != .completed && status != .dropped && status != .onHold
        }
    }
}

struct SmartLibraryView: View {
    @EnvironmentObject private var library: LibraryStore
    @StateObject private var model = SmartLibraryModel()
    @ObservedObject private var updater = WatchingUpdater.shared

    private var unwatched: [Title] {
        var seen = Set<Int>()
        return (library.watchingTitles + library.history.map(\.title)).filter { updater.newCount($0.id) > 0 && seen.insert($0.id).inserted }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                todaySection
                if !unwatched.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        SectionTitle("Непросмотренные серии")
                        ScrollView(.horizontal, showsIndicators: false) {
                            LazyHStack(alignment: .top, spacing: 12) {
                                ForEach(unwatched) { t in
                                    NavigationLink(value: t) { PosterCard(title: t).frame(width: 118) }
                                        .buttonStyle(PressableStyle())
                                }
                            }
                            .padding(.horizontal)
                        }
                    }
                }
                recommendationsSection
                Shelf(title: "Продолжения франшиз", titles: model.continuations)
                abandonedSection
            }
            .padding(.vertical)
        }
        .styledBackground()
        .navigationTitle("Для меня")
        .task { await model.load(library: library) }
        .refreshable {
            await WatchingUpdater.shared.refresh(library: library, force: true)
            await model.load(library: library)
        }
    }

    // MARK: Today

    @ViewBuilder private var todaySection: some View {
        let cont = library.history.first { $0.progress < 0.92 } ?? library.history.first
        let planned = library.titles(in: .planned)
        let pick = planned.isEmpty ? nil : planned[(Calendar.current.ordinality(of: .day, in: .era, for: Date()) ?? 0) % planned.count]
        VStack(alignment: .leading, spacing: 12) {
            SectionTitle("Что посмотреть сегодня")
            VStack(spacing: 10) {
                if let cont {
                    NavigationLink(value: cont.title) {
                        todayRow(icon: "play.fill", tint: Theme.accent, title: cont.title.title,
                                 subtitle: cont.progress > 0.92 ? "Дальше — серия \((Int(cont.episodeNumber) ?? 0) + 1)"
                                     : "Продолжить серию \(cont.episodeNumber)", url: cont.title.thumbURL)
                    }
                }
                ForEach(model.airingToday.prefix(4)) { a in
                    NavigationLink(value: ShikiRoute(shikiId: a.shikiId, names: [a.name, a.russian ?? ""])) {
                        todayRow(icon: "bell.fill", tint: .orange, title: a.title,
                                 subtitle: "Серия \(a.nextEpisode) выходит в \(a.nextEpisodeAt.formatted(date: .omitted, time: .shortened))",
                                 url: a.image)
                    }
                }
                if let first = unwatched.first {
                    NavigationLink(value: first) {
                        todayRow(icon: "sparkles", tint: .green, title: first.title,
                                 subtitle: "Новых серий: \(updater.newCount(first.id))", url: first.thumbURL)
                    }
                }
                if let pick {
                    NavigationLink(value: pick) {
                        todayRow(icon: "calendar.badge.plus", tint: .blue, title: pick.title,
                                 subtitle: "Из «Запланировано» — выбор дня", url: pick.thumbURL)
                    }
                }
                if cont == nil && model.airingToday.isEmpty && unwatched.isEmpty && pick == nil {
                    Text("Начните что-нибудь смотреть или добавьте тайтлы в «Запланировано» — здесь появятся советы на день.")
                        .font(.subheadline).foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal)
        }
    }

    private func todayRow(icon: String, tint: Color, title: String, subtitle: String, url: URL?) -> some View {
        HStack(spacing: 12) {
            PosterImage(url: url, radius: 8).frame(width: 46)
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary).lineLimit(2)
                Label(subtitle, systemImage: icon).font(.caption).foregroundStyle(tint).lineLimit(1)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
        }
        .padding(10)
        .cardStyle()
    }

    // MARK: Recommendations

    @ViewBuilder private var recommendationsSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionTitle("По вашим оценкам")
            if model.loadingRecs && model.recommendations.isEmpty {
                ShelfSkeleton()
            } else if let note = model.recsNote {
                Text(note).font(.subheadline).foregroundStyle(.secondary).padding(.horizontal)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 12) {
                    ForEach(model.recommendations) { p in
                        NavigationLink(value: ShikiRoute(shikiId: p.id, names: [p.name, p.russian ?? ""])) {
                            VStack(alignment: .leading, spacing: 5) {
                                PosterImage(url: p.image)
                                    .overlay(alignment: .topTrailing) {
                                        if let s = p.score, s > 0 { ScoreBadge(score: s).padding(6) }
                                    }
                                Text(p.title).font(.footnote.weight(.semibold)).foregroundStyle(.primary)
                                    .lineLimit(2, reservesSpace: true)
                                Text("Похоже на «\(p.because.first ?? "")»").font(.caption2).foregroundStyle(.secondary).lineLimit(2)
                            }
                            .frame(width: 118)
                        }
                        .buttonStyle(PressableStyle())
                    }
                }
                .padding(.horizontal)
            }
        }
    }

    // MARK: Abandoned

    @ViewBuilder private var abandonedSection: some View {
        let list = library.abandoned
        if !list.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                SectionTitle("Заброшенные")
                Text("Начали больше месяца назад и не досмотрели. Вернуться или убрать с глаз?")
                    .font(.footnote).foregroundStyle(.secondary).padding(.horizontal)
                VStack(spacing: 10) {
                    ForEach(list.prefix(12)) { e in
                        HStack(spacing: 12) {
                            NavigationLink(value: e.title) {
                                HStack(spacing: 12) {
                                    PosterImage(url: e.title.thumbURL, radius: 8).frame(width: 46)
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(e.title.title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary).lineLimit(2)
                                        Text("Серия \(e.episodeNumber) · \(e.date.formatted(.relative(presentation: .named)))")
                                            .font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                            }
                            Spacer(minLength: 0)
                            Menu {
                                Button { library.setBookmark(title: e.title, status: .onHold) } label: {
                                    Label("Отложить", systemImage: "pause.circle")
                                }
                                Button(role: .destructive) { library.setBookmark(title: e.title, status: .dropped) } label: {
                                    Label("Бросить", systemImage: "xmark.circle")
                                }
                                Button { library.removeFromHistory(e) } label: {
                                    Label("Убрать из истории", systemImage: "eye.slash")
                                }
                            } label: {
                                Image(systemName: "ellipsis.circle").font(.title3)
                            }
                        }
                        .padding(10)
                        .cardStyle()
                    }
                }
                .padding(.horizontal)
            }
        }
    }
}
