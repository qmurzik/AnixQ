import SwiftUI

// MARK: - Models & store

struct CollectionItem: Codable, Hashable {
    var slug: String
    var title: String
    var cover: String?
}

struct UserCollection: Codable, Identifiable, Hashable {
    var id = UUID()
    var name: String
    var emoji: String = "📺"
    var items: [CollectionItem] = []
    var updated = Date()
}

struct TitleNote: Codable, Hashable {
    var text: String = ""
    var rating: Int?
    var episodes: [String: String] = [:]
    var isEmpty: Bool { text.isEmpty && rating == nil && episodes.isEmpty }
}

/// Personal collections ("Лучшее для новичка") and notes / own score per title.
@MainActor
final class UserDataStore: ObservableObject {
    static let shared = UserDataStore()

    @Published private(set) var collections: [UserCollection] = []
    @Published private(set) var notes: [String: TitleNote] = [:]

    struct Snapshot: Codable {
        var collections: [UserCollection]
        var notes: [String: TitleNote]
    }

    private let url: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("userdata.json")

    init() {
        if let data = try? Data(contentsOf: url), let s = try? JSONDecoder().decode(Snapshot.self, from: data) {
            collections = s.collections
            notes = s.notes
        }
    }

    var snapshot: Snapshot { Snapshot(collections: collections, notes: notes) }

    func restore(_ s: Snapshot) {
        collections = s.collections
        notes = s.notes
        save()
    }

    private func save() {
        if let data = try? JSONEncoder().encode(snapshot) { try? data.write(to: url, options: .atomic) }
    }

    // Collections

    @discardableResult
    func create(_ name: String, emoji: String = "📺", items: [CollectionItem] = []) -> UserCollection {
        let c = UserCollection(name: name.isEmpty ? "Подборка" : name, emoji: emoji, items: items)
        collections.insert(c, at: 0)
        save()
        return c
    }

    func update(_ c: UserCollection) {
        guard let i = collections.firstIndex(where: { $0.id == c.id }) else { return }
        var c = c
        c.updated = Date()
        collections[i] = c
        save()
    }

    func delete(_ c: UserCollection) {
        collections.removeAll { $0.id == c.id }
        save()
    }

    func contains(_ t: Title, in c: UserCollection) -> Bool { c.items.contains { $0.slug == t.slugURL } }

    func toggle(_ t: Title, in c: UserCollection) {
        guard var c = collections.first(where: { $0.id == c.id }) else { return }
        if let i = c.items.firstIndex(where: { $0.slug == t.slugURL }) {
            c.items.remove(at: i)
        } else {
            c.items.append(CollectionItem(slug: t.slugURL, title: t.title, cover: t.coverThumb ?? t.cover))
        }
        update(c)
    }

    // Notes

    func note(_ titleId: Int) -> TitleNote { notes[String(titleId)] ?? TitleNote() }

    func setNote(_ n: TitleNote, for titleId: Int) {
        notes[String(titleId)] = n.isEmpty ? nil : n
        save()
    }

    // Sharing: the whole list travels inside the link (no server).

    private struct Payload: Codable {
        var n: String
        var e: String
        var i: [[String]]
    }

    static func shareURL(_ c: UserCollection) -> URL? {
        // Third field: cover path (shortened), so the web page shows posters at once.
        let prefix = "https://cover.imglib.info/uploads/anime/"
        let p = Payload(n: c.name, e: c.emoji, i: c.items.map { item in
            guard let cover = item.cover, !cover.isEmpty else { return [item.slug, item.title] }
            return [item.slug, item.title, cover.hasPrefix(prefix) ? String(cover.dropFirst(prefix.count)) : cover]
        })
        guard let data = try? JSONEncoder().encode(p) else { return nil }
        let b64 = data.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        return URL(string: "https://qmurzik.github.io/AnixQ/list/#\(b64)")
    }

    static func decode(_ token: String) -> UserCollection? {
        var b64 = token.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64 += "=" }
        guard let data = Data(base64Encoded: b64), let p = try? JSONDecoder().decode(Payload.self, from: data) else { return nil }
        return UserCollection(name: p.n, emoji: p.e, items: p.i.compactMap { $0.count >= 2 ? CollectionItem(slug: $0[0], title: $0[1]) : nil })
    }
}

// MARK: - Views

struct CollectionsSection: View {
    @ObservedObject private var store = UserDataStore.shared
    @State private var creating = false
    @State private var newName = ""

    var body: some View {
        Section {
            ForEach(store.collections) { c in
                NavigationLink(value: LibraryRoute.collection(c.id)) {
                    HStack {
                        Text(c.emoji)
                        Text(c.name)
                        Spacer()
                        Text("\(c.items.count)").foregroundStyle(.secondary)
                    }
                }
            }
            Button { creating = true } label: { Label("Новая подборка", systemImage: "plus.rectangle.on.rectangle") }
        } header: {
            Text("Мои подборки")
        } footer: {
            if store.collections.isEmpty {
                Text("Соберите, например, «Лучшее для новичка» и отправьте друзьям ссылкой.")
            }
        }
        .alert("Новая подборка", isPresented: $creating) {
            TextField("Название", text: $newName)
            Button("Создать") { store.create(newName); newName = "" }
            Button("Отмена", role: .cancel) { newName = "" }
        }
    }
}

struct CollectionView: View {
    let id: UUID
    @ObservedObject private var store = UserDataStore.shared
    @State private var renaming = false
    @State private var name = ""
    @State private var confirmDelete = false
    @Environment(\.dismiss) private var dismiss
    private static let emojis = ["📺", "🔥", "❤️", "⭐️", "🌸", "⚔️", "😂", "😭", "👻", "🍿", "🎌", "🧠"]

    var body: some View {
        if let c = store.collections.first(where: { $0.id == id }) {
            List {
                Section {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            ForEach(Self.emojis, id: \.self) { e in
                                Text(e).font(.title2).padding(6)
                                    .background(c.emoji == e ? Theme.accent.opacity(0.3) : .clear, in: Circle())
                                    .onTapGesture { var x = c; x.emoji = e; store.update(x) }
                            }
                        }
                    }
                }
                if c.items.isEmpty {
                    Text("Добавляйте тайтлы долгим нажатием на постер → «В подборку» или кнопкой на странице тайтла.")
                        .foregroundStyle(.secondary)
                }
                ForEach(c.items, id: \.slug) { item in
                    NavigationLink(value: SlugRoute(slug: item.slug)) {
                        HStack(spacing: 12) {
                            PosterImage(url: item.cover.flatMap(URL.init(string:)), radius: 6).frame(width: 44)
                            Text(item.title).font(.subheadline.weight(.semibold)).lineLimit(2)
                        }
                    }
                }
                .onDelete { idx in var x = c; x.items.remove(atOffsets: idx); store.update(x) }
                .onMove { from, to in var x = c; x.items.move(fromOffsets: from, toOffset: to); store.update(x) }
            }
            .styledList()
            .navigationTitle("\(c.emoji) \(c.name)")
            .toolbar {
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    if let url = UserDataStore.shareURL(c), !c.items.isEmpty {
                        ShareLink(item: url, subject: Text(c.name), message: Text("\(c.emoji) \(c.name) — подборка аниме в AnixQ")) {
                            Image(systemName: "square.and.arrow.up")
                        }
                    }
                    Menu {
                        Button { name = c.name; renaming = true } label: { Label("Переименовать", systemImage: "pencil") }
                        EditButton()
                        Button(role: .destructive) { confirmDelete = true } label: { Label("Удалить подборку", systemImage: "trash") }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
            }
            .alert("Название", isPresented: $renaming) {
                TextField("Название", text: $name)
                Button("Сохранить") { var x = c; x.name = name; store.update(x) }
                Button("Отмена", role: .cancel) {}
            }
            .confirmationDialog("Удалить «\(c.name)»?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Удалить", role: .destructive) { store.delete(c); dismiss() }
            }
        } else {
            EmptyState(icon: "rectangle.stack", title: "Подборка удалена")
        }
    }
}

/// A list someone shared by link: look, then save to own collections.
struct SharedCollectionView: View {
    let collection: UserCollection
    @ObservedObject private var store = UserDataStore.shared
    @State private var saved = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(collection.items, id: \.slug) { item in
                        NavigationLink(value: SlugRoute(slug: item.slug)) { Text(item.title).font(.subheadline.weight(.semibold)) }
                    }
                } header: {
                    Text("\(collection.items.count) тайтлов")
                }
            }
            .navigationTitle("\(collection.emoji) \(collection.name)")
            .navigationBarTitleDisplayMode(.inline)
            .appDestinations()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(saved ? "Сохранено" : "Сохранить себе") {
                        store.create(collection.name, emoji: collection.emoji, items: collection.items)
                        saved = true
                    }
                    .disabled(saved)
                }
            }
        }
    }
}

/// Opens a title known only by its slug (collections, shared lists).
struct SlugRoute: Hashable { let slug: String }

struct AddToCollectionSheet: View {
    let title: Title
    @ObservedObject private var store = UserDataStore.shared
    @State private var newName = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        TextField("Новая подборка", text: $newName)
                        Button("Создать") {
                            let c = store.create(newName)
                            store.toggle(title, in: c)
                            newName = ""
                        }
                        .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
                Section {
                    ForEach(store.collections) { c in
                        Button { store.toggle(title, in: c) } label: {
                            HStack {
                                Text(c.emoji)
                                Text(c.name).foregroundStyle(.primary)
                                Spacer()
                                if store.contains(title, in: c) { Image(systemName: "checkmark").foregroundStyle(Theme.accent) }
                            }
                        }
                    }
                }
            }
            .navigationTitle("В подборку")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }
}

/// "Мои заметки" on the title page: own score, a note, notes per episode.
struct NotesSection: View {
    let title: Title
    @ObservedObject private var store = UserDataStore.shared
    @State private var editing = false
    @State private var draft = ""
    @State private var addingTo = false

    var body: some View {
        let note = store.note(title.id)
        VStack(alignment: .leading, spacing: 12) {
            SectionTitle(title: "Мои заметки") {
                Button { addingTo = true } label: { Label("В подборку", systemImage: "rectangle.stack.badge.plus").font(.subheadline) }
            }
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 6) {
                    Text("Моя оценка").font(.subheadline.weight(.semibold))
                    Spacer()
                    ForEach(1...10, id: \.self) { i in
                        Image(systemName: (note.rating ?? 0) >= i ? "star.fill" : "star")
                            .font(.system(size: 14))
                            .foregroundStyle((note.rating ?? 0) >= i ? Color.yellow : Color.secondary)
                            .onTapGesture {
                                var n = note
                                n.rating = n.rating == i ? nil : i
                                store.setNote(n, for: title.id)
                                Haptics.tap()
                            }
                    }
                }
                if editing {
                    TextEditor(text: $draft).frame(minHeight: 90).scrollContentBackground(.hidden)
                        .padding(6).background(Theme.cardHighlight, in: RoundedRectangle(cornerRadius: 10))
                    HStack {
                        Button("Отмена") { editing = false }
                        Spacer()
                        Button("Сохранить") {
                            var n = note
                            n.text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                            store.setNote(n, for: title.id)
                            editing = false
                        }
                        .bold()
                    }
                } else {
                    Button { draft = note.text; editing = true } label: {
                        Text(note.text.isEmpty ? "Добавить заметку — мысли, кого советовать, на чём остановились…" : note.text)
                            .font(.subheadline).foregroundStyle(note.text.isEmpty ? .secondary : .primary)
                            .frame(maxWidth: .infinity, alignment: .leading).multilineTextAlignment(.leading)
                    }
                    .buttonStyle(.plain)
                }
                if !note.episodes.isEmpty {
                    Divider()
                    ForEach(note.episodes.sorted { (Double($0.key) ?? 0) < (Double($1.key) ?? 0) }, id: \.key) { k, v in
                        HStack(alignment: .top, spacing: 8) {
                            Text("Серия \(k)").font(.caption.weight(.bold)).foregroundStyle(Theme.accent).frame(width: 64, alignment: .leading)
                            Text(v).font(.caption)
                        }
                    }
                }
            }
            .padding(14)
            .cardStyle()
            .padding(.horizontal)
        }
        .sheet(isPresented: $addingTo) { AddToCollectionSheet(title: title) }
    }
}
