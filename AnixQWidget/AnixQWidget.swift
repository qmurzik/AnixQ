import WidgetKit
import SwiftUI
import ActivityKit

// MARK: - Shared helpers

private let accent = Color(red: 1.0, green: 0.27, blue: 0.42)
private let accent2 = Color(red: 0.55, green: 0.30, blue: 1.0)

private func loadImage(_ string: String?, maxWidth: CGFloat = 320) async -> UIImage? {
    guard let string, let url = URL(string: string) else { return nil }
    var req = URLRequest(url: url)
    req.timeoutInterval = 10
    if url.host?.contains("imglib") == true { req.setValue("https://anilib.me/", forHTTPHeaderField: "Referer") }
    guard let (data, _) = try? await URLSession.shared.data(for: req), let img = UIImage(data: data) else { return nil }
    // Widgets have a tight memory budget: downscale.
    let scale = min(1, maxWidth / max(img.size.width, 1))
    let target = CGSize(width: img.size.width * scale, height: img.size.height * scale)
    let format = UIGraphicsImageRendererFormat()
    format.scale = 2
    return UIGraphicsImageRenderer(size: target, format: format).image { _ in img.draw(in: CGRect(origin: .zero, size: target)) }
}

extension View {
    @ViewBuilder
    func widgetBackground<B: View>(@ViewBuilder _ background: () -> B) -> some View {
        if #available(iOSApplicationExtension 17.0, *) {
            containerBackground(for: .widget, content: background)
        } else {
            self.background(background())
        }
    }
}

private struct ProgressBar: View {
    let value: Double
    var height: CGFloat = 4
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.22))
                Capsule().fill(LinearGradient(colors: [accent, accent2], startPoint: .leading, endPoint: .trailing))
                    .frame(width: max(height, g.size.width * value))
            }
        }
        .frame(height: height)
    }
}

private struct Poster: View {
    let image: UIImage?
    var radius: CGFloat = 10
    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(white: 0.18), Color(white: 0.1)], startPoint: .top, endPoint: .bottom)
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Image(systemName: "play.rectangle.fill").font(.title2).foregroundStyle(.white.opacity(0.3))
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

// MARK: - Continue watching

struct ContinueEntry: TimelineEntry {
    let date: Date
    let items: [WidgetShared.ContinueItem]
    let images: [UIImage?]
    let hasAppGroup: Bool
}

struct ContinueProvider: TimelineProvider {
    func placeholder(in context: Context) -> ContinueEntry {
        let demo = WidgetShared.ContinueItem(title: "Ваше аниме", slug: "", episode: "5", team: "AniLibria",
                                             progress: 0.45, cover: nil)
        return ContinueEntry(date: Date(), items: [demo, demo, demo], images: [nil, nil, nil], hasAppGroup: true)
    }

    func getSnapshot(in context: Context, completion: @escaping (ContinueEntry) -> Void) {
        Task { completion(context.isPreview ? placeholder(in: context) : await entry(family: context.family)) }
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<ContinueEntry>) -> Void) {
        Task {
            let e = await entry(family: context.family)
            completion(Timeline(entries: [e], policy: .after(Date().addingTimeInterval(1800))))
        }
    }

    private func entry(family: WidgetFamily) async -> ContinueEntry {
        let items = Array((WidgetShared.read()?.continueItems ?? []).prefix(family == .systemMedium ? 3 : 1))
        WidgetShared.writeStatus(kind: "continue", note: items.isEmpty ? "нет данных от приложения" : "ok: \(items[0].title)")
        var images: [UIImage?] = []
        for (i, item) in items.enumerated() {
            images.append(await loadImage(item.cover, maxWidth: i == 0 ? 360 : 120))
        }
        return ContinueEntry(date: Date(), items: items, images: images, hasAppGroup: WidgetShared.fileURL != nil)
    }
}

struct ContinueWidgetView: View {
    let entry: ContinueEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        Group {
            if let first = entry.items.first {
                switch family {
                case .accessoryRectangular: rectangular(first)
                case .accessoryCircular: circular(first)
                case .systemMedium: medium
                default: small(first)
                }
            } else {
                empty
            }
        }
        .widgetURL(entry.items.first.flatMap { WidgetShared.titleURL($0.slug) })
        .widgetBackground {
            if family == .systemSmall, let img = entry.images.first ?? nil {
                ZStack {
                    Image(uiImage: img).resizable().scaledToFill()
                    LinearGradient(colors: [.black.opacity(0.05), .black.opacity(0.85)], startPoint: .top, endPoint: .bottom)
                }
            } else if family == .accessoryRectangular || family == .accessoryCircular {
                Color.clear
            } else {
                LinearGradient(colors: [Color(red: 0.09, green: 0.08, blue: 0.13), .black], startPoint: .top, endPoint: .bottom)
            }
        }
    }

    private var empty: some View {
        VStack(spacing: 8) {
            Image(systemName: "play.rectangle.on.rectangle.fill").font(.title2)
                .foregroundStyle(LinearGradient(colors: [accent, accent2], startPoint: .leading, endPoint: .trailing))
            Text(entry.hasAppGroup ? "Начните смотреть что-нибудь в AnixQ" : "Откройте AnixQ")
                .font(.caption.weight(.medium)).multilineTextAlignment(.center).foregroundStyle(.white.opacity(0.8))
        }
        .padding(8)
    }

    private func small(_ item: WidgetShared.ContinueItem) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Image(systemName: "play.fill").font(.system(size: 9, weight: .bold))
                    .padding(6).background(accent, in: Circle()).foregroundStyle(.white)
                Spacer()
            }
            Spacer()
            Text(item.title).font(.system(size: 14, weight: .bold)).lineLimit(2).foregroundStyle(.white)
            Text("Серия \(item.episode)").font(.system(size: 11, weight: .medium)).foregroundStyle(.white.opacity(0.75))
            ProgressBar(value: item.progress).padding(.top, 2)
        }
    }

    private var medium: some View {
        HStack(spacing: 12) {
            let first = entry.items[0]
            Poster(image: entry.images.first ?? nil, radius: 12)
                .frame(width: 92)
            VStack(alignment: .leading, spacing: 5) {
                Text("ПРОДОЛЖИТЬ").font(.system(size: 9, weight: .heavy)).kerning(0.8).foregroundStyle(accent)
                Text(first.title).font(.system(size: 15, weight: .bold)).lineLimit(2).foregroundStyle(.white)
                Text("Серия \(first.episode) · \(first.team)").font(.system(size: 11)).lineLimit(1)
                    .foregroundStyle(.white.opacity(0.65))
                ProgressBar(value: first.progress)
                Spacer(minLength: 2)
                ForEach(Array(entry.items.dropFirst().enumerated()), id: \.offset) { i, item in
                    Link(destination: WidgetShared.titleURL(item.slug) ?? URL(string: "anixq://")!) {
                        HStack(spacing: 6) {
                            Poster(image: entry.images[i + 1], radius: 3).frame(width: 14, height: 20)
                            Text(item.title).font(.system(size: 11, weight: .medium)).lineLimit(1)
                                .foregroundStyle(.white.opacity(0.85))
                            Spacer(minLength: 2)
                            Text("эп. \(item.episode)").font(.system(size: 10)).foregroundStyle(.white.opacity(0.5))
                        }
                    }
                }
            }
        }
    }

    private func rectangular(_ item: WidgetShared.ContinueItem) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Label("Продолжить", systemImage: "play.fill").font(.caption2.weight(.semibold))
            Text(item.title).font(.headline).lineLimit(1)
            HStack(spacing: 6) {
                Text("Серия \(item.episode)").font(.caption2)
                ProgressView(value: item.progress).tint(.white)
            }
        }
    }

    private func circular(_ item: WidgetShared.ContinueItem) -> some View {
        Gauge(value: item.progress) {
            Image(systemName: "play.fill")
        } currentValueLabel: {
            Text(item.episode).font(.system(size: 14, weight: .bold))
        }
        .gaugeStyle(.accessoryCircular)
    }
}

struct ContinueWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "AnixQContinue", provider: ContinueProvider()) { entry in
            ContinueWidgetView(entry: entry)
        }
        .configurationDisplayName("Продолжить просмотр")
        .description("Последние серии, которые вы смотрели.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryRectangular, .accessoryCircular])
    }
}

// MARK: - Upcoming episodes

struct AiringItem: Hashable {
    let title: String
    let episode: Int
    let time: Date
    let mine: Bool
    let slug: String?
    let cover: String?
}

struct TodayEntry: TimelineEntry {
    let date: Date
    let items: [AiringItem]
    var images: [String: UIImage] = [:]
    var failed = false
}

struct TodayProvider: TimelineProvider {
    func placeholder(in context: Context) -> TodayEntry {
        TodayEntry(date: Date(), items: (0..<5).map {
            AiringItem(title: "Онгоинг \($0 + 1)", episode: 5, time: Date().addingTimeInterval(Double($0) * 3600),
                       mine: $0 < 2, slug: nil, cover: nil)
        })
    }

    func getSnapshot(in context: Context, completion: @escaping (TodayEntry) -> Void) {
        Task { completion(context.isPreview ? placeholder(in: context) : await entry()) }
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<TodayEntry>) -> Void) {
        Task {
            let e = await entry()
            // Refresh after the next episode airs (so it drops off), at most every 2 hours.
            let next = e.items.map(\.time).filter { $0 > Date() }.min() ?? Date().addingTimeInterval(7200)
            completion(Timeline(entries: [e], policy: .after(min(next.addingTimeInterval(300), Date().addingTimeInterval(7200)))))
        }
    }

    private func entry() async -> TodayEntry {
        let payload = WidgetShared.read()
        let mine = Set(payload?.watchingShikiIds ?? [])
        var req = URLRequest(url: URL(string: "https://shikimori.io/api/calendar")!)
        req.setValue("AnixQ", forHTTPHeaderField: "User-Agent")
        req.timeoutInterval = 15
        guard let (data, _) = try? await URLSession.shared.data(for: req),
              let list = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            WidgetShared.writeStatus(kind: "upcoming", note: "нет сети / Shikimori недоступен")
            return TodayEntry(date: Date(), items: [], failed: true)
        }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        // Shikimori lists only the *next* episode of each show, so show the next 36 hours.
        let now = Date()
        let horizon = now.addingTimeInterval(36 * 3600)
        let items: [AiringItem] = list.compactMap { x in
            guard let a = x["anime"] as? [String: Any], let id = a["id"] as? Int,
                  let s = x["next_episode_at"] as? String,
                  let at = f.date(from: s) ?? ISO8601DateFormatter().date(from: s),
                  at > now.addingTimeInterval(-1800), at < horizon else { return nil }
            let name = (a["russian"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? (a["name"] as? String ?? "")
            return AiringItem(title: name, episode: x["next_episode"] as? Int ?? 0, time: at, mine: mine.contains(id),
                              slug: payload?.slugs?[id], cover: payload?.covers?[id])
        }
        .sorted { ($0.mine ? 0 : 1, $0.time) < ($1.mine ? 0 : 1, $1.time) }
        var images: [String: UIImage] = [:]
        for item in items.prefix(4) where item.cover != nil {
            if let img = await loadImage(item.cover, maxWidth: 80) { images[item.cover!] = img }
        }
        WidgetShared.writeStatus(kind: "upcoming", note: "ok: \(items.count) серий, моих \(items.filter(\.mine).count)")
        return TodayEntry(date: Date(), items: items, images: images)
    }
}

struct TodayWidgetView: View {
    let entry: TodayEntry
    @Environment(\.widgetFamily) private var family

    private var limit: Int { family == .systemLarge ? 8 : 3 }

    var body: some View {
        Group {
            if family == .accessoryRectangular {
                rectangular
            } else {
                list
            }
        }
        .widgetURL(URL(string: "anixq://schedule"))
        .widgetBackground {
            if family == .accessoryRectangular {
                Color.clear
            } else {
                LinearGradient(colors: [Color(red: 0.09, green: 0.08, blue: 0.13), .black], startPoint: .top, endPoint: .bottom)
            }
        }
    }

    private var rectangular: some View {
        VStack(alignment: .leading, spacing: 2) {
            Label("Скоро выходит", systemImage: "calendar").font(.caption2.weight(.semibold))
            if let first = entry.items.first {
                Text(first.title).font(.headline).lineLimit(1)
                Text("Серия \(first.episode) · \(dayLabel(first.time))\(first.time.formatted(date: .omitted, time: .shortened))")
                    .font(.caption2)
            } else {
                Text("Ничего в ближайшие сутки").font(.caption)
            }
        }
    }

    private var list: some View {
        VStack(alignment: .leading, spacing: family == .systemLarge ? 9 : 7) {
            HStack(spacing: 6) {
                Image(systemName: "calendar").font(.system(size: 12, weight: .bold)).foregroundStyle(accent)
                Text("Скоро выходит").font(.system(size: 13, weight: .bold)).foregroundStyle(.white)
                Spacer()
                if !entry.items.isEmpty {
                    Text("\(entry.items.count)").font(.system(size: 11, weight: .bold))
                        .padding(.horizontal, 7).padding(.vertical, 2)
                        .background(.white.opacity(0.12), in: Capsule()).foregroundStyle(.white.opacity(0.8))
                }
            }
            if entry.items.isEmpty {
                Spacer()
                HStack {
                    Spacer()
                    Text(entry.failed ? "Нет связи с Shikimori" : "В ближайшие сутки ничего не выходит")
                        .font(.caption).foregroundStyle(.white.opacity(0.6))
                    Spacer()
                }
                Spacer()
            } else {
                ForEach(entry.items.prefix(limit), id: \.self) { item in
                    row(item)
                }
                Spacer(minLength: 0)
            }
        }
    }

    private func row(_ item: AiringItem) -> some View {
        Link(destination: item.slug.flatMap(WidgetShared.titleURL) ?? URL(string: "anixq://schedule")!) {
            HStack(spacing: 9) {
                if let c = item.cover, let img = entry.images[c] {
                    Image(uiImage: img).resizable().scaledToFill().frame(width: 22, height: 31)
                        .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
                } else {
                    RoundedRectangle(cornerRadius: 4, style: .continuous)
                        .fill(item.mine ? accent.opacity(0.35) : .white.opacity(0.08))
                        .frame(width: 22, height: 31)
                        .overlay(Text("\(item.episode)").font(.system(size: 9, weight: .bold)).foregroundStyle(.white.opacity(0.8)))
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(item.title).font(.system(size: 12, weight: item.mine ? .semibold : .regular))
                        .lineLimit(1).foregroundStyle(.white)
                    Text("Серия \(item.episode)").font(.system(size: 10)).foregroundStyle(.white.opacity(0.5))
                }
                Spacer(minLength: 4)
                Text(dayLabel(item.time) + item.time.formatted(date: .omitted, time: .shortened))
                    .font(.system(size: 11, weight: .semibold).monospacedDigit())
                    .padding(.horizontal, 7).padding(.vertical, 3)
                    .background(item.mine ? accent.opacity(0.9) : .white.opacity(0.1), in: Capsule())
                    .foregroundStyle(.white)
            }
        }
    }

    private func dayLabel(_ d: Date) -> String {
        let cal = Calendar.current
        if cal.isDateInToday(d) { return "" }
        if cal.isDateInTomorrow(d) { return "завтра " }
        return d.formatted(.dateTime.day().month(.abbreviated)) + " "
    }
}

struct TodayWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "AnixQToday", provider: TodayProvider()) { entry in
            TodayWidgetView(entry: entry)
        }
        .configurationDisplayName("Скоро выходит")
        .description("Серии на ближайшие сутки — ваши тайтлы первыми.")
        .supportedFamilies([.systemMedium, .systemLarge, .accessoryRectangular])
    }
}

// MARK: - Downloads Live Activity

struct DownloadLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: DownloadActivityAttributes.self) { context in
            HStack(spacing: 12) {
                Image(systemName: "arrow.down.circle.fill").font(.title).foregroundStyle(accent)
                VStack(alignment: .leading, spacing: 4) {
                    Text(context.state.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                    Text(context.state.detail).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    ProgressView(value: context.state.progress).tint(accent)
                }
                Text("\(Int(context.state.progress * 100))%").font(.headline.monospacedDigit())
            }
            .padding()
            .activityBackgroundTint(Color.black.opacity(0.85))
            .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Image(systemName: "arrow.down.circle.fill").foregroundStyle(accent).font(.title2)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text("\(Int(context.state.progress * 100))%").font(.headline.monospacedDigit())
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(context.state.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                        Text(context.state.detail).font(.caption).foregroundStyle(.secondary)
                        ProgressView(value: context.state.progress).tint(accent)
                    }
                }
            } compactLeading: {
                Image(systemName: "arrow.down.circle.fill").foregroundStyle(accent)
            } compactTrailing: {
                Text("\(Int(context.state.progress * 100))%").font(.caption2.monospacedDigit())
            } minimal: {
                Image(systemName: "arrow.down.circle.fill").foregroundStyle(accent)
            }
        }
    }
}

@main
struct AnixQWidgets: WidgetBundle {
    var body: some Widget {
        ContinueWidget()
        TodayWidget()
        DownloadLiveActivity()
    }
}
