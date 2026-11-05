import SwiftUI

/// The user's Shikimori entry for a title: status, episode progress and score, editable in place.
struct ShikiRateCard: View {
    let shikiId: Int
    let totalEpisodes: Int
    @State private var status: String?
    @State private var episodes = 0
    @State private var score = 0
    @State private var loaded = false
    @State private var saving = false

    private let statuses: [(String, String)] = [
        ("watching", "Смотрю"), ("planned", "Запланировано"), ("completed", "Просмотрено"),
        ("on_hold", "Отложено"), ("dropped", "Брошено"), ("rewatching", "Пересматриваю")
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label("Shikimori", systemImage: "list.star").font(.headline)
                Spacer()
                if saving { ProgressView().scaleEffect(0.8) }
            }
            if !loaded {
                ProgressView().frame(maxWidth: .infinity)
            } else {
                HStack(spacing: 10) {
                    Menu {
                        ForEach(statuses, id: \.0) { s in
                            Button {
                                status = s.0
                                save(status: s.0, episodes: nil, score: nil)
                            } label: {
                                Label(s.1, systemImage: status == s.0 ? "checkmark" : "circle")
                            }
                        }
                    } label: {
                        chip(statuses.first { $0.0 == status }?.1 ?? "Не в списке", icon: "bookmark")
                    }

                    HStack(spacing: 0) {
                        Button { change(by: -1) } label: { Image(systemName: "minus").frame(width: 34, height: 32) }
                            .disabled(episodes <= 0)
                        Text(totalEpisodes > 0 ? "\(episodes) / \(totalEpisodes)" : "\(episodes) эп.")
                            .font(.subheadline.monospacedDigit().weight(.semibold))
                            .frame(minWidth: 62)
                        Button { change(by: 1) } label: { Image(systemName: "plus").frame(width: 34, height: 32) }
                            .disabled(totalEpisodes > 0 && episodes >= totalEpisodes)
                    }
                    .background(.thinMaterial, in: Capsule())

                    Menu {
                        ForEach((0...10).reversed(), id: \.self) { n in
                            Button(n == 0 ? "Без оценки" : "\(n)") {
                                score = n
                                save(status: nil, episodes: nil, score: n)
                            }
                        }
                    } label: {
                        chip(score > 0 ? "\(score)" : "—", icon: "star.fill")
                    }
                }
                .foregroundStyle(.primary)
            }
        }
        .padding(14)
        .cardStyle()
        .task {
            guard !loaded else { return }
            if let rate = try? await ShikiAPI.userRate(animeId: shikiId) {
                status = rate.str("status")
                episodes = rate.int("episodes") ?? 0
                score = rate.int("score") ?? 0
            }
            loaded = true
        }
    }

    private func chip(_ text: String, icon: String) -> some View {
        HStack(spacing: 5) {
            Image(systemName: icon).font(.caption)
            Text(text).font(.subheadline.weight(.semibold)).lineLimit(1)
        }
        .padding(.horizontal, 12)
        .frame(height: 32)
        .background(.thinMaterial, in: Capsule())
    }

    private func change(by delta: Int) {
        episodes = max(0, episodes + delta)
        var newStatus: String? = nil
        if status == nil || status == "planned" { newStatus = "watching" }
        if totalEpisodes > 0, episodes >= totalEpisodes { newStatus = "completed" }
        if let newStatus { status = newStatus }
        save(status: newStatus, episodes: episodes, score: nil)
    }

    private func save(status: String?, episodes: Int?, score: Int?) {
        saving = true
        Task {
            do {
                try await ShikiAPI.upsertRate(animeId: shikiId, status: status, episodes: episodes, score: score,
                                              force: true)
            } catch {
                DebugLog.log("shiki: card save failed \(error)")
            }
            saving = false
        }
    }
}
