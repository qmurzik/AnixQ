import SwiftUI

struct LibraryView: View {
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var downloads: DownloadManager
    @State private var showSettings = false
    @State private var showLibLogin = false

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
                NavigationLink(value: LibraryRoute.stats) {
                    Label("Статистика", systemImage: "chart.bar.xaxis")
                }
                NavigationLink(value: LibraryRoute.downloads) {
                    HStack {
                        Label("Загрузки", systemImage: "arrow.down.circle")
                        Spacer()
                        let count = downloads.items.values.filter { $0.state == .done }.count
                        if count > 0 { Text("\(count)").foregroundStyle(.secondary) }
                    }
                }
            }

            Section("Закладки") {
                ForEach(BookmarkStatus.allCases) { s in
                    NavigationLink(value: LibraryRoute.folder(s)) {
                        HStack {
                            Label(s.title, systemImage: s.icon)
                            Spacer()
                            Text("\(library.titles(in: s).count)").foregroundStyle(.secondary)
                        }
                    }
                }
            }

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
    case stats, downloads
    case folder(BookmarkStatus)
}

extension View {
    func libraryDestinations() -> some View {
        navigationDestination(for: LibraryRoute.self) { route in
            switch route {
            case .stats: StatsView()
            case .downloads: DownloadsView()
            case .folder(let s): BookmarkFolderView(status: s)
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
        .navigationTitle(status.title)
        .refreshable { await library.syncBookmarks(force: true) }
    }
}

// MARK: - Settings

struct PlayerPrefs {
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
    @AppStorage("subs.alwaysOn") private var subsAlwaysOn = false
    @State private var confirmClear = false

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
                } footer: {
                    Text("Для тайтлов из «Смотрю» — по расписанию Shikimori, с запасом 2 часа на озвучку.")
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
                    Toggle("Следующая серия автоматически", isOn: $autoNext)
                        .onChange(of: autoNext) { PlayerPrefs.autoNext = $0 }
                    Toggle("Пропускать опенинг автоматически", isOn: $autoSkip)
                        .onChange(of: autoSkip) { PlayerPrefs.autoSkipOpening = $0 }
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
                        Text("1080p").tag(1080)
                        Text("720p").tag(720)
                        Text("480p").tag(480)
                        Text("360p").tag(360)
                    }
                    .onChange(of: quality) { PlayerPrefs.preferredHeight = $0 }
                    Toggle("Субтитры у озвучек, если есть", isOn: $subsAlwaysOn)
                    Toggle("Блокировать рекламу в Kodik", isOn: $adblock)
                }

                Section("Оформление") {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 14) {
                            ForEach(AppIconChoice.allCases) { icon in
                                Button {
                                    UIApplication.shared.setAlternateIconName(icon.alternateName) { err in
                                        if let err { DebugLog.log("icon: \(err.localizedDescription)") }
                                    }
                                    appIcon = icon.rawValue
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
                                .onTapGesture { accentRaw = a.rawValue }
                        }
                    }
                    .padding(.vertical, 4)
                }

                Section("Данные") {
                    NavigationLink("Диагностика плеера") { DiagnosticsView() }
                    Button("Очистить историю просмотра", role: .destructive) { confirmClear = true }
                }
            }
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
