import SwiftUI

struct BrowseView: View {
    @StateObject private var loader = CatalogLoader()
    @State private var query = CatalogQuery()
    @State private var showFilters = false
    @State private var genres: [Genre] = []
    @State private var showRandom = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                chips
                PosterGrid(titles: loader.items) { Task { await loader.loadMore() } }
                    .padding(.horizontal)
                if loader.isLoading {
                    ProgressView().frame(maxWidth: .infinity).padding()
                } else if let e = loader.error {
                    ErrorState(message: e) { Task { await loader.reset(query) } }
                } else if loader.items.isEmpty {
                    EmptyState(icon: "line.3.horizontal.decrease.circle", title: "Ничего не найдено",
                               subtitle: "Попробуйте ослабить фильтры")
                }
            }
            .padding(.vertical, 8)
        }
        .navigationTitle("Каталог")
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { showRandom = true } label: { Image(systemName: "dice") }
                    .accessibilityLabel("Что посмотреть?")
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { showFilters = true } label: {
                    Image(systemName: query.activeFilterCount > 0
                          ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle")
                }
            }
        }
        .sheet(isPresented: $showFilters) {
            FilterSheet(query: $query, genres: genres).presentationDetents([.large])
        }
        .sheet(isPresented: $showRandom) { RandomPickView() }
        .appDestinations()
        .task {
            if loader.items.isEmpty { await loader.reset(query) }
            if genres.isEmpty { genres = await LibAPI.genres() }
        }
        .refreshable { await loader.reset(query) }
        .onChange(of: query) { q in Task { await loader.reset(q) } }
    }

    private var chips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Menu {
                    Picker("Сортировка", selection: $query.sort) {
                        ForEach(CatalogSort.allCases) { Text($0.title).tag($0) }
                    }
                } label: {
                    chip(query.sort.title, icon: "arrow.up.arrow.down", on: true, menu: true)
                }
                ForEach([CatalogStatus.ongoing, .finished, .announce]) { s in
                    Button { toggle(&query.statuses, s) } label: { chip(s.title, on: query.statuses.contains(s)) }
                }
                ForEach([CatalogType.tv, .movie, .ova, .ona]) { ty in
                    Button { toggle(&query.types, ty) } label: { chip(ty.title, on: query.types.contains(ty)) }
                }
                if query.activeFilterCount > 0 {
                    Button {
                        let sort = query.sort
                        query = CatalogQuery(sort: sort)
                    } label: {
                        chip("Сбросить", icon: "xmark", on: false)
                    }
                }
            }
            .padding(.horizontal)
        }
        .buttonStyle(.plain)
    }

    private func toggle<T: Hashable>(_ set: inout Set<T>, _ v: T) {
        if set.contains(v) { set.remove(v) } else { set.insert(v) }
    }

    private func chip(_ text: String, icon: String? = nil, on: Bool, menu: Bool = false) -> some View {
        HStack(spacing: 4) {
            if let icon { Image(systemName: icon).font(.caption.weight(.bold)) }
            Text(text)
            if menu { Image(systemName: "chevron.down").font(.caption2.weight(.bold)) }
        }
        .font(.subheadline.weight(.medium))
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
        .background(on ? AnyShapeStyle(Color.primary) : AnyShapeStyle(.thinMaterial), in: Capsule())
        .foregroundStyle(on ? Color(.systemBackground) : Color.primary)
    }
}

struct FilterSheet: View {
    @Binding var query: CatalogQuery
    let genres: [Genre]
    @Environment(\.dismiss) private var dismiss
    @State private var draft = CatalogQuery()
    private let years = Array((1970...Calendar.current.component(.year, from: Date()) + 1).reversed())

    var body: some View {
        NavigationStack {
            Form {
                Section("Сортировка") {
                    Picker("Сортировка", selection: $draft.sort) {
                        ForEach(CatalogSort.allCases) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.segmented)
                }
                Section("Тип") {
                    ForEach(CatalogType.allCases) { ty in
                        toggleRow(ty.title, isOn: draft.types.contains(ty)) {
                            if draft.types.contains(ty) { draft.types.remove(ty) } else { draft.types.insert(ty) }
                        }
                    }
                }
                Section("Статус") {
                    ForEach(CatalogStatus.allCases) { s in
                        toggleRow(s.title, isOn: draft.statuses.contains(s)) {
                            if draft.statuses.contains(s) { draft.statuses.remove(s) } else { draft.statuses.insert(s) }
                        }
                    }
                }
                Section("Год выхода") {
                    Picker("С", selection: $draft.yearFrom) {
                        Text("Любой").tag(Int?.none)
                        ForEach(years, id: \.self) { Text(String($0)).tag(Int?.some($0)) }
                    }
                    Picker("По", selection: $draft.yearTo) {
                        Text("Любой").tag(Int?.none)
                        ForEach(years, id: \.self) { Text(String($0)).tag(Int?.some($0)) }
                    }
                }
                Section("Жанры") {
                    if genres.isEmpty { ProgressView() }
                    ForEach(genres) { g in
                        toggleRow(g.name, isOn: draft.genres.contains(g.id)) {
                            if draft.genres.contains(g.id) { draft.genres.remove(g.id) } else { draft.genres.insert(g.id) }
                        }
                    }
                }
            }
            .navigationTitle("Фильтры")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Сбросить") { draft = CatalogQuery(sort: draft.sort) }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Готово") { query = draft; dismiss() }.bold()
                }
            }
        }
        .onAppear { draft = query }
    }

    private func toggleRow(_ title: String, isOn: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title).foregroundStyle(.primary)
                Spacer()
                if isOn { Image(systemName: "checkmark").foregroundStyle(Theme.accent).fontWeight(.semibold) }
            }
        }
    }
}

// MARK: - Search

struct SearchView: View {
    @State private var text = ""
    @State private var results: [Title] = []
    @State private var searching = false
    @State private var genres: [Genre] = []
    @State private var task: Task<Void, Never>?
    @AppStorage("search.recent") private var recentRaw = ""
    @State private var showTrace = false

    private var recent: [String] { recentRaw.split(separator: "\n").map(String.init) }

    var body: some View {
        List {
            if text.trimmingCharacters(in: .whitespaces).isEmpty {
                if !recent.isEmpty {
                    Section {
                        ForEach(recent, id: \.self) { q in
                            Button { text = q } label: {
                                Label(q, systemImage: "clock.arrow.circlepath").foregroundStyle(.primary)
                            }
                        }
                    } header: {
                        HStack {
                            Text("Недавние")
                            Spacer()
                            Button("Очистить") { recentRaw = "" }.font(.caption).textCase(nil)
                        }
                    }
                }
                Section("Жанры") {
                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                        ForEach(genres) { g in
                            NavigationLink(value: GenreRoute(genre: g)) {
                                Text(g.name)
                                    .font(.subheadline.weight(.semibold))
                                    .frame(maxWidth: .infinity, minHeight: 54)
                                    .background(gradient(for: g.id), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                                    .foregroundStyle(.white)
                            }
                            .buttonStyle(PressableStyle())
                        }
                    }
                    .listRowInsets(EdgeInsets(top: 10, leading: 12, bottom: 10, trailing: 12))
                }
            } else {
                if searching && results.isEmpty {
                    HStack { Spacer(); ProgressView(); Spacer() }
                } else if !searching && results.isEmpty {
                    Text("Ничего не найдено").foregroundStyle(.secondary)
                }
                ForEach(results) { t in
                    NavigationLink(value: t) { TitleRow(title: t) }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Поиск")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { showTrace = true } label: { Image(systemName: "camera.viewfinder") }
                    .accessibilityLabel("Поиск по кадру")
            }
        }
        .sheet(isPresented: $showTrace) { TraceSearchView() }
        .searchable(text: $text, placement: .navigationBarDrawer(displayMode: .always),
                    prompt: "Название, оригинал или английское")
        .onSubmit(of: .search) { remember(text) }
        .onChange(of: text) { q in schedule(q) }
        .appDestinations()
        .task { if genres.isEmpty { genres = await LibAPI.genres() } }
    }

    private func gradient(for id: Int) -> LinearGradient {
        let hues: [Double] = [0.95, 0.6, 0.45, 0.08, 0.78, 0.55, 0.0, 0.3]
        let h = hues[id % hues.count]
        return LinearGradient(colors: [Color(hue: h, saturation: 0.65, brightness: 0.75),
                                       Color(hue: h + 0.05, saturation: 0.75, brightness: 0.5)],
                              startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    private func schedule(_ raw: String) {
        task?.cancel()
        let q = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard q.count >= 2 else { results = []; return }
        searching = true
        task = Task {
            try? await Task.sleep(nanoseconds: 350_000_000)
            guard !Task.isCancelled else { return }
            let r = (try? await LibAPI.search(q)) ?? []
            guard !Task.isCancelled else { return }
            results = r
            searching = false
        }
    }

    private func remember(_ raw: String) {
        let q = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        var list = recent.filter { $0.caseInsensitiveCompare(q) != .orderedSame }
        list.insert(q, at: 0)
        recentRaw = list.prefix(8).joined(separator: "\n")
    }
}
