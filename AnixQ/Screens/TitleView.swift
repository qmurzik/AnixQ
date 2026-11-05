import SwiftUI

struct TeamOption: Identifiable, Hashable {
    let team: LibTeam
    var native: Bool
    var kodik: Bool
    var id: Int { team.id }
}

struct PlayRequest: Identifiable {
    let episodeIndex: Int
    let team: TeamOption
    let id = UUID()
}

@MainActor
final class TitleModel: ObservableObject {
    let title: Title
    @Published var detail: TitleDetail?
    @Published var episodes: [LibEpisode] = []
    @Published var teams: [TeamOption] = []
    @Published var selectedTeam: TeamOption?
    @Published var related: [RelatedTitle] = []
    @Published var similar: [Title] = []
    @Published var shiki: ShikiAnimeInfo?
    @Published var characters: [ShikiPerson] = []
    @Published var videos: [ShikiVideo] = []
    @Published var franchise: [FranchiseNode] = []
    @Published var franchiseLoading = false
    @Published var staff: [ShikiPerson] = []
    @Published var loadingEpisodes = true
    @Published var error: String?

    static var preferredTeamName: String? {
        get { UserDefaults.standard.string(forKey: "preferredTeam") }
        set { UserDefaults.standard.set(newValue, forKey: "preferredTeam") }
    }

    init(title: Title) { self.title = title }

    // MARK: Cache — reopening a recent title is instant.

    private static var cache: [String: TitleModel] = [:]
    private static var cacheOrder: [String] = []

    static func shared(for title: Title) -> TitleModel {
        if let m = cache[title.slugURL] {
            cacheOrder.removeAll { $0 == title.slugURL }
            cacheOrder.append(title.slugURL)
            return m
        }
        let m = TitleModel(title: title)
        cache[title.slugURL] = m
        cacheOrder.append(title.slugURL)
        if cacheOrder.count > 12 { cache[cacheOrder.removeFirst()] = nil }
        return m
    }

    var displayTitle: Title { detail?.title ?? title }
    private(set) var loadedAt: Date?

    /// Every block is filled as soon as its own request finishes (header first, Shikimori in parallel).
    /// Independent tasks instead of `async let` (avoids a Swift runtime task-allocator crash).
    func load(lastTeamId: Int?, force: Bool = false) async {
        if !force, let loadedAt, Date().timeIntervalSince(loadedAt) < 600, !episodes.isEmpty { return }
        error = nil
        let slug = title.slugURL
        // Paint instantly from the last visit, then refresh from the network.
        if detail == nil { detail = LibAPI.cachedDetail(slug) }
        if episodes.isEmpty {
            let cached = LibAPI.cachedEpisodes(slug)
            if !cached.isEmpty { episodes = cached }
        }
        let header = Task { [weak self] in
            let d = try? await LibAPI.detail(slug)
            await MainActor.run { if let d { self?.detail = d } }
            await self?.loadShikimori()
        }
        let extras = Task { [weak self] in
            let r = await LibAPI.related(slug)
            await MainActor.run { self?.related = r }
            let s = await LibAPI.similar(slug)
            await MainActor.run { self?.similar = s }
        }
        await loadEpisodes(lastTeamId: lastTeamId)
        await header.value
        await extras.value
        if error == nil { loadedAt = Date() }
    }

    private func loadShikimori() async {
        guard let sid = (detail?.title ?? title).shikiId else { return }
        let info = Task { try? await ShikiAPI.anime(sid) }
        let roles = Task { await ShikiAPI.roles(sid) }
        let vids = Task { await ShikiAPI.videos(sid) }
        let fr = Task { [weak self] in await self?.loadFranchise(shikiId: sid) }
        shiki = await info.value
        let r = await roles.value
        characters = r.characters
        staff = r.staff
        videos = await vids.value
        await fr.value
    }

    func loadEpisodes(lastTeamId: Int?) async {
        loadingEpisodes = true
        defer { loadingEpisodes = false }
        do {
            episodes = try await LibAPI.episodes(slug: title.slugURL)
            guard let first = episodes.first else { return }
            // Teams can differ between early and recent episodes, so look at both ends.
            let firstId = first.id
            let lastId: Int? = episodes.count > 1 ? episodes[episodes.count - 1].id : nil
            let lastTask = Task { await Self.players(lastId) }
            let firstPlayers = try await LibAPI.players(episodeId: firstId)
            let players = firstPlayers + (await lastTask.value)
            var order: [TeamOption] = []
            for p in players {
                if let i = order.firstIndex(where: { $0.team.id == p.team.id }) {
                    order[i].native = order[i].native || p.isNative
                    order[i].kodik = order[i].kodik || p.isKodik
                } else {
                    order.append(TeamOption(team: p.team, native: p.isNative, kodik: p.isKodik))
                }
            }
            // Ad-free first, then dubs before subtitles.
            teams = order.filter { $0.native && !$0.team.isSubtitles } + order.filter { $0.native && $0.team.isSubtitles }
                + order.filter { !$0.native }
            selectedTeam = teams.first { $0.id == lastTeamId }
                ?? teams.first { $0.team.name == Self.preferredTeamName && $0.native }
                ?? teams.first
            if let t = selectedTeam, t.native { LibAPI.warmUp(episodeId: first.id, teamId: t.id) }
        } catch {
            self.error = error.localizedDescription
        }
    }

    func loadFranchise(shikiId: Int) async {
        guard franchise.isEmpty, !franchiseLoading else { return }
        franchiseLoading = true
        let nodes = await ShikiAPI.franchise(shikiId)
        franchise = nodes.count > 1 ? nodes : []
        franchiseLoading = false
    }

    private static func players(_ id: Int?) async -> [LibPlayer] {
        guard let id else { return [] }
        return (try? await LibAPI.players(episodeId: id)) ?? []
    }

    func select(_ team: TeamOption) {
        selectedTeam = team
        Self.preferredTeamName = team.team.name
        if team.native, let e = episodes.first { LibAPI.warmUp(episodeId: e.id, teamId: team.id) }
    }
}

struct TitleView: View {
    @StateObject private var model: TitleModel
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var downloads: DownloadManager
    @ObservedObject private var shikiSync = ShikiSync.shared
    @State private var play: PlayRequest?
    @State private var expanded = false
    @State private var showAllEpisodes = false
    @State private var showFullFranchise = false
    @State private var scrolledPastHeader = false

    init(title: Title) {
        _model = StateObject(wrappedValue: TitleModel.shared(for: title))
    }

    private var t: Title { model.displayTitle }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 26) {
                header
                VStack(alignment: .leading, spacing: 26) {
                    if let summary = model.detail?.summary {
                        about(summary).frame(maxWidth: Layout.maxTextWidth, alignment: .leading)
                    }
                    episodesSection
                    infoSection
                }
                franchiseSection
                videoSection("Трейлеры", model.videos.filter { !$0.isMusic })
                videoSection("Опенинги и эндинги", model.videos.filter(\.isMusic))
                peopleSection("Персонажи", model.characters, round: true)
                peopleSection("Создатели", model.staff, round: false)
                relatedSection
                Shelf(title: "Похожие", titles: model.similar)
            }
            .padding(.bottom, 30)
        }
        .coordinateSpace(name: "titleScroll")
        .onPreferenceChange(HeaderOffsetKey.self) { y in
            let past = y < -(Layout.headerHeight - 140)
            if past != scrolledPastHeader { withAnimation(.easeInOut(duration: 0.2)) { scrolledPastHeader = past } }
        }
        .ignoresSafeArea(edges: .top)
        .navigationBarTitleDisplayMode(.inline)
        .navigationTitle(scrolledPastHeader ? t.title : "")
        .toolbarBackground(scrolledPastHeader ? .visible : .hidden, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                ShareLink(item: URL(string: "https://anilib.me/ru/anime/\(t.slugURL)")!) {
                    Image(systemName: "square.and.arrow.up")
                }
            }
        }
        .task {
            await model.load(lastTeamId: library.lastEntry(titleId: t.id)?.teamId)
            if let g = model.detail?.genres.map(\.name) { library.recordGenres(titleId: t.id, names: g) }
        }
        .refreshable { await model.load(lastTeamId: library.lastEntry(titleId: t.id)?.teamId, force: true) }
        .fullScreenCover(item: $play) { req in
            PlayerScreen(title: t, background: model.detail?.backgroundURL, episodes: model.episodes,
                         teams: model.teams, startIndex: req.episodeIndex, team: req.team)
        }
        .sheet(isPresented: $showAllEpisodes) { allEpisodesSheet }
    }

    // MARK: Header

    private var header: some View {
        ZStack(alignment: .bottom) {
            RemoteImage(url: model.detail?.backgroundURL ?? t.coverURL)
                .frame(height: Layout.headerHeight)
                .frame(maxWidth: .infinity)
                .blur(radius: model.detail?.backgroundURL == nil ? 18 : 0)
                .clipped()
            LinearGradient(stops: [
                .init(color: .black.opacity(0.35), location: 0),
                .init(color: .clear, location: 0.3),
                .init(color: Theme.background.opacity(0.8), location: 0.72),
                .init(color: Theme.background, location: 1)
            ], startPoint: .top, endPoint: .bottom)

            VStack(spacing: 10) {
                if model.detail?.backgroundURL == nil {
                    PosterImage(url: t.coverURL, radius: 12).frame(width: 130).shadow(radius: 16)
                }
                Text(t.title)
                    .font(.system(size: 28, weight: .bold))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
                Text(metaLine).font(.footnote.weight(.medium)).foregroundStyle(.secondary)
                    .multilineTextAlignment(.center).padding(.horizontal)
                playButtons.padding(.horizontal).padding(.top, 6).frame(maxWidth: 560)
            }
            .padding(.bottom, 8)
        }
        .frame(height: Layout.headerHeight)
        .background(GeometryReader { g in
            Color.clear.preference(key: HeaderOffsetKey.self, value: g.frame(in: .named("titleScroll")).minY)
        })
    }

    private var metaLine: String {
        var parts: [String] = []
        if let s = t.score { parts.append("★ " + String(format: "%.1f", s)) }
        if let type = t.typeLabel { parts.append(type) }
        if let y = t.year { parts.append(String(y)) }
        if !model.episodes.isEmpty { parts.append("\(model.episodes.count) эп.") }
        if let a = t.age { parts.append(a) }
        if let g = model.detail?.genres.prefix(2).map(\.name), !g.isEmpty { parts.append(g.joined(separator: ", ")) }
        return parts.joined(separator: " · ")
    }

    private var nextEpisodeIndex: Int {
        guard !model.episodes.isEmpty else { return 0 }
        if let last = library.lastEntry(titleId: t.id),
           let i = model.episodes.firstIndex(where: { $0.id == last.episodeId }) {
            if last.progress > 0.92, i + 1 < model.episodes.count { return i + 1 }
            return i
        }
        return model.episodes.firstIndex { !library.isWatched(titleId: t.id, number: $0.number) } ?? 0
    }

    private var playButtons: some View {
        VStack(spacing: 8) {
            HStack(spacing: 10) {
                Button {
                    if let team = model.selectedTeam { play = PlayRequest(episodeIndex: nextEpisodeIndex, team: team) }
                } label: {
                    HStack(spacing: 8) {
                        if model.loadingEpisodes { ProgressView().tint(.black) } else { Image(systemName: "play.fill") }
                        Text(playLabel)
                    }
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(model.selectedTeam == nil)

                BookmarkMenu(title: t) {
                    Image(systemName: library.bookmark(for: t.slugURL) == nil ? "plus" : "checkmark")
                }
                .buttonStyle(GlassButtonStyle())
                .frame(width: 50)
            }
            if let b = library.bookmark(for: t.slugURL) {
                Label(b.title, systemImage: b.icon).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private var playLabel: String {
        guard !model.episodes.isEmpty else { return model.loadingEpisodes ? "Загрузка" : "Нет серий" }
        let ep = model.episodes[nextEpisodeIndex]
        if library.resumePosition(episodeId: ep.id) != nil { return "Продолжить · серия \(ep.number)" }
        return model.episodes.count == 1 ? "Смотреть" : "Смотреть · серия \(ep.number)"
    }

    // MARK: About

    private func about(_ summary: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(summary)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(expanded ? nil : 4)
            Button(expanded ? "Свернуть" : "Ещё") { withAnimation { expanded.toggle() } }
                .font(.subheadline.weight(.semibold))
            if let genres = model.detail?.genres, !genres.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(genres) { g in
                            NavigationLink(value: GenreRoute(genre: g)) {
                                Text(g.name).font(.caption.weight(.semibold))
                                    .padding(.horizontal, 10).padding(.vertical, 6)
                                    .background(.thinMaterial, in: Capsule())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
        .padding(.horizontal)
    }

    // MARK: Episodes

    private var episodesSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionTitle(title: "Серии") {
                HStack(spacing: 14) {
                    if let team = model.selectedTeam, team.native, !model.episodes.isEmpty {
                        Menu {
                            Button {
                                model.episodes.forEach { downloads.download(title: t, episode: $0, team: team.team) }
                            } label: { Label("Скачать все серии", systemImage: "arrow.down.circle") }
                            Button {
                                model.episodes.filter { !library.isWatched(titleId: t.id, number: $0.number) }
                                    .forEach { downloads.download(title: t, episode: $0, team: team.team) }
                            } label: { Label("Скачать непросмотренные", systemImage: "arrow.down.to.line") }
                        } label: {
                            Image(systemName: "arrow.down.circle")
                        }
                    }
                    if model.episodes.count > 6 {
                        Button("Все \(model.episodes.count)") { showAllEpisodes = true }.font(.subheadline)
                    }
                }
            }
            if !model.teams.isEmpty { teamPicker.padding(.horizontal) }

            if model.loadingEpisodes && model.episodes.isEmpty {
                ProgressView().frame(maxWidth: .infinity, minHeight: 120)
            } else if model.episodes.isEmpty {
                if let e = model.error {
                    ErrorState(message: e) {
                        Task { await model.load(lastTeamId: library.lastEntry(titleId: t.id)?.teamId, force: true) }
                    }
                } else {
                    Text("Серий пока нет").foregroundStyle(.secondary).padding(.horizontal)
                }
            } else {
                ScrollViewReader { proxy in
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(alignment: .top, spacing: 12) {
                            ForEach(Array(model.episodes.enumerated()), id: \.element.id) { i, ep in
                                Button {
                                    if let team = model.selectedTeam { play = PlayRequest(episodeIndex: i, team: team) }
                                } label: {
                                    EpisodeCard(episode: ep, image: model.detail?.backgroundURL ?? t.coverURL,
                                                progress: library.progress(episodeId: ep.id),
                                                watched: library.isWatched(titleId: t.id, number: ep.number))
                                }
                                .buttonStyle(PressableStyle())
                                .id(i)
                                .overlay(alignment: .topLeading) {
                                    if downloads.localFile(episodeId: ep.id, teamId: model.selectedTeam?.id) != nil {
                                        Image(systemName: "arrow.down.circle.fill")
                                            .symbolRenderingMode(.palette)
                                            .foregroundStyle(.white, Theme.accent)
                                            .padding(6)
                                    }
                                }
                                .contextMenu {
                                    watchedToggle(ep)
                                    if let team = model.selectedTeam, team.native {
                                        Button {
                                            downloads.download(title: t, episode: ep, team: team.team)
                                        } label: { Label("Скачать", systemImage: "arrow.down.circle") }
                                    }
                                }
                            }
                        }
                        .padding(.horizontal)
                    }
                    .onAppear { proxy.scrollTo(nextEpisodeIndex, anchor: .leading) }
                    .onChange(of: model.episodes.count) { _ in proxy.scrollTo(nextEpisodeIndex, anchor: .leading) }
                }
            }
        }
    }

    private func watchedToggle(_ ep: LibEpisode) -> some View {
        let watched = library.isWatched(titleId: t.id, number: ep.number)
        return Button {
            library.toggleWatched(title: t, number: ep.number)
        } label: {
            Label(watched ? "Не просмотрено" : "Просмотрено", systemImage: watched ? "eye.slash" : "eye")
        }
    }

    private var teamPicker: some View {
        Menu {
            ForEach(model.teams) { o in
                Button {
                    model.select(o)
                } label: {
                    Label(teamLabel(o), systemImage: o.id == model.selectedTeam?.id ? "checkmark"
                          : (o.team.isSubtitles ? "captions.bubble" : "waveform"))
                }
            }
        } label: {
            HStack(spacing: 8) {
                Image(systemName: model.selectedTeam?.team.isSubtitles == true ? "captions.bubble.fill" : "waveform")
                Text(model.selectedTeam.map(teamLabel) ?? "Озвучка").lineLimit(1)
                Image(systemName: "chevron.up.chevron.down").font(.caption)
            }
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .background(.thinMaterial, in: Capsule())
        }
        .foregroundStyle(.primary)
    }

    private func teamLabel(_ o: TeamOption) -> String {
        (o.native ? "⚡ " : "") + o.team.name + (o.native ? "" : " · Kodik, реклама")
    }

    private var allEpisodesSheet: some View {
        NavigationStack {
            List {
                ForEach(Array(model.episodes.enumerated()), id: \.element.id) { i, ep in
                    Button {
                        showAllEpisodes = false
                        if let team = model.selectedTeam {
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                                play = PlayRequest(episodeIndex: i, team: team)
                            }
                        }
                    } label: {
                        HStack {
                            Text(ep.number).font(.headline.monospacedDigit()).frame(width: 44, alignment: .leading)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(ep.displayTitle).foregroundStyle(.primary)
                                let p = library.progress(episodeId: ep.id)
                                if p > 0.02 { ProgressView(value: p).tint(Theme.accent) }
                            }
                            Spacer()
                            if library.isWatched(titleId: t.id, number: ep.number) {
                                Image(systemName: "checkmark.circle.fill").foregroundStyle(.secondary)
                            }
                            DownloadButton(title: t, episode: ep, team: model.selectedTeam)
                        }
                    }
                    .swipeActions { watchedToggle(ep).tint(.gray) }
                }
            }
            .navigationTitle("Серии")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Готово") { showAllEpisodes = false } } }
        }
        .presentationDetents([.medium, .large])
    }

    // MARK: Info

    private var infoSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionTitle("Информация")
            VStack(spacing: 0) {
                ForEach(infoRows, id: \.0) { row in
                    HStack(alignment: .top) {
                        Text(row.0).foregroundStyle(.secondary)
                        Spacer(minLength: 16)
                        Text(row.1).multilineTextAlignment(.trailing)
                    }
                    .font(.subheadline)
                    .padding(.vertical, 10)
                    if row.0 != infoRows.last?.0 { Divider() }
                }
            }
            .padding(.horizontal, 14)
            .cardStyle()
            .padding(.horizontal)

            if shikiSync.isLoggedIn, let sid = t.shikiId {
                ShikiRateCard(shikiId: sid, totalEpisodes: model.shiki?.episodes ?? model.episodes.count)
                    .id(sid)
                    .padding(.horizontal)
            }
        }
    }

    private var infoRows: [(String, String)] {
        var rows: [(String, String)] = []
        if let o = t.originalTitle { rows.append(("Оригинал", o)) }
        if let s = model.detail?.studios.first { rows.append(("Студия", s)) }
        if let d = model.detail?.duration { rows.append(("Длительность", d)) }
        if let s = t.statusLabel { rows.append(("Статус", s)) }
        if let at = model.shiki?.nextEpisodeAt {
            let f = DateFormatter()
            f.locale = Locale(identifier: "ru_RU")
            f.dateFormat = "d MMMM, HH:mm"
            rows.append(("Следующая серия", f.string(from: at)))
        }
        if let r = t.rating, r > 0 { rows.append(("AnimeLib", String(format: "%.2f · %d голосов", r, t.votes ?? 0))) }
        if let s = model.shiki?.score ?? t.shikiRate, s > 0 { rows.append(("Shikimori", String(format: "%.2f", s))) }
        if let v = model.detail?.views { rows.append(("Просмотры", v)) }
        if let a = model.detail?.authors.first { rows.append(("Автор", a)) }
        return rows
    }

    @ViewBuilder
    private var franchiseSection: some View {
        if !model.franchise.isEmpty {
            let main = model.franchise.filter(\.isMain)
            let visible = showFullFranchise || main.count < 2 ? model.franchise : main
            VStack(alignment: .leading, spacing: 10) {
                SectionTitle(title: "Порядок просмотра") {
                    if model.franchise.count > main.count && main.count >= 2 {
                        Button(showFullFranchise ? "Только основное" : "Все \(model.franchise.count)") {
                            withAnimation { showFullFranchise.toggle() }
                        }
                        .font(.subheadline)
                    }
                }
                VStack(spacing: 0) {
                    ForEach(Array(visible.enumerated()), id: \.element.id) { i, node in
                        FranchiseRow(index: i + 1, node: node, isCurrent: node.shikiId == t.shikiId)
                        if i < visible.count - 1 { Divider().padding(.leading, 90) }
                    }
                }
                .cardStyle()
                .padding(.horizontal)
            }
        }
    }

    @ViewBuilder
    private func franchiseMark(_ title: Title) -> some View {
        let status = library.bookmark(for: title.slugURL)
        if status == .completed {
            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
        } else if status == .watching || library.lastEntry(titleId: title.id) != nil {
            Image(systemName: "play.circle.fill").foregroundStyle(Theme.accent)
        } else if status == .planned {
            Image(systemName: "calendar").foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func videoSection(_ title: String, _ videos: [ShikiVideo]) -> some View {
        if !videos.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                SectionTitle(title)
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: 12) {
                        ForEach(videos) { v in
                            Link(destination: v.url) {
                                VStack(alignment: .leading, spacing: 5) {
                                    ZStack {
                                        RemoteImage(url: v.image)
                                        Image(systemName: "play.circle.fill").font(.largeTitle)
                                            .symbolRenderingMode(.palette).foregroundStyle(.white, .black.opacity(0.4))
                                    }
                                    .frame(width: 200, height: 112)
                                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                    Text(v.name.isEmpty ? v.kindLabel : v.name).font(.footnote.weight(.semibold))
                                        .lineLimit(1).foregroundStyle(.primary)
                                    Text(v.kindLabel).font(.caption2).foregroundStyle(.secondary)
                                }
                                .frame(width: 200, alignment: .leading)
                            }
                        }
                    }
                    .padding(.horizontal)
                }
            }
        }
    }

    @ViewBuilder
    private func peopleSection(_ title: String, _ people: [ShikiPerson], round: Bool) -> some View {
        if !people.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                SectionTitle(title)
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: 14) {
                        ForEach(people) { p in
                            NavigationLink(value: PersonRoute(id: p.id, isCharacter: p.isCharacter)) {
                            VStack(spacing: 6) {
                                Theme.card
                                    .frame(width: 76, height: round ? 76 : 100)
                                    .overlay {
                                        if p.image == nil {
                                            Image(systemName: "person.fill").font(.title).foregroundStyle(.tertiary)
                                        } else {
                                            RemoteImage(url: p.image)
                                        }
                                    }
                                    .clipShape(RoundedRectangle(cornerRadius: round ? 38 : 10, style: .continuous))
                                Text(p.name).font(.caption.weight(.semibold)).lineLimit(2)
                                    .multilineTextAlignment(.center)
                                Text(p.role).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                            }
                            .frame(width: 86)
                            .foregroundStyle(.primary)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal)
                }
            }
        }
    }

    @ViewBuilder
    private var relatedSection: some View {
        if !model.related.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                SectionTitle("Связанные")
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: 12) {
                        ForEach(model.related) { r in
                            NavigationLink(value: r.title) {
                                VStack(alignment: .leading, spacing: 4) {
                                    PosterCard(title: r.title, showMeta: false)
                                    Text(r.relation).font(.caption2).foregroundStyle(Theme.accent)
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
    }
}

struct EpisodeCard: View {
    let episode: LibEpisode
    let image: URL?
    let progress: Double
    let watched: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack(alignment: .bottomLeading) {
                RemoteImage(url: image)
                    .overlay(Color.black.opacity(watched ? 0.55 : 0.3))
                Text(episode.number)
                    .font(.system(size: 34, weight: .bold, design: .rounded))
                    .foregroundStyle(.white.opacity(0.9))
                    .padding(10)
                if progress > 0.02 && progress < 0.95 {
                    GeometryReader { geo in
                        VStack {
                            Spacer()
                            ZStack(alignment: .leading) {
                                Rectangle().fill(.white.opacity(0.3))
                                Rectangle().fill(.white).frame(width: geo.size.width * progress)
                            }
                            .frame(height: 3)
                        }
                    }
                }
            }
            .frame(width: 200, height: 112)
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay(alignment: .topTrailing) {
                if watched {
                    Image(systemName: "checkmark.circle.fill")
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(.white, Theme.accent)
                        .padding(6)
                }
            }
            Text(episode.displayTitle).font(.footnote.weight(.semibold)).lineLimit(1)
                .foregroundStyle(watched ? .secondary : .primary)
        }
        .frame(width: 200, alignment: .leading)
    }
}


/// One franchise entry from Shikimori; the AnimeLib title is looked up lazily (and cached).
struct FranchiseRow: View {
    let index: Int
    let node: FranchiseNode
    let isCurrent: Bool
    @EnvironmentObject private var library: LibraryStore
    @State private var title: Title?
    @State private var missing = false

    var body: some View {
        Group {
            if let title, !isCurrent {
                NavigationLink(value: title) { content }.buttonStyle(.plain)
            } else {
                content
            }
        }
        .task {
            title = LibAPI.cachedTitle(shikiId: node.shikiId)
            if title == nil {
                title = await LibAPI.title(shikiId: node.shikiId, names: [node.name])
                missing = title == nil
            }
        }
    }

    private var content: some View {
        HStack(spacing: 12) {
            Text("\(index)").font(.headline.monospacedDigit()).frame(width: 26)
                .foregroundStyle(isCurrent ? Theme.accent : .secondary)
            PosterImage(url: title?.thumbURL, radius: 6).frame(width: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(title?.title ?? node.name)
                    .font(.subheadline.weight(isCurrent ? .bold : .regular))
                    .lineLimit(2).foregroundStyle(missing ? .secondary : .primary)
                Text([node.kind, node.year.map(String.init), missing ? "нет на AnimeLib" : nil]
                    .compactMap { $0 }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 4)
            if let title { mark(title) }
            if !isCurrent && title != nil {
                Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
            }
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 12)
        .contentShape(Rectangle())
        .background(isCurrent ? Theme.accent.opacity(0.1) : .clear)
    }

    @ViewBuilder
    private func mark(_ title: Title) -> some View {
        let status = library.bookmark(for: title.slugURL)
        if status == .completed {
            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
        } else if status == .watching || library.lastEntry(titleId: title.id) != nil {
            Image(systemName: "play.circle.fill").foregroundStyle(Theme.accent)
        } else if status == .planned {
            Image(systemName: "calendar").foregroundStyle(.secondary)
        }
    }
}


private struct HeaderOffsetKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}
