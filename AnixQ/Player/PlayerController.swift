import SwiftUI
import AVFoundation
import CoreImage
import MediaPlayer

/// Playback state for one title: current episode, team (dub), stream, progress and sync.
@MainActor
final class PlayerController: ObservableObject {
    let player = AVPlayer()
    let title: Title
    let episodes: [LibEpisode]
    let teams: [TeamOption]

    @Published private(set) var index: Int
    @Published private(set) var team: TeamOption
    @Published private(set) var qualities: [StreamQuality] = []
    @Published private(set) var quality: StreamQuality?
    @Published private(set) var kodikURL: URL?
    /// Kodik link of this episode for viewers who join a room from a browser (any team if ours has none).
    private(set) var webKodikURL: URL?
    /// Direct stream for browser viewers (nil for downloaded files).
    var webStreamURL: URL? {
        guard isNative, let q = quality, q.label != "Скачано", !q.url.isFileURL else { return nil }
        return q.url
    }
    var teamName: String { team.team.name }
    @Published private(set) var isResolving = false
    @Published private(set) var isBuffering = false
    @Published private(set) var isPlaying = false
    @Published private(set) var currentTime: Double = 0
    @Published private(set) var duration: Double = 0
    @Published private(set) var error: String?
    @Published private(set) var nextCountdown: Int?
    @Published var rate: Float = PlayerPrefs.rememberSpeed ? PlayerPrefs.savedRate : 1 {
        didSet {
            if isPlaying { player.rate = rate }
            if PlayerPrefs.rememberSpeed { PlayerPrefs.savedRate = rate }
            if oldValue != rate { updateTitleSettings { $0.rate = rate == 1 ? nil : rate } }
        }
    }
    @Published var isScrubbing = false
    /// Settings remembered for this title.
    @Published private(set) var titleSettings: TitlePlayerSettings
    @Published private(set) var autoQuality: Bool
    @Published private(set) var recovering = false
    /// Short message shown over the video (e.g. automatic dub switch).
    @Published var notice: String?
    /// AnimeLib comments with timecodes for this episode.
    @Published private(set) var timedComments: [TimedComment] = []
    /// Bumped to ask the view to translate the current subtitles (iOS 18).
    @Published var translateRequest = 0
    /// "Слушать": no picture, lowest quality, keeps playing with the screen off.
    @Published private(set) var audioOnly = false
    private var qualityBeforeAudio: StreamQuality?
    var subtitleDelay: Double { titleSettings.subtitleDelay }
    var audioDelay: Double { titleSettings.audioDelay }
    @Published var fillScreen = false

    weak var library: LibraryStore?

    @Published private(set) var segments: [SkipSegment] = []
    /// Sleep timer: pause at a time, or when the current episode ends.
    @Published var sleepAt: Date?
    @Published var sleepAfterEpisode = false
    @Published private(set) var subtitleTracks: [LibSubtitle] = []
    @Published private(set) var subtitle: LibSubtitle?
    @Published private(set) var subtitleText: String?
    private var cues: [SubtitleCue] = []
    private var playerId: Int?
    private var lastServerSave = Date.distantPast
    private var hrefs: [String: String] = [:]
    private var servers: [String] = []
    private var serverIndex = 0
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var failObserver: NSObjectProtocol?
    private var statusObservation: NSKeyValueObservation?
    private var itemObservation: NSKeyValueObservation?
    private var loadToken = UUID()
    private var startedAt = Date()
    private var lastSaved = Date.distantPast
    private var autoSkipped = false
    private var endingSkipped = false
    private var videoOutput: AVPlayerItemVideoOutput?
    private var markedWatched = false
    private var countdownTask: Task<Void, Never>?

    var episode: LibEpisode { episodes[index] }
    var isNative: Bool { team.native }
    var hasNext: Bool { index + 1 < episodes.count }
    var hasPrevious: Bool { index > 0 }
    var isLoading: Bool { isResolving || (isBuffering && currentTime < 0.5) }
    var openingRange: ClosedRange<Double> { 0...(PlayerPrefs.openingLength + 30) }
    var opening: SkipSegment? { segments.first { $0.isOpening } }
    var ending: SkipSegment? { segments.first { $0.isEnding } }

    /// Real opening timing from AnimeLib when available, otherwise "first minutes" heuristic.
    var canSkipOpening: Bool {
        guard isNative, !autoSkipped else { return false }
        if let op = opening { return currentTime >= op.from && currentTime < op.to - 1 }
        return currentTime > 3 && currentTime < 240
    }
    var nearEnd: Bool {
        guard hasNext, duration > 0 else { return false }
        if let ed = ending, currentTime >= ed.from { return true }
        return duration - currentTime < 100
    }

    init(title: Title, episodes: [LibEpisode], teams: [TeamOption], index: Int, team: TeamOption) {
        self.title = title
        self.episodes = episodes
        self.teams = teams
        self.index = min(max(0, index), max(0, episodes.count - 1))
        self.team = team
        let saved = TitlePlayerSettings.load(title.id)
        self.titleSettings = saved
        self.autoQuality = (saved.qualityHeight ?? PlayerPrefs.preferredHeight) == 0
        player.allowsExternalPlayback = true
        player.automaticallyWaitsToMinimizeStalling = true
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600),
                                                      queue: .main) { [weak self] t in
            let s = t.seconds
            Task { @MainActor in self?.tick(s) }
        }
        statusObservation = player.observe(\.timeControlStatus, options: [.initial, .new]) { [weak self] p, _ in
            let st = p.timeControlStatus
            Task { @MainActor in self?.statusChanged(st) }
        }
        setupRemoteCommands()
        rateObserver = NotificationCenter.default.addObserver(forName: AVPlayer.rateDidChangeNotification, object: player, queue: .main) { [weak self] n in
            let r = n.userInfo?[AVPlayer.rateDidChangeReasonKey] as? AVPlayer.RateDidChangeReason
            let text: String
            switch r {
            case .some(.audioSessionInterrupted): text = "прерывание звука"
            case .some(.appBackgrounded): text = "приложение свернуто"
            case .some(.setRateFailed): text = "ошибка воспроизведения"
            case .some(.setRateCalled): text = "команда"
            default: text = "неизвестно"
            }
            Task { @MainActor in self?.lastRateReason = text }
        }
        interruptionObserver = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] n in
            guard let raw = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt, let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
            let opts = (n.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt).map { AVAudioSession.InterruptionOptions(rawValue: $0) } ?? []
            Task { @MainActor in
                guard let self else { return }
                if type == .began {
                    self.wasPlayingBeforeInterruption = self.isPlaying || self.isBuffering
                    DebugLog.log("player: audio interrupted")
                } else {
                    DebugLog.log("player: audio interruption ended (resume=\(opts.contains(.shouldResume)))")
                    // A short interruption (another app's sound, Siri, a notification) shouldn't leave the episode paused.
                    if self.wasPlayingBeforeInterruption && opts.contains(.shouldResume) {
                        try? AVAudioSession.sharedInstance().setActive(true)
                        self.player.playImmediately(atRate: self.rate)
                    }
                    self.wasPlayingBeforeInterruption = false
                }
            }
        }
        if let r = saved.rate { rate = r }
        networkObserver = NotificationCenter.default.addObserver(forName: .anixqNetworkBack, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.networkCameBack() }
        }
    }

    private var networkObserver: NSObjectProtocol?
    private var rateObserver: NSObjectProtocol?
    private var interruptionObserver: NSObjectProtocol?
    private var ownPauseAt = Date.distantPast
    private var lastRateReason = "—"
    private var wasPlayingBeforeInterruption = false
    private var bufferedAhead: Double {
        guard let r = player.currentItem?.loadedTimeRanges.last?.timeRangeValue else { return 0 }
        return max(0, r.end.seconds - currentTime)
    }

    // MARK: Per-title settings

    func updateTitleSettings(_ change: (inout TitlePlayerSettings) -> Void) {
        var s = titleSettings
        change(&s)
        guard s != titleSettings else { return }
        titleSettings = s
        TitlePlayerSettings.save(s, for: title.id)
    }

    func setSubtitleDelay(_ d: Double) {
        updateTitleSettings { $0.subtitleDelay = (d * 10).rounded() / 10 }
    }

    private var audioDelayTask: Task<Void, Never>?

    /// Shifts the sound (MP4 streams); the item is rebuilt at the current position, debounced.
    func setAudioDelay(_ d: Double) {
        updateTitleSettings { $0.audioDelay = (d * 10).rounded() / 10 }
        audioDelayTask?.cancel()
        audioDelayTask = Task {
            try? await Task.sleep(nanoseconds: 700_000_000)
            guard !Task.isCancelled, let q = quality else { return }
            play(q, at: currentTime)
        }
    }

    // MARK: Loading

    func start() {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        SharePlayManager.shared.attach(self)
        WatchRoom.shared.attach(self)
        Task {
            // Resume exactly where the TV stopped.
            await GistSync.shared.pullFresh()
            await load()
        }
    }

    func load() async {
        let token = UUID()
        loadToken = token
        saveProgress(force: true)
        player.pause()
        player.replaceCurrentItem(with: nil)
        countdownTask?.cancel()
        nextCountdown = nil
        qualities = []
        quality = nil
        segments = []
        subtitleTracks = []
        subtitle = nil
        subtitleText = nil
        cues = []
        kodikURL = nil
        webKodikURL = nil
        error = nil
        currentTime = 0
        duration = 0
        autoSkipped = false
        endingSkipped = false
        markedWatched = false
        isResolving = true
        startedAt = Date()
        let ep = episode
        timedComments = []
        if DanmakuPrefs.enabled {
            Task { [weak self] in
                let list = await LibAPI.timecodedComments(episodeId: ep.id)
                guard let self, self.episode.id == ep.id else { return }
                self.timedComments = list
                if !list.isEmpty { DebugLog.log("player: \(list.count) timeline comments") }
            }
        }
        DebugLog.log("player: load ep \(ep.number) team \(team.team.name) native=\(team.native)")
        // Downloaded episode: play the file, no network needed.
        if let file = DownloadManager.shared.localFile(episodeId: ep.id, teamId: team.id)
            ?? (team.native ? nil : DownloadManager.shared.localFile(episodeId: ep.id, teamId: nil)) {
            hrefs = [:]
            servers = []
            let q = StreamQuality(label: "Скачано", url: file)
            qualities = [q]
            play(q, at: library?.resumePosition(episodeId: ep.id))
            isResolving = false
            DebugLog.log("player: playing downloaded file")
            updateNowPlaying()
            Task { await loadSubtitlesIfAny(episodeId: ep.id) }
            return
        }
        do {
            let players = try await LibAPI.players(episodeId: ep.id)
            guard token == loadToken else { return }
            let kodik = players.first { $0.team.id == team.id && $0.isKodik } ?? players.first { $0.isKodik }
            webKodikURL = kodik?.kodikSrc.flatMap(LibAPI.kodikURL)
            if team.native, let p = players.first(where: { $0.team.id == team.id && $0.isNative }) {
                hrefs = p.hrefs
                playerId = p.playerId
                segments = p.segments
                setSubtitleTracks(p.subtitles)
                if !p.segments.isEmpty {
                    DebugLog.log("player: segments " + p.segments.map { "\($0.kind) \(Int($0.from))-\(Int($0.to))" }.joined(separator: ", "))
                }
                let probe = p.hrefs["720p"] ?? p.hrefs.values.first ?? ""
                servers = await LibAPI.rankServers(await LibAPI.videoServers(), href: probe)
                guard token == loadToken else { return }
                serverIndex = 0
                rebuildQualities()
                let preferred = initialQuality()
                // Local position first, then the one saved on the AnimeLib account.
                var resume = HandoffSeek.take(episodeId: ep.id) ?? library?.resumePosition(episodeId: ep.id)
                if resume == nil, let server = p.serverTimecode, server > 15 { resume = server }
                if let preferred { play(preferred, at: resume) }
                DebugLog.log(String(format: "player: resolved in %.2fs", Date().timeIntervalSince(startedAt)))
                if hasNext { LibAPI.warmUp(episodeId: episodes[index + 1].id, teamId: team.id) }
            } else if let p = players.first(where: { $0.team.id == team.id && $0.isKodik }),
                      let src = p.kodikSrc, let url = LibAPI.kodikURL(src) {
                kodikURL = url
                // No position from a web player: still record the episode in history.
                library?.touch(title: title, episode: ep, team: team.team)
            } else if let alt = fallbackTeam(in: players) {
                // This dub has no video for this episode: switch to another one instead of failing.
                DebugLog.log("player: \(team.team.name) missing for ep \(ep.number), switching to \(alt.team.name)")
                notice = "У «\(team.team.name)» нет этой серии — включена «\(alt.team.name)»"
                team = alt
                isResolving = false
                await load()
                return
            } else {
                throw LibError.noVideo
            }
        } catch {
            guard token == loadToken else { return }
            self.error = error.localizedDescription
            DebugLog.log("player: load failed \(error)")
        }
        isResolving = false
        updateNowPlaying()
    }

    /// Another team that does have this episode: favourites first, ad-free player first.
    private func fallbackTeam(in players: [LibPlayer]) -> TeamOption? {
        var options: [TeamOption] = []
        for p in players where p.team.id != team.id {
            if let i = options.firstIndex(where: { $0.team.id == p.team.id }) {
                options[i].native = options[i].native || p.isNative
                options[i].kodik = options[i].kodik || p.isKodik
            } else {
                options.append(TeamOption(team: p.team, native: p.isNative, kodik: p.isKodik))
            }
        }
        let sameKind = options.filter { $0.team.isSubtitles == team.team.isSubtitles }
        return DubPreferences.pick(sameKind) ?? sameKind.first { $0.native } ?? DubPreferences.pick(options)
            ?? options.first { $0.native } ?? options.first
    }

    private func rebuildQualities() {
        guard serverIndex < servers.count else { qualities = []; return }
        let server = servers[serverIndex]
        qualities = hrefs.compactMap { label, href in
            LibAPI.videoURL(server: server, href: href).map { StreamQuality(label: label, url: $0) }
        }
        .sorted { $0.height > $1.height }
    }

    /// Saved height for this title, the global default, or (auto) a guess from the connection.
    private func initialQuality() -> StreamQuality? {
        let wanted = titleSettings.qualityHeight ?? PlayerPrefs.preferredHeight
        if wanted == 0 {
            let guess = NetworkMonitor.shared.isExpensive ? 480 : NetworkMonitor.shared.isWiFi ? 1080 : 720
            let byBitrate = lastObservedBitrate.map { bps in
                qualities.first { QualityBudget.need($0.height) * 1.3 <= bps }?.height
            } ?? nil
            let target = byBitrate ?? guess
            return qualities.first { $0.height <= target } ?? qualities.last
        }
        return qualities.first { $0.height <= wanted } ?? qualities.last
    }

    // MARK: Automatic quality

    private var stalls: [Date] = []
    private var lastQualityChange = Date.distantPast
    private var lastObservedBitrate: Double?
    private var bufferingSince: Date?

    private func noteStall() {
        guard autoQuality, isNative, currentTime > 2 else { return }
        stalls = stalls.filter { Date().timeIntervalSince($0) < 60 } + [Date()]
        guard stalls.count >= 2, Date().timeIntervalSince(lastQualityChange) > 20,
              let q = quality, let i = qualities.firstIndex(of: q), i + 1 < qualities.count else { return }
        stalls = []
        lastQualityChange = Date()
        DebugLog.log("player: auto quality down \(q.label) -> \(qualities[i + 1].label)")
        play(qualities[i + 1], at: currentTime)
    }

    /// Every half a minute without stalls: step up if the measured speed comfortably allows it.
    private func considerUpgrade() {
        guard autoQuality, isNative, isPlaying, Date().timeIntervalSince(lastQualityChange) > 45,
              stalls.filter({ Date().timeIntervalSince($0) < 90 }).isEmpty,
              let bps = player.currentItem?.accessLog()?.events.last?.observedBitrate, bps > 0,
              let q = quality, let i = qualities.firstIndex(of: q), i > 0 else { return }
        lastObservedBitrate = bps
        let up = qualities[i - 1]
        guard bps > QualityBudget.need(up.height) * 1.8 else { return }
        lastQualityChange = Date()
        DebugLog.log("player: auto quality up \(q.label) -> \(up.label) (\(Int(bps / 1000)) kbps)")
        play(up, at: currentTime)
    }

    func select(autoQuality on: Bool) {
        autoQuality = on
        updateTitleSettings { $0.qualityHeight = on ? 0 : quality?.height }
        lastQualityChange = .distantPast
    }

    // MARK: Recovery

    /// Network is back: if playback broke, reload the same episode at the same moment.
    private func networkCameBack() {
        guard isNative, error != nil || (isBuffering && (bufferingSince.map { Date().timeIntervalSince($0) > 5 } ?? false)) else { return }
        recover(reason: "network back")
    }

    private func recover(reason: String) {
        guard !recovering else { return }
        recovering = true
        let at = max(currentTime, library?.resumePosition(episodeId: episode.id) ?? 0)
        DebugLog.log("player: recovering (\(reason)) at \(Int(at))s")
        Task {
            error = nil
            if serverIndex + 1 < servers.count {
                serverIndex += 1
                let label = quality?.label
                rebuildQualities()
                if let q = qualities.first(where: { $0.label == label }) ?? qualities.first { play(q, at: at) }
            } else {
                await load()
                if at > 2 { seek(to: at) }
            }
            recovering = false
        }
    }

    private func play(_ q: StreamQuality, at time: Double?) {
        quality = q
        let asset = AVURLAsset(url: q.url, options: ["AVURLAssetHTTPHeaderFieldsKey": ["Referer": LibAPI.site + "/"]])
        if titleSettings.audioDelay != 0 {
            Task {
                let shifted = await AudioOffset.item(for: asset, delay: titleSettings.audioDelay)
                guard quality == q else { return }
                attach(shifted ?? AVPlayerItem(asset: asset), at: time)
                if shifted == nil { DebugLog.log("player: audio delay unavailable for this stream") }
            }
            return
        }
        attach(AVPlayerItem(asset: asset), at: time)
    }

    private func attach(_ item: AVPlayerItem, at time: Double?) {
        if VideoEnhancer.enabled && !audioOnly { Task { await VideoEnhancer.apply(to: item) } }
        item.preferredForwardBufferDuration = 0
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item,
                                                             queue: .main) { [weak self] _ in
            Task { @MainActor in self?.didFinish() }
        }
        if let failObserver { NotificationCenter.default.removeObserver(failObserver) }
        failObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item,
                                                              queue: .main) { [weak self] n in
            let e = (n.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error)?.localizedDescription
            Task { @MainActor in
                DebugLog.log("player: failed mid-play \(e ?? "?")")
                self?.recover(reason: "failed to play to end")
            }
        }
        itemObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            let st = item.status
            let d = item.duration.seconds
            let e = item.error?.localizedDescription
            Task { @MainActor in self?.itemStatus(st, duration: d, error: e) }
        }
        let output = AVPlayerItemVideoOutput(pixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ])
        item.add(output)
        videoOutput = output
        SharePlayManager.shared.itemWillChange(self)
        player.replaceCurrentItem(with: item)
        if let time, time > 1 {
            player.seek(to: CMTime(seconds: time, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
            currentTime = time
        }
        player.playImmediately(atRate: rate)
    }

    private func itemStatus(_ st: AVPlayerItem.Status, duration d: Double, error e: String?) {
        switch st {
        case .readyToPlay:
            if d.isFinite { duration = d }
            DebugLog.log(String(format: "player: ready after %.2fs", Date().timeIntervalSince(startedAt)))
        case .failed:
            DebugLog.log("player: item failed \(e ?? "?") on \(servers[safe: serverIndex] ?? "?")")
            if serverIndex + 1 < servers.count {
                serverIndex += 1
                let label = quality?.label
                rebuildQualities()
                if let q = qualities.first(where: { $0.label == label }) ?? qualities.first {
                    play(q, at: currentTime > 1 ? currentTime : nil)
                    return
                }
            }
            error = e ?? "Не удалось воспроизвести видео"
        default: break
        }
    }

    // MARK: Subtitles

    private func setSubtitleTracks(_ tracks: [LibSubtitle]) {
        subtitleTracks = tracks
        guard !tracks.isEmpty else { return }
        DebugLog.log("player: subtitles " + tracks.map { "\($0.label) [\($0.format)]" }.joined(separator: ", "))
        // Subtitle teams: show their subtitles by default; dubs: only if the user turned them on.
        if titleSettings.subtitlesOff == true { return }
        if let label = titleSettings.subtitleLabel, let t = tracks.first(where: { $0.label == label }) {
            select(subtitle: t, remember: false)
        } else if team.team.isSubtitles || UserDefaults.standard.bool(forKey: "subs.alwaysOn") || titleSettings.subtitlesOff == false {
            select(subtitle: tracks[0], remember: false)
        }
    }

    func loadSubtitlesIfAny(episodeId: Int) async {
        guard let players = try? await LibAPI.players(episodeId: episodeId),
              let p = players.first(where: { $0.team.id == team.id }) else { return }
        setSubtitleTracks(p.subtitles)
    }

    // MARK: Translation / listen mode / enhance

    func cueTexts() -> [String] { cues.map(\.text) }

    func applyTranslation(_ texts: [String]) {
        guard texts.count == cues.count else { return }
        cues = zip(cues, texts).map { SubtitleCue(start: $0.start, end: $0.end, text: $1) }
        subtitleText = nil
    }

    var canTranslate: Bool { !cues.isEmpty && TranslationSupport.available }

    func setAudioOnly(_ on: Bool) {
        guard on != audioOnly, isNative else { return }
        audioOnly = on
        if on {
            qualityBeforeAudio = quality
            if let lowest = qualities.last, lowest != quality { play(lowest, at: currentTime) }
        } else if let q = qualityBeforeAudio, q != quality {
            play(q, at: currentTime)
        }
        updateNowPlaying()
    }

    func setEnhance(_ on: Bool) {
        VideoEnhancer.enabled = on
        if let q = quality { play(q, at: currentTime) }
    }

    /// A subtitle file picked by the user (SRT / ASS / VTT), for the current episode.
    func addExternalSubtitle(from url: URL) {
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "-" + url.lastPathComponent)
        try? FileManager.default.copyItem(at: url, to: tmp)
        let sub = LibSubtitle(url: tmp, format: url.pathExtension.lowercased(), label: "Файл: " + url.deletingPathExtension().lastPathComponent)
        subtitleTracks.append(sub)
        select(subtitle: sub, remember: false)
    }

    func select(subtitle s: LibSubtitle?, remember: Bool = true) {
        if remember {
            updateTitleSettings {
                $0.subtitlesOff = s == nil
                $0.subtitleLabel = s.flatMap { $0.label.hasPrefix("Файл: ") ? nil : $0.label }
            }
        }
        subtitle = s
        cues = []
        subtitleText = nil
        guard let s else { return }
        Task {
            do {
                let parsed = try await SubtitleParser.load(s)
                guard subtitle == s else { return }
                cues = parsed
                DebugLog.log("player: \(parsed.count) subtitle cues from \(s.label)")
            } catch {
                DebugLog.log("player: subtitles failed \(error)")
            }
        }
    }

    // MARK: Transport

    func togglePlay() {
        if player.timeControlStatus == .paused {
            if duration > 0, currentTime >= duration - 0.5 { seek(to: 0) }
            player.playImmediately(atRate: rate)
            isPlaying = true
        } else {
            ownPauseAt = Date()
            player.pause()
            isPlaying = false
        }
        WatchRoom.shared.localAction(self)
    }

    func seek(to s: Double) {
        let target = max(0, duration > 0 ? min(duration - 0.5, s) : s)
        currentTime = target
        player.seek(to: CMTime(seconds: target, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        WatchRoom.shared.localAction(self)
    }

    func seek(by delta: Double) { seek(to: currentTime + delta) }

    func skipOpening() {
        autoSkipped = true
        if let op = opening { seek(to: op.to) } else { seek(by: PlayerPrefs.openingLength) }
    }

    func select(quality q: StreamQuality) {
        autoQuality = false
        updateTitleSettings { $0.qualityHeight = q.height }
        guard q != quality else { return }
        play(q, at: currentTime)
    }

    func select(team t: TeamOption) {
        guard t != team else { return }
        let resume = currentTime
        team = t
        updateTitleSettings { $0.teamId = t.id }
        TitleModel.preferredTeamName = t.team.name
        DubPreferences.use(t.team.name)
        SharePlayManager.shared.localChanged(self)
        Task {
            await load()
            if resume > 5, t.native { seek(to: resume) }
            WatchRoom.shared.localAction(self)
        }
    }

    func go(to i: Int) {
        guard episodes.indices.contains(i), i != index else { return }
        // Web player: moving forward means the previous episode was watched.
        if !isNative, i > index {
            library?.markWatched(title: title, episode: episode, totalEpisodes: episodes.count)
        }
        index = i
        SharePlayManager.shared.localChanged(self)
        Task {
            await load()
            WatchRoom.shared.localAction(self)
        }
    }

    /// Another SharePlay participant moved to a different episode or dub.
    func apply(sharedEpisodeId: Int, teamId: Int) {
        if teamId != team.id, let t = teams.first(where: { $0.id == teamId }) {
            team = t
            if let i = episodes.firstIndex(where: { $0.id == sharedEpisodeId }) { index = i }
            Task { await load() }
        } else if let i = episodes.firstIndex(where: { $0.id == sharedEpisodeId }), i != index {
            index = i
            Task { await load() }
        }
    }

    /// Current video frame as an image (AnimeLib player only; Kodik runs in a web view).
    func snapshot() -> UIImage? {
        guard isNative, let output = videoOutput, let item = player.currentItem else { return nil }
        let time = item.currentTime()
        guard let buffer = output.copyPixelBuffer(forItemTime: time, itemTimeForDisplay: nil) else { return nil }
        let ci = CIImage(cvPixelBuffer: buffer)
        guard let cg = CIContext().createCGImage(ci, from: ci.extent) else { return nil }
        return UIImage(cgImage: cg)
    }

    func next() { go(to: index + 1) }
    func previous() { go(to: index - 1) }

    func cancelCountdown() {
        countdownTask?.cancel()
        nextCountdown = nil
    }

    func stop() {
        ownPauseAt = Date()
        [rateObserver, interruptionObserver].compactMap { $0 }.forEach(NotificationCenter.default.removeObserver)
        SharePlayManager.shared.detach(self)
        WatchRoom.shared.detach(self)
        if let failObserver { NotificationCenter.default.removeObserver(failObserver) }
        saveProgress(force: true)
        countdownTask?.cancel()
        player.pause()
        player.replaceCurrentItem(with: nil)
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        GistSync.shared.flush()
    }

    // MARK: Observers

    private func statusChanged(_ st: AVPlayer.TimeControlStatus) {
        // Paused without anyone asking: note why, so a "random pause" can be traced in the debug log.
        if st == .paused, isPlaying || isBuffering, Date().timeIntervalSince(ownPauseAt) > 1.5,
           !(duration > 0 && currentTime >= duration - 1) {
            DebugLog.log("player: paused by itself at \(Int(currentTime))s (reason: \(lastRateReason), buffered \(Int(bufferedAhead))s)")
        }
        isPlaying = st == .playing
        let wasBuffering = isBuffering
        isBuffering = st == .waitingToPlayAtSpecifiedRate
        if isBuffering && !wasBuffering {
            bufferingSince = Date()
            noteStall()
        } else if !isBuffering {
            bufferingSince = nil
        }
        if st == .playing, Date().timeIntervalSince(startedAt) < 120, currentTime < 2 {
            DebugLog.log(String(format: "player: PLAYING after %.2fs", Date().timeIntervalSince(startedAt)))
        }
        updateNowPlaying()
    }

    private var lastUpgradeCheck = Date()

    private func tick(_ s: Double) {
        guard s.isFinite else { return }
        if !isScrubbing { currentTime = s }
        // Stuck buffering for a long time: try another mirror at the same moment.
        if isNative, let since = bufferingSince, Date().timeIntervalSince(since) > 20, currentTime > 1, !recovering {
            bufferingSince = Date()
            recover(reason: "buffering 20s")
        }
        if Date().timeIntervalSince(lastUpgradeCheck) > 30 {
            lastUpgradeCheck = Date()
            considerUpgrade()
        }
        if let at = sleepAt, Date() >= at {
            sleepAt = nil
            ownPauseAt = Date()
            player.pause()
            DebugLog.log("player: sleep timer paused playback")
        }
        if !cues.isEmpty {
            let text = SubtitleParser.cue(at: s - titleSettings.subtitleDelay, in: cues)
            if text != subtitleText { subtitleText = text }
        }
        if duration <= 0, let d = player.currentItem?.duration.seconds, d.isFinite, d > 0 { duration = d }
        if PlayerPrefs.autoSkipOpening, !autoSkipped, isNative {
            if let op = opening {
                if s >= op.from, s < op.to - 1 { skipOpening() }
            } else if s > 2, s < 15, duration > 300 {
                skipOpening()
            }
        }
        // Marathon mode: straight to the next episode once the ending (and preview) starts.
        if PlayerPrefs.autoSkipEnding, isNative, hasNext, !endingSkipped, !sleepAfterEpisode, !isScrubbing,
           let ed = ending, s >= ed.from + 1, s < ed.to {
            endingSkipped = true
            if !markedWatched {
                markedWatched = true
                library?.markWatched(title: title, episode: episode, totalEpisodes: episodes.count)
                let (eid, pid) = (episode.id, playerId)
                Task { await LibAPI.markViewed(episodeId: eid, playerId: pid) }
            }
            DebugLog.log("player: ending skipped -> next")
            next()
            return
        }
        if duration > 0, s / duration > 0.9, !markedWatched {
            markedWatched = true
            library?.markWatched(title: title, episode: episode, totalEpisodes: episodes.count)
            let (eid, pid) = (episode.id, playerId)
            Task { await LibAPI.markViewed(episodeId: eid, playerId: pid) }
        }
        saveProgress(force: false)
    }

    private func didFinish() {
        saveProgress(force: true)
        if !markedWatched {
            markedWatched = true
            library?.markWatched(title: title, episode: episode, totalEpisodes: episodes.count)
        }
        if sleepAfterEpisode {
            sleepAfterEpisode = false
            return
        }
        guard hasNext, PlayerPrefs.autoNext else { return }
        countdownTask?.cancel()
        countdownTask = Task {
            for n in stride(from: 5, through: 1, by: -1) {
                nextCountdown = n
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if Task.isCancelled { return }
            }
            nextCountdown = nil
            next()
        }
    }

    private func saveProgress(force: Bool) {
        guard let library, duration > 0, currentTime > 1 else { return }
        guard force || Date().timeIntervalSince(lastSaved) > 5 else { return }
        lastSaved = Date()
        library.updateProgress(title: title, episode: episode, team: team.team, position: currentTime, duration: duration)
        if isNative, force || Date().timeIntervalSince(lastServerSave) > 30 {
            lastServerSave = Date()
            let (eid, pid, pos) = (episode.id, playerId, currentTime)
            Task { await LibAPI.saveTimecode(episodeId: eid, playerId: pid, seconds: pos) }
        }
    }

    // MARK: Lock screen / Control Center

    private func setupRemoteCommands() {
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget { [weak self] _ in Task { @MainActor in self?.player.play() }; return .success }
        c.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in
                DebugLog.log("player: pause from Now Playing / headphones")
                self?.ownPauseAt = Date(); self?.player.pause()
            }
            return .success
        }
        c.togglePlayPauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.togglePlay() }
            return .success
        }
        c.skipForwardCommand.preferredIntervals = [10]
        c.skipBackwardCommand.preferredIntervals = [10]
        c.skipForwardCommand.addTarget { [weak self] _ in Task { @MainActor in self?.seek(by: 10) }; return .success }
        c.skipBackwardCommand.addTarget { [weak self] _ in Task { @MainActor in self?.seek(by: -10) }; return .success }
        c.nextTrackCommand.addTarget { [weak self] _ in Task { @MainActor in self?.next() }; return .success }
        c.changePlaybackPositionCommand.addTarget { [weak self] e in
            guard let e = e as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            let pos = e.positionTime
            Task { @MainActor in self?.seek(to: pos) }
            return .success
        }
    }

    private func updateNowPlaying() {
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: "Серия \(episode.number)",
            MPMediaItemPropertyArtist: title.title,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? Double(rate) : 0,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: currentTime
        ]
        if duration > 0 { info[MPMediaItemPropertyPlaybackDuration] = duration }
        if let url = title.coverURL, let img = ImagePipeline.shared.cached(url) {
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: img.size) { _ in img }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    deinit {
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        let c = MPRemoteCommandCenter.shared()
        [c.playCommand, c.pauseCommand, c.togglePlayPauseCommand, c.skipForwardCommand, c.skipBackwardCommand,
         c.nextTrackCommand, c.changePlaybackPositionCommand].forEach { $0.removeTarget(nil) }
    }
}

extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}
