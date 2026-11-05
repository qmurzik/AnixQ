import SwiftUI
import UserNotifications
import CoreSpotlight

@main
struct AnixQApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var library = LibraryStore()
    @StateObject private var downloads = DownloadManager.shared
    @AppStorage("accent") private var accentRaw = AccentChoice.rose.rawValue
    private var accent: AccentChoice { AccentChoice(rawValue: accentRaw) ?? .rose }
    @Environment(\.scenePhase) private var scenePhase

    init() {
        CrashReporter.install()
        WatchingUpdater.registerBackgroundTask { LibraryStore.current }
        URLCache.shared = URLCache(memoryCapacity: 32 * 1024 * 1024, diskCapacity: 128 * 1024 * 1024)
        Theme.configureAppearance()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .tint(accent.color)
                .environmentObject(library)
                .environmentObject(downloads)
                .preferredColorScheme(.dark)
                .task { AdBlocker.shared.warmUp() }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .background { WatchingUpdater.scheduleBackgroundRefresh() }
            guard phase == .active else { return }
            Task {
                if LibSession.isLoggedIn {
                    if LibSession.userId == nil { await LibAPI.loadProfile() }
                    await library.syncBookmarks()
                }
                if ShikiSession.isLoggedIn { await library.importShikimori() }
                await WatchingUpdater.shared.refresh(library: library)
                await EpisodeNotifier.reschedule(titles: library.watchingTitles)
            }
        }
    }
}

enum AppTab: Int { case home, browse, schedule, library, search }

struct RootView: View {
    // CI screenshot hooks: `-screenshotTab 1`, `-screenshotSlug <slug>`, `-autoplaySlug <slug>`.
    @State private var tab = AppTab(rawValue: UserDefaults.standard.integer(forKey: "screenshotTab")) ?? .home
    private let screenshotSlug = UserDefaults.standard.string(forKey: "screenshotSlug")
    private let autoplaySlug = UserDefaults.standard.string(forKey: "autoplaySlug")

    @State private var deepLink: DeepLink?
    @State private var shikiLink: ShikiLink?
    @EnvironmentObject private var library: LibraryStore
    @ObservedObject private var network = NetworkMonitor.shared

    /// Actions requested by Siri / Shortcuts while the app was closed.
    private func handlePendingAction() {
        guard UserDefaults.standard.string(forKey: "pendingAction") == "continue" else { return }
        UserDefaults.standard.removeObject(forKey: "pendingAction")
        if let last = library.history.first { deepLink = DeepLink(slug: last.title.slugURL) }
    }

    var body: some View {
        if let slug = autoplaySlug {
            DebugAutoplayView(slug: slug)
        } else {
            tabs
                // anixq://title/<slug> (widgets), anixq://schedule
                .onOpenURL { url in
                    guard url.scheme == "anixq" else { return }
                    if url.host == "title", let slug = url.pathComponents.dropFirst().first {
                        deepLink = DeepLink(slug: slug)
                    } else if url.host == "schedule" {
                        tab = .schedule
                    } else if url.host == "shiki" || url.host == "plan",
                              let id = url.pathComponents.dropFirst().first.flatMap({ Int($0) }) {
                        // From the share extension "Что за аниме?".
                        let q = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
                        let names = ["o", "n"].compactMap { k in q.first { $0.name == k }?.value }
                        let route = ShikiRoute(shikiId: id, names: names)
                        if url.host == "plan" {
                            Task {
                                if let t = await LibAPI.title(shikiId: id, names: names) {
                                    library.setBookmark(title: t, status: .planned)
                                }
                            }
                        }
                        shikiLink = ShikiLink(route: route)
                    }
                }
                .overlay(alignment: .bottom) {
                    if !network.isOnline {
                        Button { tab = .library } label: {
                            Label("Нет подключения · смотрите загрузки", systemImage: "wifi.slash")
                                .font(.footnote.weight(.semibold))
                                .padding(.horizontal, 14).padding(.vertical, 9)
                                .background(.ultraThinMaterial, in: Capsule())
                        }
                        .foregroundStyle(.primary)
                        .padding(.bottom, 58)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }
                .animation(.spring(response: 0.35), value: network.isOnline)
                .onReceive(NotificationCenter.default.publisher(for: .anixqPendingAction)) { _ in handlePendingAction() }
                .onAppear { handlePendingAction() }
                .onContinueUserActivity(CSSearchableItemActionType) { activity in
                    if let id = activity.userInfo?[CSSearchableItemActivityIdentifier] as? String,
                       id.hasPrefix("title:") {
                        deepLink = DeepLink(slug: String(id.dropFirst(6)))
                    }
                }
                .sheet(item: $shikiLink) { link in
                    NavigationStack {
                        ResolveShikiView(route: link.route)
                            .appDestinations()
                            .toolbar {
                                ToolbarItem(placement: .cancellationAction) {
                                    Button("Закрыть") { shikiLink = nil }
                                }
                            }
                    }
                }
                .sheet(item: $deepLink) { link in
                    NavigationStack {
                        DebugTitleLoader(slug: link.slug)
                            .appDestinations()
                            .toolbar {
                                ToolbarItem(placement: .cancellationAction) {
                                    Button("Закрыть") { deepLink = nil }
                                }
                            }
                    }
                }
        }
    }

    private var tabs: some View {
        TabView(selection: $tab) {
            NavigationStack {
                if let slug = screenshotSlug {
                    DebugTitleLoader(slug: slug).appDestinations()
                } else {
                    HomeView()
                }
            }
            .tabItem { Label("Главная", systemImage: "play.rectangle.on.rectangle.fill") }
            .tag(AppTab.home)

            NavigationStack { BrowseView() }
                .tabItem { Label("Каталог", systemImage: "square.grid.2x2.fill") }
                .tag(AppTab.browse)

            NavigationStack { ScheduleView() }
                .tabItem { Label("Расписание", systemImage: "calendar") }
                .tag(AppTab.schedule)

            NavigationStack { LibraryView() }
                .tabItem { Label("Медиатека", systemImage: "rectangle.stack.fill") }
                .tag(AppTab.library)

            NavigationStack { SearchView() }
                .tabItem { Label("Поиск", systemImage: "magnifyingglass") }
                .tag(AppTab.search)
        }
    }
}

/// Opens a title page directly (CI screenshots).
struct DeepLink: Identifiable {
    let slug: String
    var id: String { slug }
}

struct ShikiLink: Identifiable {
    let route: ShikiRoute
    var id: Int { route.shikiId }
}

struct DebugTitleLoader: View {
    let slug: String
    @State private var title: Title?

    var body: some View {
        Group {
            if let title { TitleView(title: title) } else { ProgressView() }
        }
        .task { title = try? await LibAPI.detail(slug).title }
    }
}

/// CI player test: `-autoplaySlug 14421--kimetsu-no-yaiba-anime -autoplayTeam AniLibria -autoLandscapeAfter 40`.
struct DebugAutoplayView: View {
    let slug: String
    @StateObject private var model: TitleModel
    @State private var ready = false

    init(slug: String) {
        self.slug = slug
        _model = StateObject(wrappedValue: TitleModel(title: DebugAutoplayView.placeholder(slug)))
    }

    static func placeholder(_ slug: String) -> Title {
        Title(json: ["id": Int(slug.prefix { $0.isNumber }) ?? 0, "slug_url": slug, "rus_name": slug])!
    }

    var body: some View {
        Group {
            if ready, let team = model.selectedTeam, !model.episodes.isEmpty {
                PlayerScreen(title: model.displayTitle, background: nil, episodes: model.episodes,
                             teams: model.teams, startIndex: 0, team: team)
            } else {
                ProgressView()
            }
        }
        .task {
            DebugLog.log("autoplay: \(slug)")
            await model.loadEpisodes(lastTeamId: nil)
            if let name = UserDefaults.standard.string(forKey: "autoplayTeam"),
               let t = model.teams.first(where: { $0.team.name.localizedCaseInsensitiveContains(name) }) {
                model.select(t)
            }
            DebugLog.log("autoplay: teams=\(model.teams.map { "\($0.team.name)\($0.native ? "*" : "")" }) selected=\(model.selectedTeam?.team.name ?? "-")")
            ready = true
            let after = UserDefaults.standard.integer(forKey: "autoLandscapeAfter")
            if after > 0 {
                try? await Task.sleep(nanoseconds: UInt64(after) * 1_000_000_000)
                OrientationController.set(.portrait)
                DebugLog.log("autoplay: rotate to portrait")
            }
        }
    }
}
