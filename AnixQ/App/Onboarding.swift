import SwiftUI
import UserNotifications

/// First launch: style, accounts, favourite dubs and genres — so the home screen has
/// recommendations right away instead of an empty library.
struct OnboardingView: View {
    @Binding var done: Bool
    @EnvironmentObject private var library: LibraryStore
    // Survives the UI rebuild that happens when a style is picked.
    @AppStorage("onboarding.page") private var page = 0
    @AppStorage(AppStyle.storageKey) private var styleRaw = AppStyle.classic.rawValue
    @State private var dubs: [String] = DubPreferences.favorites
    @State private var genres: [Genre] = []
    @State private var picked: Set<Int> = Set(UserDefaults.standard.array(forKey: "onboarding.genres") as? [Int] ?? [])
    @State private var showLib = false
    @State private var showShiki = false
    @State private var libIn = LibSession.isLoggedIn
    @State private var shikiIn = ShikiSession.isLoggedIn

    static let popularDubs = ["AniLibria", "AnimeVost", "Studio Band", "Dream Cast", "SHIZA Project", "AniDUB",
                              "JAM CLUB", "AniStar", "Ryuko Studio", "Субтитры"]
    private let pages = 5

    var body: some View {
        VStack(spacing: 0) {
            TabView(selection: $page) {
                welcome.tag(0)
                stylePage.tag(1)
                accounts.tag(2)
                dubsPage.tag(3)
                genresPage.tag(4)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .animation(.easeInOut, value: page)

            VStack(spacing: 12) {
                HStack(spacing: 6) {
                    ForEach(0..<pages, id: \.self) { i in
                        Capsule().fill(i == page ? Theme.accent : Color.secondary.opacity(0.3))
                            .frame(width: i == page ? 22 : 7, height: 7)
                    }
                }
                Button {
                    if page < pages - 1 { page += 1 } else { finish() }
                } label: {
                    Text(page == 0 ? "Начать" : page < pages - 1 ? "Дальше" : "Готово").frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryButtonStyle())
                if page > 0 && page < pages - 1 {
                    Button("Пропустить") { page += 1 }.font(.subheadline).foregroundStyle(.secondary)
                } else {
                    Color.clear.frame(height: 20)
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 20)
        }
        .styledBackground()
        .sheet(isPresented: $showLib) { LibLoginView { _ in libIn = LibSession.isLoggedIn } }
        .sheet(isPresented: $showShiki) { ShikiLoginView { shikiIn = ShikiSession.isLoggedIn } }
        .task { genres = await LibAPI.genres() }
    }

    // MARK: Pages

    private var welcome: some View {
        VStack(spacing: 22) {
            Spacer()
            Image("AppIcon-Preview").resizable().frame(width: 110, height: 110)
                .clipShape(RoundedRectangle(cornerRadius: 25, style: .continuous))
                .shadow(color: Theme.accent.opacity(0.5), radius: 30, y: 12)
            Text("Добро пожаловать в AnixQ").font(.largeTitle.bold()).multilineTextAlignment(.center)
            VStack(alignment: .leading, spacing: 14) {
                feature("play.rectangle.fill", "Плеер без рекламы, загрузки офлайн")
                feature("bell.badge.fill", "Уведомления о сериях в вашей озвучке")
                feature("arrow.triangle.2.circlepath", "Синхронизация с AnimeLib и Shikimori")
                feature("person.2.fill", "Совместный просмотр по ссылке")
            }
            .padding(.horizontal, 30)
            Spacer()
        }
        .padding()
    }

    private func feature(_ icon: String, _ text: String) -> some View {
        HStack(spacing: 14) {
            Image(systemName: icon).font(.title3).foregroundStyle(Theme.accent).frame(width: 30)
            Text(text).font(.body)
        }
    }

    private var stylePage: some View {
        VStack(spacing: 18) {
            header("Как будет выглядеть?", "Можно поменять в любой момент в настройках.")
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 120))], spacing: 16) {
                ForEach(AppStyle.allCases) { s in
                    Button {
                        UserDefaults.standard.set(s.rawValue, forKey: AppStyle.storageKey)
                        Theme.configureAppearance()
                        styleRaw = s.rawValue
                    } label: { StylePreview(style: s, selected: styleRaw == s.rawValue) }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal)
            Spacer()
        }
    }

    private var accounts: some View {
        VStack(spacing: 18) {
            header("Аккаунты", "Необязательно, но так закладки и просмотренные серии будут везде одинаковыми.")
            accountRow("AnimeLib", "Плеер без рекламы и синхронизация закладок", done: libIn) { showLib = true }
            accountRow("Shikimori", "Отметки серий, оценки, рекомендации и друзья", done: shikiIn) { showShiki = true }
            Spacer()
        }
        .padding(.horizontal)
    }

    private func accountRow(_ name: String, _ text: String, done: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: done ? "checkmark.circle.fill" : "person.crop.circle.badge.plus")
                    .font(.title2).foregroundStyle(done ? .green : Theme.accent)
                VStack(alignment: .leading, spacing: 2) {
                    Text(done ? "\(name) — вход выполнен" : "Войти в \(name)").font(.headline).foregroundStyle(.primary)
                    Text(text).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
            }
            .padding(16)
            .cardStyle()
        }
        .buttonStyle(PressableStyle())
        .disabled(done)
    }

    private var dubsPage: some View {
        VStack(spacing: 18) {
            header("Любимые озвучки", "Выберите в порядке приоритета — первая доступная включится сама.")
            ScrollView {
                FlowChips(items: Self.popularDubs, selected: dubs) { name in
                    if let i = dubs.firstIndex(of: name) { dubs.remove(at: i) } else { dubs.append(name) }
                } badge: { name in dubs.firstIndex(of: name).map { "\($0 + 1)" } }
                .padding(.horizontal)
            }
        }
    }

    private var genresPage: some View {
        VStack(spacing: 18) {
            header("Что вам нравится?", "Отметьте жанры — на главной сразу появится подборка «Для вас».")
            ScrollView {
                if genres.isEmpty { ProgressView().padding() }
                FlowChips(items: genres.map(\.name), selected: genres.filter { picked.contains($0.id) }.map(\.name)) { name in
                    guard let g = genres.first(where: { $0.name == name }) else { return }
                    if picked.contains(g.id) { picked.remove(g.id) } else { picked.insert(g.id) }
                } badge: { _ in nil }
                .padding(.horizontal)
            }
        }
    }

    private func header(_ title: String, _ subtitle: String) -> some View {
        VStack(spacing: 8) {
            Text(title).font(.title.bold()).multilineTextAlignment(.center)
            Text(subtitle).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }
        .padding(.top, 40)
        .padding(.horizontal)
    }

    private func finish() {
        DubPreferences.favorites = dubs
        UserDefaults.standard.set(Array(picked), forKey: "onboarding.genres")
        UserDefaults.standard.set(true, forKey: "onboarding.done")
        Task {
            // Quiet permission: new-version and new-episode notices land in Notification Center.
            _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.provisional, .alert, .sound, .badge])
        }
        withAnimation { done = true }
    }
}

/// Wrapping row of selectable chips.
struct FlowChips: View {
    let items: [String]
    let selected: [String]
    let toggle: (String) -> Void
    let badge: (String) -> String?

    var body: some View {
        FlowLayout(spacing: 8) {
            ForEach(items, id: \.self) { item in
                let on = selected.contains(item)
                Button { toggle(item) } label: {
                    HStack(spacing: 6) {
                        if let b = badge(item) {
                            Text(b).font(.caption.weight(.heavy)).frame(width: 18, height: 18)
                                .background(Color.white.opacity(0.25), in: Circle())
                        }
                        Text(item).font(.subheadline.weight(.medium))
                    }
                    .padding(.horizontal, 14).padding(.vertical, 9)
                    .background(on ? Theme.accent : Theme.card, in: Capsule())
                    .foregroundStyle(on ? Color.white : .primary)
                }
                .buttonStyle(.plain)
            }
        }
    }
}

struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 320
        var x: CGFloat = 0, y: CGFloat = 0, row: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x + size.width > width, x > 0 { x = 0; y += row + spacing; row = 0 }
            x += size.width + spacing
            row = max(row, size.height)
        }
        return CGSize(width: width, height: y + row)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, row: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x + size.width > bounds.maxX, x > bounds.minX { x = bounds.minX; y += row + spacing; row = 0 }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            row = max(row, size.height)
        }
    }
}
