import SwiftUI
import UserNotifications
import CoreSpotlight

@main
struct AnixQApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var library = LibraryStore()
    @StateObject private var downloads = DownloadManager.shared
    @AppStorage("accent") private var accentRaw = AccentChoice.rose.rawValue
    @AppStorage(AppStyle.storageKey) private var styleRaw = AppStyle.classic.rawValue
    @AppStorage("appearance") private var appearance = "dark"
    private var colorScheme: ColorScheme? {
        if AppStyle(rawValue: styleRaw) == .oled { return .dark }
        switch appearance {
        case "light": return .light
        case "system": return nil
        default: return .dark
        }
    }
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
                // Style colours are read statically, so rebuild the UI when the style changes.
                .id(styleRaw == AppStyle.material.rawValue || styleRaw == AppStyle.glass.rawValue
                    ? styleRaw + accentRaw : styleRaw)
                .tint(AppStyle(rawValue: styleRaw) == .material ? AppStyle.tones.primary : accent.color)
                .environmentObject(library)
                .environmentObject(downloads)
                .preferredColorScheme(colorScheme)
                .task { AdBlocker.shared.warmUp() }
        }
        .onChange(of: scenePhase) { phase in
            AppLock.shared.sceneChanged(phase)
            if phase == .background {
                WatchingUpdater.scheduleBackgroundRefresh()
                DownloadManager.shared.cleanupWatched()
                BackupManager.autoBackupIfNeeded(library: library)
            }
            guard phase == .active else { return }
            Task {
                if LibSession.isLoggedIn {
                    if LibSession.userId == nil { await LibAPI.loadProfile() }
                    await library.syncBookmarks()
                }
                await library.applyWidgetActions()
                await EpisodeCountdown.refresh(library: library)
                await CalendarSync.sync(library: library)
                CloudSync.shared.start(library: library)
                await UpdateChecker.shared.check()
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
    @ObservedObject private var sharePlay = SharePlayManager.shared
    @ObservedObject private var lock = AppLock.shared
    @ObservedObject private var room = WatchRoom.shared
    @State private var sharedLaunch: WatchTogether?
    @State private var sharedList: UserCollection?
    /// Existing users (anything in the library) skip the introduction.
    @State private var onboarded = UserDefaults.standard.bool(forKey: "onboarding.done")
        || UserDefaults.standard.string(forKey: "screenshotSlug") != nil

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
                    } else if url.host == "list", let token = url.pathComponents.dropFirst().first,
                              let list = UserDataStore.decode(token) {
                        sharedList = list
                    } else if url.host == "room", let id = url.pathComponents.dropFirst().first {
                        // Watch room invite (anixq://room/<id>, opened from the site link).
                        room.join(id)
                    } else if url.host == "continue" {
                        // Control Center button.
                        if let last = library.history.first { deepLink = DeepLink(slug: last.title.slugURL) }
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
                .onAppear {
                    handlePendingAction()
                    sharePlay.listen()
                    if lock.locked { AppLock.shared.obscured = false }   // creates the cover window at launch
                }
                // SharePlay started by someone else on the FaceTime call.
                .onReceive(sharePlay.$incoming) { a in
                    guard let a else { return }
                    sharePlay.incoming = nil
                    sharedLaunch = a
                }
                .onReceive(room.$incoming) { a in
                    guard let a, sharedLaunch?.slug != a.slug else { return }
                    room.incoming = nil
                    sharedLaunch = a
                }
                .alert("Комната", isPresented: Binding(get: { room.message != nil }, set: { if !$0 { room.message = nil } })) {
                    Button("OK", role: .cancel) {}
                } message: { Text(room.message ?? "") }
                .fullScreenCover(isPresented: Binding(get: { !onboarded }, set: { onboarded = !$0 })) {
                    OnboardingView(done: $onboarded).environmentObject(library)
                }
                .onAppear {
                    if !onboarded, !library.history.isEmpty || !library.bookmarks.isEmpty {
                        onboarded = true
                        UserDefaults.standard.set(true, forKey: "onboarding.done")
                    }
                }
                .sheet(item: $sharedList) { list in SharedCollectionView(collection: list).environmentObject(library) }
                .fullScreenCover(item: $sharedLaunch) { a in
                    SharePlayLauncher(activity: a).environmentObject(library)
                }
                // Handoff: the episode another device of the user is playing, at the same moment.
                .onContinueUserActivity(HandoffSeek.activityType) { activity in
                    let i = activity.userInfo ?? [:]
                    guard let slug = i["slug"] as? String, let eid = i["episodeId"] as? Int, let tid = i["teamId"] as? Int else { return }
                    if let pos = i["position"] as? Double { HandoffSeek.set(episodeId: eid, position: pos) }
                    sharedLaunch = WatchTogether(slug: slug, titleName: i["titleName"] as? String ?? "", episodeId: eid,
                                                 episodeNumber: i["episodeNumber"] as? String ?? "", teamId: tid)
                }
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

    @Environment(\.horizontalSizeClass) private var hSize

    @ViewBuilder
    private var tabs: some View {
        Group {
            if Layout.isPad && hSize == .regular && screenshotSlug == nil {
                SidebarRoot(tab: $tab)
            } else {
                tabView
            }
        }
        .background(KeyboardShortcuts(tab: $tab))
    }

    private var tabView: some View {
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
                AnyPlayerScreen(title: model.displayTitle, background: nil, episodes: model.episodes,
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
                // The old player opens in landscape, the new one in portrait: show the other orientation.
                let to: OrientationController.Target = PlayerVersion.useNew ? .landscape : .portrait
                OrientationController.set(to)
                DebugLog.log("autoplay: rotate to \(to == .landscape ? "landscape" : "portrait")")
            }
        }
    }
}
