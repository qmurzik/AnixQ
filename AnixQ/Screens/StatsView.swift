import SwiftUI

/// Viewing statistics computed from the on-device library.
struct StatsView: View {
    @EnvironmentObject private var library: LibraryStore
    @State private var year = Calendar.current.component(.year, from: Date())

    private struct Stats {
        var seconds: Double = 0
        var episodes = 0
        var titles = 0
        var genres: [(String, Int)] = []
        var teams: [(String, Int)] = []
        var months: [Int] = Array(repeating: 0, count: 12)
        var topTitles: [(Title, Int)] = []
    }

    private func compute(year: Int?) -> Stats {
        let cal = Calendar.current
        var st = Stats()
        var titleEpisodes: [Int: Int] = [:]
        // Positions are stored per episode; map them back to titles via history.
        let episodeTitle = Dictionary(library.history.map { ($0.episodeId, $0.title.id) }, uniquingKeysWith: { a, _ in a })
        for (eid, p) in library.positions {
            if let year, cal.component(.year, from: p.date) != year { continue }
            st.seconds += min(p.position, p.duration)
            if p.duration > 0, p.position / p.duration > 0.85 {
                st.episodes += 1
                st.months[cal.component(.month, from: p.date) - 1] += 1
                if let tid = episodeTitle[eid] { titleEpisodes[tid, default: 0] += 1 }
            }
        }
        if year == nil {
            // Episodes imported from Shikimori count too (no duration known).
            st.episodes = max(st.episodes, library.watched.values.reduce(0) { $0 + $1.count })
        }
        let entries = library.history.filter { year == nil || cal.component(.year, from: $0.date) == year }
        st.titles = Set(entries.map(\.title.id)).count
        var genreCount: [String: Int] = [:]
        for e in entries { for g in library.genres[e.title.id] ?? [] { genreCount[g, default: 0] += 1 } }
        st.genres = genreCount.sorted { $0.value > $1.value }.prefix(6).map { ($0.key, $0.value) }
        var teamCount: [String: Int] = [:]
        for e in entries { teamCount[e.teamName, default: 0] += 1 }
        st.teams = teamCount.sorted { $0.value > $1.value }.prefix(5).map { ($0.key, $0.value) }
        let byId = Dictionary(entries.map { ($0.title.id, $0.title) }, uniquingKeysWith: { a, _ in a })
        st.topTitles = titleEpisodes.sorted { $0.value > $1.value }.prefix(5).compactMap { id, n in
            byId[id].map { ($0, n) }
        }
        return st
    }

    var body: some View {
        let all = compute(year: nil)
        let yearly = compute(year: year)
        List {
            Section {
                HStack(spacing: 0) {
                    tile(hours(all.seconds), "часов")
                    tile("\(all.episodes)", "серий")
                    tile("\(Set(library.history.map(\.title.id)).union(library.bookmarks.values.map(\.title.id)).count)", "тайтлов")
                }
                .listRowInsets(EdgeInsets(top: 12, leading: 0, bottom: 12, trailing: 0))
            } header: { Text("За всё время") }

            Section {
                HStack(spacing: 0) {
                    tile(hours(yearly.seconds), "часов")
                    tile("\(yearly.episodes)", "серий")
                    tile("\(yearly.titles)", "тайтлов")
                }
                .listRowInsets(EdgeInsets(top: 12, leading: 0, bottom: 12, trailing: 0))
                monthChart(yearly.months)
                if !yearly.topTitles.isEmpty {
                    ForEach(Array(yearly.topTitles.enumerated()), id: \.offset) { i, item in
                        NavigationLink(value: item.0) {
                            HStack {
                                Text("\(i + 1)").font(.headline.monospacedDigit()).foregroundStyle(Theme.accent).frame(width: 22)
                                Text(item.0.title).lineLimit(1)
                                Spacer()
                                Text("\(item.1) эп.").foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            } header: {
                HStack {
                    Text("Итоги \(String(year)) года")
                    Spacer()
                    Stepper("", value: $year, in: 2015...Calendar.current.component(.year, from: Date())).labelsHidden()
                }
            }

            if !all.genres.isEmpty {
                Section("Любимые жанры") { bars(all.genres) }
            }
            if !all.teams.isEmpty {
                Section("Озвучки") { bars(all.teams) }
            }
            Section("Закладки") {
                ForEach(BookmarkStatus.allCases) { s in
                    LabeledContent(s.title, value: "\(library.titles(in: s).count)")
                }
            }
        }
        .navigationTitle("Статистика")
    }

    private func hours(_ s: Double) -> String {
        s >= 36000 ? String(Int(s / 3600)) : String(format: "%.1f", s / 3600)
    }

    private func tile(_ value: String, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.system(size: 28, weight: .bold, design: .rounded)).foregroundStyle(Theme.accent)
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    private func monthChart(_ months: [Int]) -> some View {
        let maxV = max(1, months.max() ?? 1)
        let names = ["Я", "Ф", "М", "А", "М", "И", "И", "А", "С", "О", "Н", "Д"]
        return HStack(alignment: .bottom, spacing: 6) {
            ForEach(0..<12, id: \.self) { i in
                VStack(spacing: 4) {
                    RoundedRectangle(cornerRadius: 3)
                        .fill(months[i] > 0 ? Theme.accent : Color.secondary.opacity(0.2))
                        .frame(height: max(4, CGFloat(months[i]) / CGFloat(maxV) * 80))
                    Text(names[i]).font(.system(size: 9)).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
            }
        }
        .frame(height: 100)
        .padding(.vertical, 6)
    }

    private func bars(_ items: [(String, Int)]) -> some View {
        let maxV = max(1, items.map(\.1).max() ?? 1)
        return ForEach(items, id: \.0) { name, n in
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(name).font(.subheadline)
                    Spacer()
                    Text("\(n)").font(.caption).foregroundStyle(.secondary)
                }
                GeometryReader { g in
                    Capsule().fill(Theme.accent.opacity(0.85))
                        .frame(width: g.size.width * CGFloat(n) / CGFloat(maxV))
                }
                .frame(height: 6)
            }
        }
    }
}
