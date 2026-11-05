import SwiftUI

// MARK: - Achievements

struct Achievement: Identifiable {
    let id: String
    let icon: String
    let title: String
    let detail: String
    let unlocked: Bool
    let progress: Double
}

@MainActor
enum Achievements {
    static func all(_ library: LibraryStore) -> [Achievement] {
        let cal = Calendar.current
        let finished = library.positions.values.filter { $0.duration > 0 && $0.position / $0.duration > 0.85 }
        let episodes = max(finished.count, library.watched.values.reduce(0) { $0 + $1.count })
        let hours = library.positions.values.reduce(0) { $0 + min($1.position, $1.duration) } / 3600
        let completed = library.titles(in: .completed).count
        let night = finished.contains { (2...5).contains(cal.component(.hour, from: $0.date)) }
        let perDay = Dictionary(grouping: finished) { cal.startOfDay(for: $0.date) }.values.map(\.count).max() ?? 0
        let genres = Set(library.history.flatMap { library.genres[$0.title.id] ?? [] }).count
        let teams = Dictionary(grouping: library.history, by: \.teamName).mapValues(\.count)
        let loyal = library.history.count >= 10 && Double(teams.values.max() ?? 0) / Double(library.history.count) >= 0.8
        let planned = library.titles(in: .planned).count

        func a(_ id: String, _ icon: String, _ title: String, _ detail: String, _ value: Double, _ goal: Double) -> Achievement {
            Achievement(id: id, icon: icon, title: title, detail: detail, unlocked: value >= goal, progress: min(1, value / goal))
        }
        return [
            a("first", "play.circle.fill", "Первая серия", "Посмотреть серию до конца", Double(episodes), 1),
            a("ep100", "film.stack.fill", "Сотня", "100 серий", Double(episodes), 100),
            a("ep500", "flame.fill", "Отаку", "500 серий", Double(episodes), 500),
            a("ep1000", "crown.fill", "Легенда", "1000 серий", Double(episodes), 1000),
            a("h24", "clock.fill", "Сутки в аниме", "24 часа просмотра", hours, 24),
            a("h100", "hourglass", "Сто часов", "100 часов просмотра", hours, 100),
            a("done10", "checkmark.seal.fill", "Довожу до конца", "10 тайтлов в «Просмотрено»", Double(completed), 10),
            a("done50", "rosette", "Коллекционер", "50 тайтлов в «Просмотрено»", Double(completed), 50),
            a("marathon", "bolt.fill", "Марафонец", "8 серий за один день", Double(perDay), 8),
            a("night", "moon.stars.fill", "Ночная смена", "Досмотреть серию между 2 и 5 утра", night ? 1 : 0, 1),
            a("genres", "sparkles", "Всеядный", "Тайтлы 10 разных жанров", Double(genres), 10),
            a("loyal", "waveform", "Верный озвучке", "80% просмотров с одной озвучкой", loyal ? 1 : 0, 1),
            a("planner", "calendar.badge.plus", "Планировщик", "20 тайтлов в «Запланировано»", Double(planned), 20)
        ]
    }
}

struct AchievementsView: View {
    @EnvironmentObject private var library: LibraryStore
    private let columns = [GridItem(.adaptive(minimum: 150), spacing: 12)]

    var body: some View {
        let list = Achievements.all(library)
        ScrollView {
            Text("Открыто \(list.filter(\.unlocked).count) из \(list.count)")
                .font(.headline).foregroundStyle(.secondary).padding(.top)
            LazyVGrid(columns: columns, spacing: 12) {
                ForEach(list) { a in
                    VStack(spacing: 8) {
                        Image(systemName: a.icon).font(.system(size: 30))
                            .foregroundStyle(a.unlocked ? AnyShapeStyle(LinearGradient(colors: [Theme.accent, .purple], startPoint: .topLeading, endPoint: .bottomTrailing)) : AnyShapeStyle(Color.secondary.opacity(0.4)))
                            .frame(height: 38)
                        Text(a.title).font(.subheadline.weight(.bold)).multilineTextAlignment(.center)
                        Text(a.detail).font(.caption2).foregroundStyle(.secondary).multilineTextAlignment(.center)
                        if !a.unlocked {
                            ProgressView(value: a.progress).tint(Theme.accent)
                        }
                    }
                    .padding(14)
                    .frame(maxWidth: .infinity, minHeight: 150)
                    .cardStyle()
                    .opacity(a.unlocked ? 1 : 0.75)
                }
            }
            .padding()
        }
        .styledBackground()
        .navigationTitle("Достижения")
    }
}

// MARK: - Year in review

struct YearSummary {
    let year: Int
    let hours: Int
    let episodes: Int
    let titles: Int
    let topTitles: [String]
    let topGenre: String?
    let topTeam: String?
    let bestMonth: String?
    let achievements: Int

    @MainActor
    static func make(_ library: LibraryStore, year: Int) -> YearSummary {
        let cal = Calendar.current
        let inYear = library.positions.filter { cal.component(.year, from: $0.value.date) == year }
        let finished = inYear.filter { $0.value.duration > 0 && $0.value.position / $0.value.duration > 0.85 }
        let seconds = inYear.values.reduce(0) { $0 + min($1.position, $1.duration) }
        let entries = library.history.filter { cal.component(.year, from: $0.date) == year }
        let episodeTitle = Dictionary(library.history.map { ($0.episodeId, $0.title) }, uniquingKeysWith: { a, _ in a })
        let perTitle = Dictionary(grouping: finished.keys.compactMap { episodeTitle[$0] }, by: \.id)
        let top = perTitle.sorted { $0.value.count > $1.value.count }.prefix(3).compactMap { $0.value.first?.title }
        let genres = entries.flatMap { library.genres[$0.title.id] ?? [] }
        let topGenre = Dictionary(grouping: genres, by: { $0 }).max { $0.value.count < $1.value.count }?.key
        let topTeam = Dictionary(grouping: entries, by: \.teamName).max { $0.value.count < $1.value.count }?.key
        let months = Dictionary(grouping: finished.values) { cal.component(.month, from: $0.date) }
        let best = months.max { $0.value.count < $1.value.count }?.key
        let f = DateFormatter()
        f.locale = Locale(identifier: "ru_RU")
        return YearSummary(year: year, hours: Int(seconds / 3600), episodes: finished.count,
                           titles: Set(entries.map(\.title.id)).count, topTitles: top, topGenre: topGenre, topTeam: topTeam,
                           bestMonth: best.map { f.standaloneMonthSymbols[$0 - 1].capitalized },
                           achievements: Achievements.all(library).filter(\.unlocked).count)
    }
}

/// The shareable card: rendered to an image for stories / chats (fixed 9:16 size).
struct YearCard: View {
    let s: YearSummary

    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(red: 0.98, green: 0.3, blue: 0.48), Color(red: 0.45, green: 0.25, blue: 0.95),
                                    Color(red: 0.08, green: 0.07, blue: 0.16)], startPoint: .topLeading, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 22) {
                HStack {
                    Image("AppIcon-Preview").resizable().frame(width: 44, height: 44)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    Text("AnixQ").font(.system(size: 26, weight: .heavy))
                    Spacer()
                    Text("Итоги \(String(s.year))").font(.system(size: 20, weight: .bold)).opacity(0.85)
                }
                Spacer(minLength: 0)
                VStack(alignment: .leading, spacing: 0) {
                    Text("\(s.hours)").font(.system(size: 120, weight: .heavy, design: .rounded))
                    Text(hoursWord(s.hours) + " аниме").font(.system(size: 30, weight: .bold)).opacity(0.9)
                }
                HStack(spacing: 28) {
                    stat("\(s.episodes)", "серий")
                    stat("\(s.titles)", "тайтлов")
                    stat("\(s.achievements)", "достижений")
                }
                if !s.topTitles.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("ЛЮБИМОЕ").font(.system(size: 15, weight: .heavy)).kerning(1.5).opacity(0.7)
                        ForEach(Array(s.topTitles.enumerated()), id: \.offset) { i, t in
                            Text("\(i + 1). \(t)").font(.system(size: 24, weight: .semibold)).lineLimit(1)
                        }
                    }
                }
                VStack(alignment: .leading, spacing: 6) {
                    if let g = s.topGenre { line("Жанр года", g) }
                    if let t = s.topTeam { line("Озвучка года", t) }
                    if let m = s.bestMonth { line("Самый аниме-месяц", m) }
                }
                Spacer(minLength: 0)
                Text("qmurzik.github.io/AnixQ").font(.system(size: 18, weight: .semibold)).opacity(0.75)
            }
            .foregroundStyle(.white)
            .padding(48)
        }
        .frame(width: 1080 / 2, height: 1920 / 2)
    }

    private func stat(_ v: String, _ l: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(v).font(.system(size: 40, weight: .heavy, design: .rounded))
            Text(l).font(.system(size: 17, weight: .medium)).opacity(0.8)
        }
    }

    private func line(_ k: String, _ v: String) -> some View {
        HStack(spacing: 8) {
            Text(k).opacity(0.7)
            Text(v).fontWeight(.bold)
        }
        .font(.system(size: 19))
    }

    private func hoursWord(_ n: Int) -> String {
        let m10 = n % 10, m100 = n % 100
        if m10 == 1 && m100 != 11 { return "час" }
        if (2...4).contains(m10) && !(12...14).contains(m100) { return "часа" }
        return "часов"
    }
}

struct YearInReviewView: View {
    @EnvironmentObject private var library: LibraryStore
    @State private var year = Calendar.current.component(.year, from: Date())
    @State private var image: UIImage?

    var body: some View {
        let summary = YearSummary.make(library, year: year)
        ScrollView {
            VStack(spacing: 18) {
                Picker("Год", selection: $year) {
                    let now = Calendar.current.component(.year, from: Date())
                    ForEach((now - 3...now).reversed(), id: \.self) { Text(String($0)).tag($0) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)

                YearCard(s: summary)
                    .scaleEffect(0.62)
                    .frame(width: 540 * 0.62, height: 960 * 0.62)
                    .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                    .shadow(color: .black.opacity(0.3), radius: 20, y: 10)

                if let image {
                    ShareLink(item: Image(uiImage: image), preview: SharePreview("Мои итоги \(String(year)) в AnixQ", image: Image(uiImage: image))) {
                        Label("Поделиться", systemImage: "square.and.arrow.up").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .padding(.horizontal, 40)
                    Button {
                        UIImageWriteToSavedPhotosAlbum(image, nil, nil, nil)
                        Haptics.success()
                    } label: { Label("Сохранить в Фото", systemImage: "square.and.arrow.down") }
                }
            }
            .padding(.vertical)
        }
        .styledBackground()
        .navigationTitle("Итоги года")
        .task(id: year) { render(summary) }
    }

    private func render(_ s: YearSummary) {
        let r = ImageRenderer(content: YearCard(s: s))
        r.scale = 2      // 1080 × 1920, the stories size
        image = r.uiImage
    }
}
