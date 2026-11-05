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
        .navigationTitle("Загрузки")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Text(ByteCountFormatter.string(fromByteCount: downloads.totalBytes, countStyle: .file))
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .fullScreenCover(item: $playing) { p in
            let done = p.items.filter { $0.state == .done }
            let team = done.first?.team ?? p.items[0].team
            PlayerScreen(title: p.title, background: nil, episodes: done.map(\.episode),
                         teams: [TeamOption(team: team, native: true, kodik: false)],
                         startIndex: max(0, done.firstIndex { $0.id == p.items[p.index].id } ?? 0),
                         team: TeamOption(team: team, native: true, kodik: false))
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
                            Text("В очереди…")
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
                default:
                    Text("\(Int(item.progress * 100))%").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                }
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
                case .failed: Image(systemName: "exclamationmark.arrow.circlepath").foregroundStyle(.red)
                case nil: Image(systemName: "arrow.down.circle")
                }
            }
            .buttonStyle(.borderless)
            .font(.title3)
        }
    }
}
