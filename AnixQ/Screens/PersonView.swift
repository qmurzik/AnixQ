import SwiftUI

struct PersonRoute: Hashable {
    let id: Int
    let isCharacter: Bool
}

/// Character or person (seyu, director…) page from Shikimori, linking back into AnimeLib titles.
struct PersonView: View {
    let route: PersonRoute
    @State private var page: ShikiAPI.PersonPage?
    @State private var loaded = false
    @State private var expanded = false

    var body: some View {
        ScrollView {
            if let page {
                VStack(alignment: .leading, spacing: 22) {
                    HStack(alignment: .top, spacing: 16) {
                        Theme.card.frame(width: 120, height: 170)
                            .overlay {
                                if page.image == nil {
                                    Image(systemName: "person.fill").font(.largeTitle).foregroundStyle(.tertiary)
                                } else {
                                    RemoteImage(url: page.image)
                                }
                            }
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        VStack(alignment: .leading, spacing: 6) {
                            Text(page.name).font(.title2.bold())
                            if let alt = page.altName, alt != page.name {
                                Text(alt).font(.subheadline).foregroundStyle(.secondary)
                            }
                            Text(route.isCharacter ? "Персонаж" : "Человек").font(.caption.weight(.semibold))
                                .padding(.horizontal, 8).padding(.vertical, 3)
                                .background(.thinMaterial, in: Capsule())
                        }
                    }
                    .padding(.horizontal)

                    if let d = page.description {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(d).font(.subheadline).foregroundStyle(.secondary).lineLimit(expanded ? nil : 6)
                            Button(expanded ? "Свернуть" : "Ещё") { withAnimation { expanded.toggle() } }
                                .font(.subheadline.weight(.semibold))
                        }
                        .padding(.horizontal)
                    }

                    if !page.related.isEmpty {
                        VStack(alignment: .leading, spacing: 10) {
                            SectionTitle(route.isCharacter ? "Сейю" : "Персонажи")
                            ScrollView(.horizontal, showsIndicators: false) {
                                LazyHStack(alignment: .top, spacing: 14) {
                                    ForEach(page.related) { p in
                                        NavigationLink(value: PersonRoute(id: p.id, isCharacter: p.isCharacter)) {
                                            VStack(spacing: 6) {
                                                Theme.card.frame(width: 76, height: 76)
                                                    .overlay {
                                                        if p.image == nil {
                                                            Image(systemName: "person.fill").foregroundStyle(.tertiary)
                                                        } else { RemoteImage(url: p.image) }
                                                    }
                                                    .clipShape(Circle())
                                                Text(p.name).font(.caption.weight(.semibold)).lineLimit(2)
                                                    .multilineTextAlignment(.center).foregroundStyle(.primary)
                                            }
                                            .frame(width: 86)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                                .padding(.horizontal)
                            }
                        }
                    }

                    if !page.works.isEmpty {
                        VStack(alignment: .leading, spacing: 10) {
                            SectionTitle(route.isCharacter ? "Аниме" : "Работы")
                            VStack(spacing: 0) {
                                ForEach(Array(page.works.prefix(40).enumerated()), id: \.offset) { i, w in
                                    NavigationLink(value: ShikiRoute(shikiId: w.id, names: [w.name, w.russian])) {
                                        HStack {
                                            VStack(alignment: .leading, spacing: 2) {
                                                Text(w.russian.isEmpty ? w.name : w.russian).font(.subheadline)
                                                    .foregroundStyle(.primary).lineLimit(2)
                                                Text([w.year.map(String.init), w.role.isEmpty ? nil : w.role]
                                                    .compactMap { $0 }.joined(separator: " · "))
                                                    .font(.caption).foregroundStyle(.secondary)
                                            }
                                            Spacer()
                                            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                                        }
                                        .padding(.vertical, 10).padding(.horizontal, 14)
                                    }
                                    .buttonStyle(.plain)
                                    if i < min(page.works.count, 40) - 1 { Divider().padding(.leading, 14) }
                                }
                            }
                            .cardStyle()
                            .padding(.horizontal)
                        }
                    }
                }
                .padding(.vertical)
            } else if loaded {
                EmptyState(icon: "person.crop.circle.badge.questionmark", title: "Не удалось загрузить")
            } else {
                ProgressView().frame(maxWidth: .infinity, minHeight: 300)
            }
        }
        .navigationTitle(page?.name ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if page == nil { page = await ShikiAPI.personPage(id: route.id, isCharacter: route.isCharacter) }
            loaded = true
        }
    }
}
