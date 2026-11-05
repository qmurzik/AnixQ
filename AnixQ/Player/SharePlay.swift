import SwiftUI
import AVFoundation
import GroupActivities

/// "Смотреть вместе" over FaceTime: everyone opens the same episode and AVPlayer keeps play/pause/seek in sync.
struct WatchTogether: GroupActivity {
    static let activityIdentifier = "ru.qmods.anixq.watch-together"

    let slug: String
    let titleName: String
    let episodeId: Int
    let episodeNumber: String
    let teamId: Int

    var metadata: GroupActivityMetadata {
        var m = GroupActivityMetadata()
        m.title = titleName
        m.subtitle = "Серия \(episodeNumber)"
        m.type = .watchTogether
        m.fallbackURL = URL(string: "https://qmurzik.github.io/AnixQ/")
        return m
    }
}

extension WatchTogether: Identifiable {
    var id: String { "\(slug)-\(episodeId)-\(teamId)" }
}

@MainActor
final class SharePlayManager: NSObject, ObservableObject {
    static let shared = SharePlayManager()

    @Published private(set) var session: GroupSession<WatchTogether>?
    /// Activity received from another participant that no open player is showing yet.
    @Published var incoming: WatchTogether?
    @Published var message: String?
    @Published private(set) var isEligible = false

    private weak var controller: PlayerController?
    /// Identifier of the item being played, read by AVFoundation off the main actor.
    nonisolated(unsafe) private var itemIdentifier = ""
    private var tasks: [Task<Void, Never>] = []
    private var observer = GroupStateObserver()
    private var listening = false

    var isActive: Bool { session != nil }

    /// Started once at launch; picks up sessions started here or by others on the call.
    func listen() {
        guard !listening else { return }
        listening = true
        isEligible = observer.isEligibleForGroupSession
        Task {
            for await eligible in observer.$isEligibleForGroupSession.values { isEligible = eligible }
        }
        Task {
            for await s in WatchTogether.sessions() { configure(s) }
        }
    }

    private func configure(_ s: GroupSession<WatchTogether>) {
        tasks.forEach { $0.cancel() }
        tasks = []
        session = s
        DebugLog.log("shareplay: session \(s.activity.titleName) ep \(s.activity.episodeNumber)")
        tasks.append(Task { [weak self] in
            for await activity in s.$activity.values { self?.received(activity) }
        })
        tasks.append(Task { [weak self] in
            for await state in s.$state.values {
                if case .invalidated = state { self?.ended() }
            }
        })
        s.join()
        if let controller { coordinate(controller) }
    }

    private func received(_ a: WatchTogether) {
        if let c = controller, c.title.slugURL == a.slug {
            c.apply(sharedEpisodeId: a.episodeId, teamId: a.teamId)
        } else {
            incoming = a
        }
    }

    private func ended() {
        DebugLog.log("shareplay: ended")
        session = nil
        incoming = nil
        tasks.forEach { $0.cancel() }
        tasks = []
    }

    // MARK: Player

    /// Called right before the controller puts a new item into its AVPlayer.
    func itemWillChange(_ c: PlayerController) {
        guard controller === c else { return }
        itemIdentifier = "\(c.episode.id)-\(c.team.id)"
    }

    func attach(_ c: PlayerController) {
        controller = c
        itemIdentifier = "\(c.episode.id)-\(c.team.id)"
        c.player.playbackCoordinator.delegate = self
        if session != nil { coordinate(c) }
    }

    func detach(_ c: PlayerController) {
        guard controller === c else { return }
        controller = nil
    }

    private func coordinate(_ c: PlayerController) {
        guard let session, c.isNative else { return }
        c.player.playbackCoordinator.coordinateWithSession(session)
        if incoming?.slug == c.title.slugURL { incoming = nil }
    }

    /// The local user switched episode or dub: move everyone else too.
    func localChanged(_ c: PlayerController) {
        guard let session, controller === c else { return }
        let a = Self.activity(for: c)
        let cur = session.activity
        if cur.slug != a.slug || cur.episodeId != a.episodeId || cur.teamId != a.teamId {
            session.activity = a
        }
    }

    func start(with c: PlayerController) async {
        guard c.isNative else {
            message = "Смотреть вместе можно только в плеере AnimeLib (⚡), не в Kodik."
            return
        }
        let activity = Self.activity(for: c)
        switch await activity.prepareForActivation() {
        case .activationPreferred:
            do { _ = try await activity.activate() } catch {
                message = "Не удалось начать SharePlay: \(error.localizedDescription)"
            }
        case .activationDisabled:
            message = "Начните звонок FaceTime, а затем нажмите «Смотреть вместе» ещё раз."
        default:
            break
        }
    }

    func leave() {
        session?.leave()
        ended()
    }

    private static func activity(for c: PlayerController) -> WatchTogether {
        WatchTogether(slug: c.title.slugURL, titleName: c.title.title, episodeId: c.episode.id,
                      episodeNumber: c.episode.number, teamId: c.team.id)
    }
}

extension SharePlayManager: AVPlayerPlaybackCoordinatorDelegate {
    /// Stream URLs differ between people (mirrors, tokens), so items are matched by episode + dub.
    nonisolated func playbackCoordinator(_ coordinator: AVPlayerPlaybackCoordinator,
                                         identifierFor playerItem: AVPlayerItem) -> String {
        itemIdentifier
    }
}

/// Opens the episode another participant started.
struct SharePlayLauncher: View {
    let activity: WatchTogether
    @StateObject private var model: TitleModel
    @State private var failed = false
    @Environment(\.dismiss) private var dismiss

    init(activity: WatchTogether) {
        self.activity = activity
        _model = StateObject(wrappedValue: TitleModel(title: DebugAutoplayView.placeholder(activity.slug)))
    }

    var body: some View {
        Group {
            if let team = model.teams.first(where: { $0.id == activity.teamId }) ?? model.selectedTeam,
               let index = model.episodes.firstIndex(where: { $0.id == activity.episodeId }) {
                PlayerScreen(title: model.displayTitle, background: nil, episodes: model.episodes,
                             teams: model.teams, startIndex: index, team: team)
            } else if failed {
                VStack(spacing: 14) {
                    EmptyState(icon: "shareplay.slash", title: "Не удалось открыть серию",
                               subtitle: activity.titleName)
                    Button("Закрыть") { dismiss() }.buttonStyle(.bordered)
                }
            } else {
                VStack(spacing: 12) {
                    ProgressView()
                    Text("Подключаемся к «\(activity.titleName)»…").font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
        .preferredColorScheme(.dark)
        .task {
            await model.load(lastTeamId: activity.teamId)
            failed = model.episodes.isEmpty
        }
    }
}
