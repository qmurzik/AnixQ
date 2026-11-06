import SwiftUI

// MARK: - Quick sections under the hero

/// Colourful shortcuts: one tap into the most used catalog views.
struct DiscoverStrip: View {
    private enum Dest { case smart, season, catalog(CatalogRoute) }

    private struct Item: Identifiable {
        let title: String
        let icon: String
        let colors: [Color]
        let route: Dest
        var id: String { title }
    }

    private var items: [Item] {
        var movies = CatalogQuery(sort: .rating)
        movies.types = [.movie]
        return [
            Item(title: "Для меня", icon: "sparkles", colors: [.pink, .purple], route: .smart),
            Item(title: "Онгоинги", icon: "dot.radiowaves.left.and.right", colors: [.orange, .red],
                 route: .catalog(CatalogRoute(title: "Онгоинги", query: CatalogQuery(status: .ongoing, sort: .popular)))),
            Item(title: "Сезон", icon: "leaf.fill", colors: [.green, .teal], route: .season),
            Item(title: "Топ", icon: "trophy.fill", colors: [.yellow, .orange],
                 route: .catalog(CatalogRoute(title: "Лучшее по рейтингу", query: CatalogQuery(sort: .rating)))),
            Item(title: "Фильмы", icon: "film.fill", colors: [.blue, .indigo],
                 route: .catalog(CatalogRoute(title: "Фильмы", query: movies))),
            Item(title: "На вечер", icon: "moon.stars.fill", colors: [.indigo, .purple],
                 route: .catalog(CatalogRoute(title: "До 13 серий", query: .short)))
        ]
    }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(items) { item in
                    link(item.route) {
                        HStack(spacing: 8) {
                            Image(systemName: item.icon)
                                .font(.system(size: 13, weight: .bold))
                                .foregroundStyle(.white)
                                .frame(width: 28, height: 28)
                                .background(LinearGradient(colors: item.colors, startPoint: .topLeading, endPoint: .bottomTrailing),
                                            in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                            Text(item.title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                        }
                        .padding(.leading, 6).padding(.trailing, 14).padding(.vertical, 6)
                        .surface(Capsule(), level: .card)
                    }
                }
            }
            .padding(.horizontal)
        }
    }

    @ViewBuilder
    private func link<L: View>(_ d: Dest, @ViewBuilder label: () -> L) -> some View {
        switch d {
        case .smart: NavigationLink(value: SmartRoute(), label: label).buttonStyle(PressableStyle())
        case .season: NavigationLink(value: SeasonRoute(), label: label).buttonStyle(PressableStyle())
        case .catalog(let r): NavigationLink(value: r, label: label).buttonStyle(PressableStyle())
        }
    }
}

// MARK: - Top 10 with big numbers

struct TopTenShelf: View {
    let title: String
    let titles: [Title]
    var seeAll: CatalogRoute? = nil

    var body: some View {
        if !titles.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                SectionTitle(title: title) {
                    if let seeAll { SeeAllLink(route: seeAll) }
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .bottom, spacing: 4) {
                        ForEach(Array(titles.prefix(10).enumerated()), id: \.element.id) { i, t in
                            NavigationLink(value: t) {
                                HStack(alignment: .bottom, spacing: -18) {
                                    Text("\(i + 1)")
                                        .font(.system(size: 92, weight: .black, design: .rounded))
                                        .foregroundStyle(LinearGradient(colors: [Theme.accent.opacity(0.95), Theme.accent.opacity(0.25)],
                                                                        startPoint: .top, endPoint: .bottom))
                                        .shadow(color: .black.opacity(0.5), radius: 2, x: 2, y: 2)
                                        .frame(width: i == 9 ? 104 : 58, alignment: .trailing)
                                        .offset(y: 14)
                                        .zIndex(0)
                                    PosterImage(url: t.coverURL).frame(width: 112).zIndex(1)
                                        .shadow(color: .black.opacity(0.35), radius: 8, y: 4)
                                }
                            }
                            .buttonStyle(PressableStyle())
                            .titleQuickActions(t)
                        }
                    }
                    .padding(.horizontal)
                    .padding(.bottom, 6)
                }
            }
        }
    }
}

struct SeeAllLink: View {
    let route: CatalogRoute
    var body: some View {
        NavigationLink(value: route) {
            HStack(spacing: 3) {
                Text("Все")
                Image(systemName: "chevron.right").font(.caption.weight(.bold))
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Theme.accent)
        }
    }
}

// MARK: - Meta chips

struct MetaChip: View {
    let text: String
    var icon: String? = nil
    var tint: Color? = nil

    var body: some View {
        HStack(spacing: 4) {
            if let icon { Image(systemName: icon).font(.caption2.weight(.bold)) }
            Text(text).font(.caption.weight(.semibold)).lineLimit(1)
        }
        .padding(.horizontal, 9).padding(.vertical, 5)
        .foregroundStyle(tint ?? .primary)
        .background((tint ?? Color.primary).opacity(0.14), in: Capsule())
    }
}
