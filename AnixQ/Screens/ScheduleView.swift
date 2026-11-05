import SwiftUI

/// Release calendar from Shikimori (Japanese air time, shown in local time).
struct ScheduleView: View {
    @State private var days: [(date: Date, items: [ShikiCalendarItem])] = []
    @State private var selected = 0
    @State private var error: String?
    @State private var loading = false
    @State private var onlyMine = false
    @EnvironmentObject private var library: LibraryStore

    private let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ru_RU")
        f.dateFormat = "EE"
        return f
    }()
    private let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ru_RU")
        f.dateFormat = "HH:mm"
        return f
    }()

    var body: some View {
        List {
            Section {
                dayStrip
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            }
            if loading && days.isEmpty {
                HStack { Spacer(); ProgressView(); Spacer() }
            } else if let error {
                ErrorState(message: error) { Task { await load() } }
            } else if selected < days.count {
                let items = filtered(days[selected].items)
                Section {
                    if items.isEmpty {
                        Text(onlyMine ? "Из ваших тайтлов в этот день ничего" : "Ничего не выходит")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(items) { item in
                        NavigationLink(value: ShikiRoute(shikiId: item.shikiId, names: [item.name, item.russian ?? ""])) {
                            ScheduleRow(item: item, time: timeFormatter.string(from: item.nextEpisodeAt))
                        }
                    }
                } header: {
                    Text(header(days[selected].date))
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Расписание")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    Picker("Показывать", selection: $onlyMine) {
                        Text("Все онгоинги").tag(false)
                        Text("Только мои").tag(true)
                    }
                } label: {
                    Image(systemName: onlyMine ? "person.crop.circle.fill" : "person.crop.circle")
                }
            }
        }
        .appDestinations()
        .refreshable { await load() }
        .task { if days.isEmpty { await load() } }
    }

    private func filtered(_ items: [ShikiCalendarItem]) -> [ShikiCalendarItem] {
        guard onlyMine else { return items }
        let mine = Set(library.bookmarks.values.compactMap { $0.title.shikiId })
            .union(library.history.compactMap { $0.title.shikiId })
        return items.filter { mine.contains($0.shikiId) }
    }

    private var dayStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(days.indices, id: \.self) { i in
                    let d = days[i].date
                    Button { selected = i } label: {
                        VStack(spacing: 2) {
                            Text(Calendar.current.isDateInToday(d) ? "Сегодня" : dayFormatter.string(from: d).capitalized)
                                .font(.caption.weight(.semibold))
                            Text("\(Calendar.current.component(.day, from: d))").font(.title3.weight(.bold))
                        }
                        .frame(minWidth: 62, minHeight: 56)
                        .padding(.horizontal, 4)
                        .background(i == selected ? AnyShapeStyle(Color.primary) : AnyShapeStyle(.thinMaterial),
                                    in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .foregroundStyle(i == selected ? Color(.systemBackground) : Color.primary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 4)
            .padding(.vertical, 6)
        }
    }

    private func header(_ d: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ru_RU")
        f.dateFormat = "EEEE, d MMMM"
        return f.string(from: d).capitalized
    }

    private func load() async {
        loading = true
        error = nil
        defer { loading = false }
        do {
            let items = try await ShikiAPI.calendar()
            let cal = Calendar.current
            // Shikimori keeps a few stale entries from past days; show today onwards only.
            let today = cal.startOfDay(for: Date())
            let grouped = Dictionary(grouping: items) { cal.startOfDay(for: $0.nextEpisodeAt) }
            days = grouped.keys.filter { $0 >= today }.sorted().map { day in
                (day, (grouped[day] ?? []).sorted { $0.nextEpisodeAt < $1.nextEpisodeAt })
            }
            selected = 0
        } catch {
            self.error = "Не удалось загрузить расписание Shikimori"
        }
    }
}

struct ScheduleRow: View {
    let item: ShikiCalendarItem
    let time: String
    @State private var title: Title?

    var body: some View {
        HStack(spacing: 12) {
            Theme.card
                .frame(width: 52, height: 74)
                .overlay { RemoteImage(url: title?.thumbURL ?? item.image) }
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            VStack(alignment: .leading, spacing: 3) {
                Text(title?.title ?? item.title).font(.subheadline.weight(.semibold)).lineLimit(2)
                Text("Серия \(item.nextEpisode)" + (item.episodes.map { $0 > 0 ? " из \($0)" : "" } ?? ""))
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Text(time)
                .font(.subheadline.monospacedDigit().weight(.semibold))
                .foregroundStyle(item.nextEpisodeAt < Date() ? Color.secondary : Theme.accent)
        }
        .task {
            title = LibAPI.cachedTitle(shikiId: item.shikiId)
            if title == nil {
                title = await LibAPI.title(shikiId: item.shikiId, names: [item.name, item.russian ?? ""])
            }
        }
    }
}
