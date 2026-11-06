import SwiftUI

struct LibraryView: View {
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var downloads: DownloadManager

    private var downloadsSubtitle: String {
        let done = downloads.items.values.filter { $0.state == .done }.count
        let active = downloads.activeCount
        if active > 0 { return "Качается: \(active)" }
        return done > 0 ? "Серий: \(done)" : "Смотрите офлайн"
    }
    @State private var showSettings = false
    @State private var showLibLogin = false
    @ObservedObject private var lock = AppLock.shared

    var body: some View {
        List {
            if !LibSession.isLoggedIn {
                Section {
                    Button { showLibLogin = true } label: {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Войти в AnimeLib").foregroundStyle(.primary)
                                Text("Плеер без рекламы и синхронизация закладок")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        } icon: {
                            Image(systemName: "person.crop.circle.badge.plus")
                        }
                    }
                }
            }

            if library.history.isEmpty && library.bookmarks.isEmpty {
                Section {
                    NavigationLink { BackupView() } label: {
                        Label("Восстановить из резервной копии", systemImage: "clock.arrow.circlepath")
                    }
                } footer: {
                    Text("Переустановили приложение? Верните историю и закладки из файла копии.")
                }
            }

            if let p = library.shikiImport {
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Загружаем список Shikimori: \(p.done) из \(p.total)").font(.subheadline)
                        ProgressView(value: Double(p.done), total: Double(max(p.total, 1)))
                    }
                }
            } else if let summary = library.shikiImportSummary {
                Section { Label(summary, systemImage: "checkmark.circle").font(.footnote) }
            }

            if let e = library.lastSyncError {
                Section { Label(e, systemImage: "exclamationmark.triangle").font(.footnote).foregroundStyle(.orange) }
            }

            Section {
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible())], spacing: 10) {
                    LibraryTile(title: "Для меня", subtitle: "Сегодня и советы", icon: "sparkles", colors: [.pink, .purple], value: SmartRoute())
                    LibraryTile(title: "Загрузки", subtitle: downloadsSubtitle, icon: "arrow.down.circle.fill", colors: [.blue, .cyan],
                                value: LibraryRoute.downloads)
                    LibraryTile(title: "Статистика", subtitle: "Итоги и достижения", icon: "chart.bar.fill", colors: [.orange, .yellow],
                                value: LibraryRoute.stats)
                    LibraryTile(title: "Друзья", subtitle: "Лента Shikimori", icon: "person.2.fill", colors: [.green, .teal],
                                value: LibraryRoute.friends)
                }
                .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                .listRowBackground(Color.clear)
            }

            Section("Закладки") {
                ForEach(BookmarkStatus.allCases) { s in
                    NavigationLink(value: LibraryRoute.folder(s)) {
                        HStack {
                            Label { Text(s.title) } icon: { ColoredIcon(name: s.icon, color: s.color) }
                            Spacer()
                            if lock.isLocked(s) {
                                Image(systemName: "lock.fill").foregroundStyle(.secondary)
                            } else {
                                Text("\(library.titles(in: s).count)").foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }

            CollectionsSection()

            Section {
                if library.history.isEmpty {
                    Text("Здесь появятся тайтлы, которые вы начнёте смотреть").foregroundStyle(.secondary)
                }
                ForEach(library.history.prefix(30)) { entry in
                    NavigationLink(value: entry.title) {
                        HStack(spacing: 12) {
                            PosterImage(url: entry.title.thumbURL, radius: 6).frame(width: 46)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(entry.title.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                                Text("Серия \(entry.episodeNumber) · \(entry.teamName)").font(.caption).foregroundStyle(.secondary)
                                ProgressView(value: entry.progress).tint(Theme.accent)
                            }
                        }
                    }
                    .swipeActions {
                        Button(role: .destructive) { library.removeFromHistory(entry) } label: {
                            Label("Удалить", systemImage: "trash")
                        }
                    }
                }
            } header: {
                Text("История")
            }
        }
        .styledList()
        .listStyle(.insetGrouped)
        .navigationTitle("Медиатека")
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                if library.isSyncing { ProgressView() }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { showSettings = true } label: { Image(systemName: "gearshape") }
            }
        }
        .libraryDestinations()
        .sheet(isPresented: $showSettings) { SettingsView() }
        .sheet(isPresented: $showLibLogin) {
            LibLoginView { ok in if ok { Task { await library.syncBookmarks(force: true) } } }
        }
        .appDestinations()
        .refreshable {
            await library.syncBookmarks(force: true)
            await library.importShikimori(force: true)
        }
    }
}

/// Value-based routes for the Library tab. Mixing destination-based NavigationLinks with the
/// value-based title links made SwiftUI push titles onto the wrong level (folder stayed on top).
enum LibraryRoute: Hashable {
    case stats, downloads, friends
    case folder(BookmarkStatus)
    case collection(UUID)
}

extension View {
    func libraryDestinations() -> some View {
        navigationDestination(for: LibraryRoute.self) { route in
            switch route {
            case .stats: StatsView()
            case .downloads: DownloadsView()
            case .friends: FriendsView()
            case .collection(let id): CollectionView(id: id)
            case .folder(let s): LockGate(folder: s) { BookmarkFolderView(status: s) }
            }
        }
    }
}

struct BookmarkFolderView: View {
    let status: BookmarkStatus
    @EnvironmentObject private var library: LibraryStore

    var body: some View {
        ScrollView {
            let titles = library.titles(in: status)
            if titles.isEmpty {
                EmptyState(icon: status.icon, title: "Пусто", subtitle: "Добавляйте тайтлы кнопкой «+» на их странице")
            } else {
                PosterGrid(titles: titles).padding()
            }
        }
        .styledBackground()
        .navigationTitle(status.title)
        .refreshable { await library.syncBookmarks(force: true) }
    }
}

// MARK: - Settings

struct PlayerPrefs {
    static var autoSkipEnding: Bool {
        get { UserDefaults.standard.object(forKey: "player.autoSkipEnding") as? Bool ?? false }
        set { UserDefaults.standard.set(newValue, forKey: "player.autoSkipEnding") }
    }

    static var autoSkipOpening: Bool {
        get { UserDefaults.standard.object(forKey: "player.autoSkip") as? Bool ?? false }
        set { UserDefaults.standard.set(newValue, forKey: "player.autoSkip") }
    }
    static var autoNext: Bool {
        get { UserDefaults.standard.object(forKey: "player.autoNext") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "player.autoNext") }
    }
    static var openingLength: Double {
        get { UserDefaults.standard.object(forKey: "player.opLength") as? Double ?? 85 }
        set { UserDefaults.standard.set(newValue, forKey: "player.opLength") }
    }
    private static let d = UserDefaults.standard
    static var doubleTapStep: Int {
        get { d.object(forKey: "player.tapStep") as? Int ?? 10 }
        set { d.set(newValue, forKey: "player.tapStep") }
    }
    static var gestureBrightness: Bool {
        get { d.object(forKey: "gesture.brightness") as? Bool ?? true }
        set { d.set(newValue, forKey: "gesture.brightness") }
    }
    static var gestureVolume: Bool {
        get { d.object(forKey: "gesture.volume") as? Bool ?? true }
        set { d.set(newValue, forKey: "gesture.volume") }
    }
    static var gestureSeek: Bool {
        get { d.object(forKey: "gesture.seek") as? Bool ?? true }
        set { d.set(newValue, forKey: "gesture.seek") }
    }
    static var rememberSpeed: Bool {
        get { d.object(forKey: "player.rememberSpeed") as? Bool ?? false }
        set { d.set(newValue, forKey: "player.rememberSpeed") }
    }
    static var savedRate: Float {
        get { d.object(forKey: "player.rate") as? Float ?? 1 }
        set { d.set(newValue, forKey: "player.rate") }
    }
    static var downloadHeight: Int {
        get { UserDefaults.standard.object(forKey: "download.quality") as? Int ?? 720 }
        set { UserDefaults.standard.set(newValue, forKey: "download.quality") }
    }
    static var preferredHeight: Int {
        get { UserDefaults.standard.object(forKey: "player.quality") as? Int ?? 720 }
        set { UserDefaults.standard.set(newValue, forKey: "player.quality") }
    }
}

struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var library: LibraryStore
    @ObservedObject private var shiki = ShikiSync.shared
    @State private var libLoggedIn = LibSession.isLoggedIn
    @State private var showLibLogin = false
    @State private var showShikiLogin = false
    @State private var notify = EpisodeNotifier.isEnabled
    @State private var autoSkip = PlayerPrefs.autoSkipOpening
    @State private var autoSkipEnd = PlayerPrefs.autoSkipEnding
    @State private var autoNext = PlayerPrefs.autoNext
    @State private var opLength = PlayerPrefs.openingLength
    @State private var quality = PlayerPrefs.preferredHeight
    @State private var downloadQuality = PlayerPrefs.downloadHeight
    @State private var autoDL = WatchingUpdater.autoDownload
    @State private var tapStep = PlayerPrefs.doubleTapStep
    @State private var gBright = PlayerPrefs.gestureBrightness
    @State private var gVolume = PlayerPrefs.gestureVolume
    @State private var gSeek = PlayerPrefs.gestureSeek
    @State private var rememberSpeed = PlayerPrefs.rememberSpeed
    @State private var autoDLCount = WatchingUpdater.autoDownloadCount
    @AppStorage("adblock") private var adblock = true
    @AppStorage("accent") private var accentRaw = AccentChoice.rose.rawValue
    @AppStorage("appIcon") private var appIcon = AppIconChoice.standard.rawValue
    @AppStorage(AppStyle.storageKey) private var styleRaw = AppStyle.classic.rawValue
    @AppStorage("appearance") private var appearance = "dark"
    @AppStorage("subs.alwaysOn") private var subsAlwaysOn = false
    @State private var confirmClear = false
    @State private var iconError: String?
    @State private var countdownTick = 0
    @State private var playerTick = 0
    @AppStorage(PlayerVersion.key) private var playerVersion = "new"
    @State private var calendarOn = CalendarSync.enabled
    @State private var calendarNote: String?
    @AppStorage(CloudSync.enabledKey) private var cloudOn = true
    @ObservedObject private var updates = UpdateChecker.shared
    @ObservedObject private var cloud = CloudSync.shared

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if libLoggedIn {
                        LabeledContent("AnimeLib", value: LibSession.username ?? "вход выполнен")
                        Button("Синхронизировать закладки") { Task { await library.syncBookmarks(force: true) } }
                        Button("Выйти из AnimeLib", role: .destructive) {
                            LibSession.clear()
                            LibAPI.clearCache()
                            libLoggedIn = false
                        }
                    } else {
                        Button("Войти в AnimeLib") { showLibLogin = true }
                    }
                    if shiki.isLoggedIn {
                        LabeledContent("Shikimori", value: ShikiSession.nickname ?? "вход выполнен")
                        Button(library.shikiImport == nil ? "Загрузить мой список с Shikimori" : "Загружаем список…") {
                            Task { await library.importShikimori(force: true) }
                        }
                        .disabled(library.shikiImport != nil)
                        Button("Выйти из Shikimori", role: .destructive) {
                            ShikiSession.clear()
                            shiki.refreshState()
                        }
                    } else {
                        Button("Подключить Shikimori") { showShikiLogin = true }
                    }
                } header: {
                    Text("Аккаунты")
                } footer: {
                    Text("Shikimori: просто войдите на сайте — просмотренные серии, статусы и оценки будут отмечаться автоматически.")
                }

                Section {
                    Toggle("Уведомлять о новых сериях", isOn: $notify)
                        .onChange(of: notify) { on in
                            Task {
                                if on { notify = await EpisodeNotifier.enable() } else { EpisodeNotifier.isEnabled = false }
                                await EpisodeNotifier.reschedule(titles: library.watchingTitles, force: true)
                            }
                        }
                    Toggle("Отсчёт до серии на экране блокировки", isOn: Binding(
                        get: { EpisodeCountdown.enabled }, set: { EpisodeCountdown.enabled = $0; countdownTick += 1 }))
                    Toggle("Добавлять серии в Календарь", isOn: Binding(get: { calendarOn }, set: { on in
                        calendarOn = on
                        CalendarSync.enabled = on
                        if on { Task { let n = await CalendarSync.sync(library: library, force: true); calendarNote = n > 0 ? "Добавлено серий: \(n)" : "Новых серий для календаря пока нет" } }
                    }))
                    if let calendarNote { Text(calendarNote).font(.footnote).foregroundStyle(.secondary) }
                } footer: {
                    Text("Для тайтлов из «Смотрю» — по расписанию Shikimori, с запасом 2 часа на озвучку. Отсчёт появляется за 6 часов до выхода серии.")
                }

                Section {
                    Toggle("Скачивать новые серии по Wi-Fi", isOn: $autoDL)
                        .onChange(of: autoDL) { WatchingUpdater.autoDownload = $0 }
                    if autoDL {
                        Stepper("Серий наперёд: \(autoDLCount)", value: $autoDLCount, in: 1...5)
                            .onChange(of: autoDLCount) { WatchingUpdater.autoDownloadCount = $0 }
                    }
                } header: {
                    Text("Автозагрузка")
                } footer: {
                    Text("Для тайтлов из «Смотрю»: следующие непросмотренные серии с той же озвучкой (плеер AnimeLib).")
                }

                Section {
                    Picker("Двойной тап", selection: $tapStep) {
                        ForEach([5, 10, 15, 30], id: \.self) { Text("±\($0) с").tag($0) }
                    }
                    .onChange(of: tapStep) { PlayerPrefs.doubleTapStep = $0 }
                    Toggle("Яркость — свайп слева", isOn: $gBright)
                        .onChange(of: gBright) { PlayerPrefs.gestureBrightness = $0 }
                    Toggle("Громкость — свайп справа", isOn: $gVolume)
                        .onChange(of: gVolume) { PlayerPrefs.gestureVolume = $0 }
                    Toggle("Перемотка — горизонтальный свайп", isOn: $gSeek)
                        .onChange(of: gSeek) { PlayerPrefs.gestureSeek = $0 }
                    Toggle("Запоминать скорость", isOn: $rememberSpeed)
                        .onChange(of: rememberSpeed) { PlayerPrefs.rememberSpeed = $0 }
                } header: {
                    Text("Жесты плеера")
                }

                Section("Плеер") {
                    Picker("Плеер", selection: $playerVersion) {
                        Text("Новый").tag("new")
                        Text("Старый").tag("old")
                    }
                    .pickerStyle(.segmented)
                    if playerVersion != "old" {
                        Toggle("Открывать сразу на весь экран", isOn: Binding(get: { PlayerVersion.startFullscreen },
                                                                            set: { PlayerVersion.startFullscreen = $0; playerTick += 1 }))
                    }
                    NavigationLink {
                        DubPreferencesView()
                    } label: {
                        LabeledContent("Любимые озвучки", value: DubPreferences.favorites.first ?? "не выбраны")
                    }
                    Toggle("Следующая серия автоматически", isOn: $autoNext)
                        .onChange(of: autoNext) { PlayerPrefs.autoNext = $0 }
                    Toggle("Пропускать опенинг автоматически", isOn: $autoSkip)
                        .onChange(of: autoSkip) { PlayerPrefs.autoSkipOpening = $0 }
                    Toggle("Пропускать эндинг и превью", isOn: $autoSkipEnd)
                        .onChange(of: autoSkipEnd) { PlayerPrefs.autoSkipEnding = $0 }
                    Stepper("Длина опенинга: \(Int(opLength)) с", value: $opLength, in: 30...150, step: 5)
                        .onChange(of: opLength) { PlayerPrefs.openingLength = $0 }
                    Picker("Качество загрузок", selection: $downloadQuality) {
                        Text("1080p").tag(1080)
                        Text("720p").tag(720)
                        Text("480p").tag(480)
                        Text("360p").tag(360)
                    }
                    .onChange(of: downloadQuality) { PlayerPrefs.downloadHeight = $0 }
                    Picker("Качество по умолчанию", selection: $quality) {
                        Text("Авто").tag(0)
                        Text("1080p").tag(1080)
                        Text("720p").tag(720)
                        Text("480p").tag(480)
                        Text("360p").tag(360)
                    }
                    .onChange(of: quality) { PlayerPrefs.preferredHeight = $0 }
                    Toggle("Субтитры у озвучек, если есть", isOn: $subsAlwaysOn)
                    Toggle("Блокировать рекламу в Kodik", isOn: $adblock)
                }

                Section {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 12) {
                            ForEach(AppStyle.allCases) { style in
                                Button {
                                    UserDefaults.standard.set(style.rawValue, forKey: AppStyle.storageKey)
                                    Theme.configureAppearance()
                                    styleRaw = style.rawValue
                                } label: {
                                    StylePreview(style: style, selected: styleRaw == style.rawValue)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.vertical, 6)
                    }
                    Picker("Тема", selection: $appearance) {
                        Text("Тёмная").tag("dark")
                        Text("Светлая").tag("light")
                        Text("Как в системе").tag("system")
                    }
                    .disabled(styleRaw == AppStyle.oled.rawValue)
                } header: {
                    Text("Стиль интерфейса")
                } footer: {
                    Text("Liquid Glass на iOS 26 использует системное стекло, на более старых — его имитацию. Material You берёт оттенки из цвета акцента. OLED всегда тёмный.")
                }

                Section("Оформление") {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 14) {
                            ForEach(AppIconChoice.allCases) { icon in
                                Button {
                                    guard UIApplication.shared.supportsAlternateIcons else {
                                        iconError = "Подпись приложения не поддерживает смену иконки."
                                        return
                                    }
                                    UIApplication.shared.setAlternateIconName(icon.alternateName) { err in
                                        DispatchQueue.main.async {
                                            if let err {
                                                DebugLog.log("icon: \(err)")
                                                let code = (err as NSError).code
                                                iconError = code == -54
                                                    ? "iOS запретил смену иконки для этой подписи (ошибка -54: приложению, подписанному через GBox, не дают доступ к базе иконок). В приложении это не исправить: иконку можно поменять при подписи в GBox, если там есть такая настройка."
                                                    : "iOS не сменил иконку: \(err.localizedDescription)"
                                            } else {
                                                appIcon = icon.rawValue
                                            }
                                        }
                                    }
                                } label: {
                                    VStack(spacing: 6) {
                                        Image(icon.preview).resizable()
                                            .frame(width: 58, height: 58)
                                            .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                                            .overlay(RoundedRectangle(cornerRadius: 13, style: .continuous)
                                                .stroke(appIcon == icon.rawValue ? Theme.accent : .clear, lineWidth: 3))
                                        Text(icon.title).font(.caption2).foregroundStyle(.primary)
                                    }
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                    HStack(spacing: 12) {
                        ForEach(AccentChoice.allCases) { a in
                            Circle()
                                .fill(a.color)
                                .frame(width: 28, height: 28)
                                .overlay(Circle().stroke(.white, lineWidth: accentRaw == a.rawValue ? 3 : 0))
                                .onTapGesture {
                                    UserDefaults.standard.set(a.rawValue, forKey: "accent")
                                    Theme.configureAppearance()
                                    accentRaw = a.rawValue
                                }
                        }
                    }
                    .padding(.vertical, 4)
                }

                Section {
                    NavigationLink { LockSettingsView() } label: {
                        Label("Блокировка и закрытые папки", systemImage: "faceid")
                    }
                } header: {
                    Text("Защита")
                }

                Section {
                    NavigationLink { DeviceSyncView() } label: {
                        LabeledContent {
                            Text(GistSync.shared.isOn ? "Вкл." : "Выкл.")
                        } label: {
                            Label("Синхронизация устройств (iPhone ↔ ТВ)", systemImage: "tv.and.mediabox")
                        }
                    }
                } header: {
                    Text("Синхронизация")
                }

                Section {
                    Toggle("Синхронизация через iCloud", isOn: $cloudOn)
                        .onChange(of: cloudOn) { on in if on { cloud.start(library: library) } }
                    if cloudOn {
                        LabeledContent("Состояние", value: cloud.status)
                    }
                } header: {
                    Text("iCloud")
                } footer: {
                    Text("История, позиции в сериях, закладки и настройки переносятся между вашими iPhone и iPad. Работает, если сертификат, которым подписано приложение, поддерживает iCloud.")
                }

                Section {
                    LabeledContent("Версия", value: UpdateChecker.installedVersion)
                    if let u = updates.available {
                        Button { UpdateChecker.openGBox() } label: {
                            Label("Обновить до \(u.version) в GBox", systemImage: "arrow.down.app.fill")
                        }
                        Link(destination: UpdateChecker.ipaURL) {
                            Label("Скачать IPA", systemImage: "square.and.arrow.down")
                        }
                    } else {
                        Button {
                            Task { await updates.check(force: true) }
                        } label: {
                            HStack {
                                Text(updates.lastChecked == nil ? "Проверить обновления" : "Установлена последняя версия")
                                Spacer()
                                if updates.isChecking { ProgressView() }
                            }
                        }
                    }
                    NavigationLink { ReportProblemView() } label: {
                        Label("Сообщить о проблеме", systemImage: "exclamationmark.bubble")
                    }
                    Link(destination: UpdateChecker.siteURL) { Label("Сайт AnixQ", systemImage: "safari") }
                } header: {
                    Text("О приложении")
                }

                Section("Данные") {
                    NavigationLink { BackupView() } label: {
                        Label("Резервная копия", systemImage: "externaldrive.badge.timemachine")
                    }
                    NavigationLink { ListTransferView() } label: {
                        Label("Экспорт и импорт списка (MAL)", systemImage: "arrow.up.arrow.down.square")
                    }
                    NavigationLink("Диагностика плеера") { DiagnosticsView() }
                    Button("Очистить историю просмотра", role: .destructive) { confirmClear = true }
                }
            }
            .styledList()
            .navigationTitle("Настройки")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } } }
            .sheet(isPresented: $showLibLogin) {
                LibLoginView { ok in
                    libLoggedIn = LibSession.isLoggedIn
                    if ok { Task { await library.syncBookmarks(force: true) } }
                }
            }
            .sheet(isPresented: $showShikiLogin) {
                ShikiLoginView { Task { await library.importShikimori(force: true) } }
            }
            .onAppear { appIcon = UIApplication.shared.alternateIconName ?? AppIconChoice.standard.rawValue }
            .alert("Иконка", isPresented: Binding(get: { iconError != nil }, set: { if !$0 { iconError = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(iconError ?? "")
            }
            .confirmationDialog("Очистить историю и прогресс?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("Очистить", role: .destructive) { library.clearHistory() }
            }
        }
    }
}

struct DiagnosticsView: View {
    @State private var text = ""
    @State private var crash = CrashReporter.lastReport

    var body: some View {
        List {
            if let crash {
                Section {
                    Text(crash)
                        .font(.system(size: 10, design: .monospaced))
                        .textSelection(.enabled)
                    ShareLink(item: crash) { Label("Отправить отчёт о сбое", systemImage: "square.and.arrow.up") }
                    Button("Удалить отчёт", role: .destructive) {
                        CrashReporter.clear()
                        self.crash = nil
                    }
                } header: {
                    Text("Последний сбой")
                }
            }
            Section("Виджеты") {
                LabeledContent("Общая группа приложений",
                               value: WidgetShared.fileURL == nil ? "недоступна (подпись без App Group)" : "доступна")
                let statuses = WidgetShared.readStatuses()
                if statuses.isEmpty {
                    Text(WidgetShared.fileURL == nil
                         ? "Без общей группы виджет «Продолжить» не получает данные. В GBox включите App Group / «сохранять entitlements» при подписи."
                         : "Виджеты ещё не запускались")
                        .font(.caption).foregroundStyle(.secondary)
                }
                ForEach(statuses, id: \.kind) { st in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(st.kind == "continue" ? "Продолжить просмотр" : "Скоро выходит").font(.subheadline)
                        Text("\(st.date.formatted(date: .abbreviated, time: .shortened)) — \(st.note)")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            Section("Журнал этого запуска") {
                Text(text.isEmpty ? "Пусто — откройте серию и вернитесь сюда." : text)
                    .font(.system(size: 11, design: .monospaced))
                    .textSelection(.enabled)
            }
        }
        .styledList()
        .navigationTitle("Диагностика")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                ShareLink(item: text.isEmpty ? "empty" : text) { Image(systemName: "square.and.arrow.up") }
            }
        }
        .onAppear { text = DebugLog.read() }
        .refreshable { text = DebugLog.read() }
    }
}

/// Shows a folder only after Face ID when it is in the closed list.
struct LockGate<Content: View>: View {
    let folder: BookmarkStatus
    @ViewBuilder var content: () -> Content
    @ObservedObject private var lock = AppLock.shared

    var body: some View {
        if lock.isLocked(folder) {
            LockedFolderView(folder: folder).navigationTitle(folder.title)
        } else {
            content()
        }
    }
}

/// Big colourful entry tile at the top of the library.
struct LibraryTile<V: Hashable>: View {
    let title: String
    let subtitle: String
    let icon: String
    let colors: [Color]
    let value: V

    var body: some View {
        let label = VStack(alignment: .leading, spacing: 10) {
            Image(systemName: icon).font(.system(size: 18, weight: .bold)).foregroundStyle(.white)
                .frame(width: 38, height: 38)
                .background(LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing),
                            in: RoundedRectangle(cornerRadius: 11, style: .continuous))
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.headline).foregroundStyle(.primary)
                Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(LinearGradient(colors: [colors[0].opacity(0.16), colors[colors.count - 1].opacity(0.05)],
                                   startPoint: .topLeading, endPoint: .bottomTrailing),
                    in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        // Invisible link on top keeps list rows free of chevrons.
        ZStack {
            label
            NavigationLink(value: value) { label }.opacity(0)
        }
    }
}

/// Settings-style icon: white symbol on a coloured rounded square.
struct ColoredIcon: View {
    let name: String
    let color: Color

    var body: some View {
        Image(systemName: name).font(.system(size: 13, weight: .semibold)).foregroundStyle(.white)
            .frame(width: 28, height: 28)
            .background(color.gradient, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
    }
}

extension BookmarkStatus {
    var color: Color {
        switch self {
        case .watching: return .green
        case .planned: return .blue
        case .dropped: return .red
        case .completed: return .purple
        case .favorite: return .pink
        case .rewatching: return .teal
        case .onHold: return .orange
        }
    }
}
