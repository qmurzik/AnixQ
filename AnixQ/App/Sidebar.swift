import SwiftUI

/// iPad in a regular-width window: a sidebar instead of the tab bar, with the library folders
/// as their own entries (a two-column library: folders on the left, content on the right).
enum SidebarItem: Hashable {
    case tab(AppTab)
    case smart, season, downloads, friends, stats
    case folder(BookmarkStatus)

    /// Screens that register their own navigation destinations.
    var hasOwnDestinations: Bool {
        if case .tab = self { return true }
        return false
    }
}

struct SidebarRoot: View {
    @Binding var tab: AppTab
    @State private var selection: SidebarItem? = .tab(.home)
    @EnvironmentObject private var library: LibraryStore
    @ObservedObject private var lock = AppLock.shared

    var body: some View {
        NavigationSplitView {
            List(selection: $selection) {
                Section("Обзор") {
                    row("Главная", "play.rectangle.on.rectangle.fill", .tab(.home))
                    row("Каталог", "square.grid.2x2.fill", .tab(.browse))
                    row("Сезон", "leaf.fill", .season)
                    row("Расписание", "calendar", .tab(.schedule))
                    row("Поиск", "magnifyingglass", .tab(.search))
                }
                Section("Медиатека") {
                    row("Для меня", "sparkles", .smart)
                    row("Вся медиатека", "rectangle.stack.fill", .tab(.library))
                    row("Загрузки", "arrow.down.circle", .downloads)
                    row("Друзья", "person.2", .friends)
                    row("Статистика", "chart.bar.xaxis", .stats)
                }
                Section("Закладки") {
                    ForEach(BookmarkStatus.allCases) { s in
                        Label {
                            HStack {
                                Text(s.title)
                                Spacer()
                                if lock.isLocked(s) {
                                    Image(systemName: "lock.fill").font(.caption).foregroundStyle(.secondary)
                                } else {
                                    Text("\(library.titles(in: s).count)").font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        } icon: { Image(systemName: s.icon) }
                        .tag(SidebarItem.folder(s))
                    }
                }
            }
            .navigationTitle("AnixQ")
        } detail: {
            NavigationStack {
                detail(selection ?? .tab(.home))
            }
            .id(selection)
        }
        .onChange(of: tab) { selection = .tab($0) }
        .onChange(of: selection) { s in if case .tab(let t) = s, t != tab { tab = t } }
    }

    private func row(_ title: String, _ icon: String, _ item: SidebarItem) -> some View {
        Label(title, systemImage: icon).tag(item)
    }

    @ViewBuilder
    private func detail(_ item: SidebarItem) -> some View {
        let content = Group {
            switch item {
            case .tab(.home): HomeView()
            case .tab(.browse): BrowseView()
            case .tab(.schedule): ScheduleView()
            case .tab(.library): LibraryView()
            case .tab(.search): SearchView()
            case .smart: SmartLibraryView()
            case .season: SeasonView()
            case .downloads: DownloadsView()
            case .friends: FriendsView()
            case .stats: StatsView()
            case .folder(let s): LockGate(folder: s) { BookmarkFolderView(status: s) }
            }
        }
        if item.hasOwnDestinations {
            content
        } else {
            content.appDestinations().libraryDestinations()
        }
    }
}

/// Hardware keyboard: ⌘1…⌘5 switch sections, ⌘F opens search.
struct KeyboardShortcuts: View {
    @Binding var tab: AppTab

    var body: some View {
        ZStack {
            ForEach(Array([AppTab.home, .browse, .schedule, .library, .search].enumerated()), id: \.offset) { i, t in
                Button("") { tab = t }.keyboardShortcut(KeyEquivalent(Character(String(i + 1))), modifiers: .command)
            }
            Button("") { tab = .search }.keyboardShortcut("f", modifiers: .command)
        }
        .opacity(0)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
