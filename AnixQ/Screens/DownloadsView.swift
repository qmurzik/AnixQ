import SwiftUI

struct DownloadsView: View {
    @EnvironmentObject private var downloads: DownloadManager
    @State private var playing: OfflinePlay?

    struct OfflinePlay: Identifiable {
        let title: Title
        let items: [DownloadItem]
        let index: Int
        let id = UUID()
    }

    var body: some View {
        List {
            if downloads.items.isEmpty {
                EmptyState(icon: "arrow.down.circle", title: "Нет загрузок",
                           subtitle: "Скачивайте серии на странице тайтла — кнопка ↓ у серий. Работает с плеером AnimeLib.")
                    .listRowBackground(Color.clear)
            }
            if !downloads.items.isEmpty {
                Section {
                    storageSummary
                    if downloads.activeCount > 0 {
                        Button { downloads.pauseAll() } label: { Label("Приостановить все", systemImage: "pause.circle") }
                    }
                    if downloads.pausedCount > 0 {
                        Button { downloads.resumeAll() } label: { Label("Продолжить все", systemImage: "play.circle") }
                    }
                }
            }
            ForEach(downloads.titles, id: \.title.id) { group in
                Section {
                    ForEach(group.items) { item in
                        row(item, group: group)
                    }
                } header: {
                    HStack {
                        Text(group.title.title)
                        Spacer()
                        Button("Удалить все") { downloads.deleteAll(titleId: group.title.id) }
                            .font(.caption).textCase(nil)
                    }
                }
            }
        }
        .styledList()
        .navigationTitle("Загрузки")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                NavigationLink { DownloadSettingsView() } label: { Image(systemName: "slider.horizontal.3") }
            }
        }
        .fullScreenCover(item: $playing) { p in
            let done = p.items.filter { $0.state == .done }
            let team = done.first?.team ?? p.items[0].team
            AnyPlayerScreen(title: p.title, background: nil, episodes: done.map(\.episode),
                         teams: [TeamOption(team: team, native: true, kodik: false)],
                         startIndex: max(0, done.firstIndex { $0.id == p.items[p.index].id } ?? 0),
                         team: TeamOption(team: team, native: true, kodik: false))
        }
    }

    private var storageSummary: some View {
        let used = downloads.totalBytes
        let limit = DownloadManager.limitGB
        return VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Занято").font(.subheadline)
                Spacer()
                Text(ByteCountFormatter.string(fromByteCount: used, countStyle: .file)
                     + (limit > 0 ? " из \(Int(limit)) ГБ" : "")).font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
            }
            if limit > 0 {
                ProgressView(value: min(1, Double(used) / (limit * 1_000_000_000))).tint(Theme.accent)
            }
            if let free = (try? URL(fileURLWithPath: NSHomeDirectory())
                .resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]))?.volumeAvailableCapacityForImportantUsage {
                Text("Свободно на устройстве: \(ByteCountFormatter.string(fromByteCount: free, countStyle: .file))")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private func row(_ item: DownloadItem, group: (title: Title, items: [DownloadItem])) -> some View {
        Button {
            if item.state == .done, let i = group.items.firstIndex(of: item) {
                playing = OfflinePlay(title: group.title, items: group.items, index: i)
            }
        } label: {
            HStack(spacing: 12) {
                Text(item.episode.number).font(.headline.monospacedDigit()).frame(width: 40, alignment: .leading)
                VStack(alignment: .leading, spacing: 3) {
                    Text(item.episode.displayTitle).foregroundStyle(.primary).lineLimit(1)
                    Group {
                        switch item.state {
                        case .done:
                            Text("\(item.team.name) · \(item.quality) · \(ByteCountFormatter.string(fromByteCount: item.bytes, countStyle: .file))")
                        case .downloading:
                            ProgressView(value: item.progress).tint(Theme.accent)
                        case .queued:
                            Text(item.error ?? ((item.priority ?? 0) > 0 ? "В очереди · первой" : "В очереди…"))
                        case .paused:
                            Text("Пауза · \(Int(item.progress * 100))%")
                        case .failed:
                            Text(item.error ?? "Ошибка").foregroundStyle(.red)
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
                Spacer()
                switch item.state {
                case .done: Image(systemName: "play.circle.fill").font(.title2).foregroundStyle(Theme.accent)
                case .failed:
                    Button { downloads.retry(item) } label: { Image(systemName: "arrow.clockwise.circle").font(.title2) }
                        .buttonStyle(.borderless)
                case .downloading:
                    Button { downloads.pause(item) } label: { Image(systemName: "pause.circle").font(.title2) }
                        .buttonStyle(.borderless)
                case .paused:
                    Button { downloads.resume(item) } label: { Image(systemName: "play.circle").font(.title2) }
                        .buttonStyle(.borderless)
                case .queued:
                    Button { downloads.pause(item) } label: { Image(systemName: "pause.circle").font(.title2).foregroundStyle(.secondary) }
                        .buttonStyle(.borderless)
                }
            }
        }
        .swipeActions(edge: .leading) {
            if item.state == .queued || item.state == .paused {
                Button { downloads.prioritize(item) } label: { Label("Первой", systemImage: "arrow.up.to.line") }
                    .tint(.blue)
            }
        }
        .swipeActions {
            Button(role: .destructive) {
                item.state == .done ? downloads.delete(item) : downloads.cancel(item)
            } label: {
                Label(item.state == .done ? "Удалить" : "Отменить", systemImage: "trash")
            }
        }
    }
}

/// Small download control used on episode cards / lists.
struct DownloadButton: View {
    let title: Title
    let episode: LibEpisode
    let team: TeamOption?
    @EnvironmentObject private var downloads: DownloadManager

    var body: some View {
        if let team, team.native {
            let item = downloads.item(episodeId: episode.id, teamId: team.id)
            Button {
                if item == nil || item?.state == .failed {
                    downloads.download(title: title, episode: episode, team: team.team)
                } else if let item, item.state == .paused {
                    downloads.resume(item)
                } else if let item, item.state == .downloading || item.state == .queued {
                    downloads.pause(item)
                }
            } label: {
                switch item?.state {
                case .done: Image(systemName: "arrow.down.circle.fill").foregroundStyle(Theme.accent)
                case .downloading:
                    ZStack {
                        Circle().stroke(Color.secondary.opacity(0.3), lineWidth: 2.5)
                        Circle().trim(from: 0, to: item?.progress ?? 0)
                            .stroke(Theme.accent, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                            .rotationEffect(.degrees(-90))
                    }
                    .frame(width: 18, height: 18)
                case .queued: ProgressView().scaleEffect(0.7)
                case .paused: Image(systemName: "pause.circle").foregroundStyle(.secondary)
                case .failed: Image(systemName: "exclamationmark.arrow.circlepath").foregroundStyle(.red)
                case nil: Image(systemName: "arrow.down.circle")
                }
            }
            .buttonStyle(.borderless)
            .font(.title3)
        }
    }
}

struct DownloadSettingsView: View {
    @State private var concurrent = DownloadManager.maxConcurrent
    @State private var wifiOnly = DownloadManager.wifiOnly
    @State private var limit = DownloadManager.limitGB
    @State private var deleteWatched = DownloadManager.deleteWatched
    @State private var autoDL = WatchingUpdater.autoDownload
    @State private var autoDLCount = WatchingUpdater.autoDownloadCount
    @State private var quality = PlayerPrefs.downloadHeight

    var body: some View {
        Form {
            Section {
                Stepper("Одновременно: \(concurrent)", value: $concurrent, in: 1...4)
                    .onChange(of: concurrent) { DownloadManager.maxConcurrent = $0; DownloadManager.shared.pump() }
                Toggle("Только по Wi-Fi", isOn: $wifiOnly).onChange(of: wifiOnly) { DownloadManager.wifiOnly = $0 }
                Picker("Качество", selection: $quality) {
                    ForEach([1080, 720, 480, 360], id: \.self) { Text("\($0)p").tag($0) }
                }
                .onChange(of: quality) { PlayerPrefs.downloadHeight = $0 }
            } header: {
                Text("Очередь")
            } footer: {
                Text("Смахните загрузку вправо, чтобы поставить её первой. «Только по Wi-Fi» действует для новых загрузок.")
            }
            Section {
                Picker("Лимит места", selection: $limit) {
                    Text("Без лимита").tag(0.0)
                    ForEach([2.0, 5.0, 10.0, 20.0, 50.0], id: \.self) { Text("\(Int($0)) ГБ").tag($0) }
                }
                .onChange(of: limit) { DownloadManager.limitGB = $0 }
                Toggle("Удалять просмотренные серии", isOn: $deleteWatched)
                    .onChange(of: deleteWatched) { DownloadManager.deleteWatched = $0 }
            } header: {
                Text("Место")
            } footer: {
                Text("Просмотренные серии удаляются, когда вы сворачиваете приложение, и в первую очередь — когда не хватает места под лимит.")
            }
            Section {
                Toggle("Скачивать следующие серии по Wi-Fi", isOn: $autoDL).onChange(of: autoDL) { WatchingUpdater.autoDownload = $0 }
                if autoDL {
                    Stepper("Серий наперёд: \(autoDLCount)", value: $autoDLCount, in: 1...5)
                        .onChange(of: autoDLCount) { WatchingUpdater.autoDownloadCount = $0 }
                }
            } header: {
                Text("Автозагрузка")
            } footer: {
                Text("Для тайтлов из «Смотрю» — с той же озвучкой, что вы смотрели последней.")
            }
        }
        .styledList()
        .navigationTitle("Настройки загрузок")
    }
}
