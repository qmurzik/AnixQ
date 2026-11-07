import SwiftUI
import AVKit
import UniformTypeIdentifiers

// MARK: - Version switch

enum PlayerVersion {
    static let key = "playerVersion"
    static var useNew: Bool { (UserDefaults.standard.string(forKey: key) ?? "new") != "old" }
    /// New player: start full screen (landscape) instead of the portrait layout.
    static var startFullscreen: Bool {
        get { UserDefaults.standard.bool(forKey: "player.startFullscreen") }
        set { UserDefaults.standard.set(newValue, forKey: "player.startFullscreen") }
    }
}

/// Picks the player chosen in Settings → Плеер.
struct AnyPlayerScreen: View {
    let title: Title
    let background: URL?
    let episodes: [LibEpisode]
    let teams: [TeamOption]
    let startIndex: Int
    let team: TeamOption

    var body: some View {
        if PlayerVersion.useNew {
            PlayerScreenV2(title: title, background: background, episodes: episodes, teams: teams, startIndex: startIndex, team: team)
        } else {
            PlayerScreen(title: title, background: background, episodes: episodes, teams: teams, startIndex: startIndex, team: team)
        }
    }
}

// MARK: - New player

/// Portrait: video on top, everything else below (like YouTube). Landscape: immersive, calm controls.
struct PlayerScreenV2: View {
    @StateObject private var c: PlayerController
    @EnvironmentObject private var library: LibraryStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    let background: URL?

    @State private var land = false
    /// Safe-area insets of the screen, used to keep landscape controls off the notch and home indicator.
    @State private var insets = EdgeInsets()
    @State private var showControls = true
    @State private var locked = false
    @State private var panel: PlayerPanel?
    @State private var hideTask: Task<Void, Never>?
    @State private var pip: AVPictureInPictureController?
    @State private var hud: (icon: String, text: String, value: Double?)?
    @State private var ripple: (left: Bool, id: UUID, count: Int)?
    @State private var dragMode: DragKind?
    @State private var dragStart: Double = 0
    @State private var seekPreview: Double?
    @State private var dismissOffset: CGFloat = 0
    @State private var boosted: Float?
    @State private var scrubValue: Double = 0
    @State private var webLoading = true
    @State private var webReload = 0
    @State private var frameShot: UIImage?
    @State private var frameSaved = false
    @State private var whoFrame: UIImage?
    @State private var importingSubtitle = false
    @State private var confirmLeaveSharePlay = false
    @ObservedObject private var sharePlay = SharePlayManager.shared
    @ObservedObject private var room = WatchRoom.shared
    @AppStorage("player.danmaku") private var danmaku = true
    @AppStorage("player.enhance") private var enhance = false
    @AppStorage("subs.size") private var subtitleSize: Double = 20
    @AppStorage("subs.background") private var subtitleBackground = true
    @AppStorage("subs.color") private var subtitleColor = "white"
    @AppStorage("subs.outline") private var subtitleOutline = true
    @AppStorage("subs.lift") private var subtitleLift: Double = 0
    @AppStorage("subs.font") private var subtitleFont = "system"
    @AppStorage("subs.bold") private var subtitleBold = true

    enum DragKind { case brightness, volume, seek, dismiss, ignored }

    private let bg = Color(red: 0.05, green: 0.05, blue: 0.07)

    init(title: Title, background: URL?, episodes: [LibEpisode], teams: [TeamOption], startIndex: Int, team: TeamOption) {
        _c = StateObject(wrappedValue: PlayerController(title: title, episodes: episodes, teams: teams, index: startIndex, team: team))
        self.background = background
    }

    var body: some View {
        GeometryReader { geo in
            let isLand = geo.size.width > geo.size.height
            ZStack(alignment: .top) {
                (isLand ? Color.black : bg).ignoresSafeArea()
                VStack(spacing: 0) {
                    videoArea(isLand: isLand)
                        .frame(width: geo.size.width, height: isLand ? geo.size.height : geo.size.width * 9 / 16)
                        .offset(y: dismissOffset)
                    if !isLand {
                        details
                            .opacity(1 - Double(min(1, dismissOffset / 300)))
                    }
                }
                if isLand, let p = panel { drawer(p, width: geo.size.width) }
                if let shot = frameShot { framePreview(shot) }
                noticeBanner
                VolumeSliderHost().frame(width: 1, height: 1).opacity(0.01)
                shortcuts
            }
            .onAppear { land = isLand; insets = geo.safeAreaInsets }
            .onChange(of: isLand) { v in
                guard scenePhase == .active else { return }
                land = v
                if !v, panel != nil { panel = nil }
            }
            .onChange(of: geo.safeAreaInsets) { insets = $0 }
            .onChange(of: scenePhase) { phase in
                // Swiping Home can cancel a drag without calling onEnded.
                hideTask?.cancel()
                resetTransientInteraction()
                if phase == .active {
                    land = isLand
                    insets = geo.safeAreaInsets
                    if !isLand { panel = nil }
                    showControls = true
                    scheduleHide()
                }
            }
        }
        // Landscape: the picture uses the whole screen, including the home-indicator strip and the
        // notch sides; only the controls keep clear of them (see `insets`).
        .ignoresSafeArea(edges: land ? .all : [])
        .statusBarHidden(land || !showControls)
        .persistentSystemOverlays(.hidden)
        .preferredColorScheme(.dark)
        .animation(.easeInOut(duration: 0.2), value: showControls)
        .animation(.spring(response: 0.32, dampingFraction: 0.86), value: panel)
        .onAppear {
            c.library = library
            c.start()
            if PlayerVersion.startFullscreen { OrientationController.set(.landscape) }
            scheduleHide()
        }
        .onDisappear {
            if let pip, pip.isPictureInPictureActive { PiPKeeper.shared.keep(c, pip: pip) } else { c.stop() }
            OrientationController.set(.portrait)
        }
        .onChange(of: c.isPlaying) { playing in if playing && scenePhase == .active { scheduleHide() } }
        .modifier(SubtitleTranslation(c: c))
        .userActivity(HandoffSeek.activityType, isActive: c.isNative && c.duration > 0) { a in
            a.title = "\(c.title.title) — серия \(c.episode.number)"
            a.isEligibleForHandoff = true
            a.userInfo = ["slug": c.title.slugURL, "titleName": c.title.title, "episodeId": c.episode.id,
                          "episodeNumber": c.episode.number, "teamId": c.team.id, "position": c.currentTime]
        }
        // Portrait: settings slide up as a sheet.
        .sheet(item: Binding(get: { land ? nil : panel }, set: { panel = $0 })) { p in
            PanelSheet(title: panelTitle(p)) { panelContent(p) }
                .presentationDetents(p == .episodes || p == .subtitles ? [.medium, .large] : [.medium])
                .environmentObject(library)
        }
        .sheet(item: Binding(get: { whoFrame.map(IdentifiedImage.init) }, set: { whoFrame = $0?.image })) { WhoIsThisView(frame: $0.image) }
        .fileImporter(isPresented: $importingSubtitle,
                      allowedContentTypes: [UTType(filenameExtension: "srt") ?? .plainText, UTType(filenameExtension: "ass") ?? .plainText,
                                            UTType(filenameExtension: "ssa") ?? .plainText, UTType(filenameExtension: "vtt") ?? .plainText,
                                            .plainText, .data]) { result in
            guard case .success(let url) = result else { return }
            let access = url.startAccessingSecurityScopedResource()
            c.addExternalSubtitle(from: url)
            if access { url.stopAccessingSecurityScopedResource() }
        }
        .confirmationDialog("Смотреть вместе", isPresented: $confirmLeaveSharePlay, titleVisibility: .visible) {
            Button("Выйти из SharePlay", role: .destructive) { sharePlay.leave() }
        }
        .alert("SharePlay", isPresented: Binding(get: { sharePlay.message != nil }, set: { if !$0 { sharePlay.message = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(sharePlay.message ?? "") }
    }

    // MARK: Video area

    private func videoArea(isLand: Bool) -> some View {
        GeometryReader { g in
            ZStack {
                Color.black
                videoLayers(isLand: isLand)
                if c.isNative {
                    gestureLayer(size: g.size, isLand: isLand)
                    if !locked && (showControls || !c.isPlaying) && dragMode == nil {
                        controlsOverlay(isLand: isLand).transition(.opacity)
                    }
                    hudLayer
                    if locked { lockOverlay }
                } else {
                    webChrome(isLand: isLand)
                }
                if let n = c.nextCountdown, c.hasNext { nextCard(n, isLand: isLand) }
                if isLand && room.isActive && !locked { RoomOverlay() }
            }
            .clipped()
        }
    }

    @ViewBuilder
    private func videoLayers(isLand: Bool) -> some View {
        if c.audioOnly {
            ListenModeView(c: c, background: background)
        } else {
            PlayerSurface(player: c.player, fill: c.fillScreen && isLand, pip: $pip)
                .opacity(c.isNative ? 1 : 0)
            if danmaku && c.isNative && !c.timedComments.isEmpty && isLand {
                DanmakuLayer(comments: c.timedComments, time: c.currentTime)
            }
        }
        if !c.isNative, let url = c.kodikURL {
            KodikWebView(url: url, isLoading: $webLoading).id("\(url.absoluteString)#\(webReload)")
        }
        if c.isLoading || (!c.isNative && c.kodikURL != nil && webLoading) {
            VStack(spacing: 12) {
                ProgressView().tint(.white).scaleEffect(1.3)
                Text(c.isNative ? "Серия \(c.episode.number)" : "Загружаем Kodik…")
                    .font(.footnote.weight(.medium)).foregroundStyle(.white.opacity(0.7))
            }
            .allowsHitTesting(false)
        } else if c.isNative && c.isBuffering {
            // Mid-episode loading: the video is waiting for data, not paused — show it, so "play" isn't pressed by mistake.
            VStack(spacing: 10) {
                ProgressView().tint(.white).scaleEffect(1.2)
                Text("Подгружаем…").font(.caption.weight(.medium)).foregroundStyle(.white.opacity(0.75))
            }
            .padding(16).background(.black.opacity(0.35), in: RoundedRectangle(cornerRadius: 14))
            .allowsHitTesting(false)
        }
        if let text = c.subtitleText {
            VStack {
                Spacer()
                Text(text)
                    .font(.system(size: isLand ? subtitleSize : subtitleSize * 0.72, weight: subtitleBold ? .semibold : .regular,
                                  design: subtitleFont == "rounded" ? .rounded : subtitleFont == "serif" ? .serif : .default))
                    .multilineTextAlignment(.center)
                    .foregroundStyle(PlayerScreen.subtitleColors[subtitleColor] ?? .white)
                    .modifier(SubtitleOutline(on: subtitleOutline))
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(Color.black.opacity(subtitleBackground ? 0.45 : 0), in: RoundedRectangle(cornerRadius: 6))
                    .padding(.horizontal, isLand ? 48 : 12)
                    .padding(.bottom, (showControls ? (isLand ? 120 : 52) : (isLand ? 30 : 10)) + (isLand ? subtitleLift : 0))
            }
            .allowsHitTesting(false)
            .animation(.easeInOut(duration: 0.2), value: showControls)
        }
        if let e = c.error {
            VStack(spacing: 10) {
                Image(systemName: "exclamationmark.triangle.fill").font(.title).foregroundStyle(.yellow)
                Text(e).font(.subheadline).multilineTextAlignment(.center).foregroundStyle(.white.opacity(0.85))
                HStack {
                    Button("Повторить") { Task { await c.load() } }
                    Button("Другая озвучка") { panel = .teams }
                }
                .buttonStyle(.bordered).tint(.white)
            }
            .padding(20)
        }
    }

    // MARK: Gestures

    private func gestureLayer(size: CGSize, isLand: Bool) -> some View {
        HStack(spacing: 0) {
            tapZone(left: true)
            tapZone(left: false)
        }
        .contentShape(Rectangle())
        .gesture(locked ? nil : dragGesture(size: size, isLand: isLand))
        .simultaneousGesture(locked ? nil : LongPressGesture(minimumDuration: 0.45).onEnded { _ in
            guard c.isPlaying, boosted == nil else { return }
            boosted = c.rate
            c.rate = 2
            Haptics.tap()
            hud = ("forward.fill", "2× — отпустите, чтобы вернуть", nil)
        })
        .simultaneousGesture(DragGesture(minimumDistance: 0).onEnded { _ in
            if let r = boosted {
                c.rate = r
                boosted = nil
                withAnimation { hud = nil }
            }
        })
        .simultaneousGesture(locked || !isLand ? nil : MagnificationGesture().onEnded { scale in
            withAnimation { c.fillScreen = scale > 1 }
            flash(c.fillScreen ? "arrow.up.left.and.arrow.down.right" : "arrow.down.right.and.arrow.up.left",
                  c.fillScreen ? "Заполнить экран" : "Вписать")
        })
    }

    private func tapZone(left: Bool) -> some View {
        Color.clear
            .contentShape(Rectangle())
            .onTapGesture(count: 2) {
                guard !locked else { return }
                c.seek(by: Double(left ? -PlayerPrefs.doubleTapStep : PlayerPrefs.doubleTapStep))
                let count = (ripple?.left == left ? (ripple?.count ?? 0) : 0) + 1
                ripple = (left, UUID(), count)
            }
            .onTapGesture {
                if dragMode != nil {
                    resetTransientInteraction()
                    showControls = true
                    scheduleHide()
                    return
                }
                if locked { showControls.toggle(); scheduleHide(); return }
                if panel != nil { panel = nil; return }
                showControls.toggle()
                if showControls { scheduleHide() }
            }
    }

    private func dragGesture(size: CGSize, isLand: Bool) -> some Gesture {
        DragGesture(minimumDistance: 14)
            .onChanged { v in
                if dragMode == nil {
                    // Swipes that start at the screen edges belong to iOS (home bar, Control Center,
                    // notifications): never turn them into brightness / volume / seek.
                    let edge = max(48, size.height * 0.12)
                    if isLand && (v.startLocation.y < edge || v.startLocation.y > size.height - edge
                                  || v.startLocation.x < 24 || v.startLocation.x > size.width - 24) {
                        dragMode = .ignored
                    // Wait for a clear direction so a short tap-drag doesn't jump the volume.
                    } else if max(abs(v.translation.width), abs(v.translation.height)) < 22 {
                        return
                    } else if abs(v.translation.width) > abs(v.translation.height) {
                        dragMode = PlayerPrefs.gestureSeek ? .seek : .ignored
                        dragStart = c.currentTime
                    } else if !isLand {
                        // Portrait: pull the video down to close.
                        dragMode = v.translation.height > 0 ? .dismiss : .ignored
                    } else {
                        let left = v.startLocation.x < size.width / 2
                        dragMode = left ? (PlayerPrefs.gestureBrightness ? .brightness : .ignored)
                                        : (PlayerPrefs.gestureVolume ? .volume : .ignored)
                        dragStart = dragMode == .brightness ? Double(UIScreen.main.brightness)
                                                            : Double(AVAudioSession.sharedInstance().outputVolume)
                    }
                }
                switch dragMode {
                case .seek:
                    let perPoint = max(60, c.duration) / Double(size.width) * 0.5
                    let target = max(0, min(c.duration, dragStart + Double(v.translation.width) * perPoint))
                    seekPreview = target
                    let delta = Int(target - dragStart)
                    hud = (delta >= 0 ? "goforward" : "gobackward", "\(fmt(target))  \(delta >= 0 ? "+" : "−")\(abs(delta)) с", target / max(1, c.duration))
                case .brightness, .volume:
                    let value = max(0, min(1, dragStart - Double(v.translation.height) / Double(size.height * 0.75)))
                    if dragMode == .brightness {
                        UIScreen.main.brightness = CGFloat(value)
                        hud = ("sun.max.fill", "Яркость", value)
                    } else {
                        VolumeSliderHost.set(Float(value))
                        hud = (value == 0 ? "speaker.slash.fill" : "speaker.wave.2.fill", "Громкость", value)
                    }
                case .dismiss:
                    dismissOffset = max(0, v.translation.height)
                case .ignored, nil: break
                }
            }
            .onEnded { v in
                if dragMode == .seek, let t = seekPreview { c.seek(to: t) }
                if dragMode == .dismiss {
                    if v.translation.height > 140 || v.predictedEndTranslation.height > 320 { dismiss() }
                    else { withAnimation(.spring()) { dismissOffset = 0 } }
                }
                dragMode = nil
                seekPreview = nil
                withAnimation(.easeOut(duration: 0.3).delay(0.35)) { if boosted == nil { hud = nil } }
            }
    }

    private func flash(_ icon: String, _ text: String) {
        hud = (icon, text, nil)
        Task {
            try? await Task.sleep(nanoseconds: 900_000_000)
            withAnimation { if boosted == nil { hud = nil } }
        }
    }

    // MARK: Controls over the video

    private func controlsOverlay(isLand: Bool) -> some View {
        ZStack {
            LinearGradient(stops: [.init(color: .black.opacity(0.65), location: 0), .init(color: .black.opacity(0.15), location: 0.3),
                                   .init(color: .black.opacity(0.15), location: 0.6), .init(color: .black.opacity(0.75), location: 1)],
                           startPoint: .top, endPoint: .bottom)
                .allowsHitTesting(false)
            VStack(spacing: 0) {
                topBar(isLand: isLand)
                Spacer(minLength: 0)
                bottomBar(isLand: isLand)
            }
            .padding(.leading, isLand ? max(24, insets.leading + 8) : 10)
            .padding(.trailing, isLand ? max(24, insets.trailing + 8) : 10)
            .padding(.top, isLand ? max(14, insets.top) : 6)
            .padding(.bottom, isLand ? max(14, insets.bottom + 4) : 4)
            if !c.isLoading && !(c.isNative && c.isBuffering) { centerButtons(isLand: isLand) }
        }
        .foregroundStyle(.white)
    }

    private func centerButtons(isLand: Bool) -> some View {
        HStack(spacing: isLand ? 64 : 40) {
            roundButton(c.hasPrevious && c.currentTime < 5 ? "backward.end.fill" : "gobackward.10", size: isLand ? 24 : 18, bg: isLand ? 58 : 42) {
                c.hasPrevious && c.currentTime < 5 ? c.previous() : c.seek(by: -10)
            }
            roundButton(c.isPlaying || c.isBuffering ? "pause.fill" : "play.fill", size: isLand ? 34 : 26, bg: isLand ? 78 : 58) { c.togglePlay() }
            roundButton(c.nearEnd && c.hasNext ? "forward.end.fill" : "goforward.10", size: isLand ? 24 : 18, bg: isLand ? 58 : 42) {
                c.nearEnd && c.hasNext ? c.next() : c.seek(by: 10)
            }
        }
    }

    private func topBar(isLand: Bool) -> some View {
        HStack(spacing: isLand ? 6 : 2) {
            icon(isLand ? "chevron.down" : "chevron.down", 17) {
                if isLand { OrientationController.set(.portrait) } else { dismiss() }
            }
            if isLand {
                VStack(alignment: .leading, spacing: 1) {
                    Text(c.title.title).font(.headline).lineLimit(1)
                    Text("Серия \(c.episode.number) · \(c.team.team.name)").font(.caption).foregroundStyle(.white.opacity(0.7)).lineLimit(1)
                }
                .padding(.leading, 4)
            }
            Spacer(minLength: 0)
            if isLand && c.isNative { RoomMenu(c: c) }
            RoutePicker().frame(width: 34, height: 34)
            if let pip, AVPictureInPictureController.isPictureInPictureSupported() {
                icon("pip.enter", 16) { pip.startPictureInPicture() }
            }
            if isLand { icon("lock.open", 16) { locked = true; showControls = true; scheduleHide() } }
            moreMenu
        }
    }

    private var moreMenu: some View {
        Menu {
            if RemoteTV.shared.isAvailable {
                Button {
                    // Hand the episode over to the TV at this exact moment; the phone becomes the remote.
                    let at = c.currentTime
                    if c.isPlaying { c.togglePlay() }
                    RemoteTV.shared.play(title: c.title, episodeId: c.episode.id, teamId: c.team.id, position: at)
                    RemoteTV.shared.showRemote = false
                    dismiss()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { RemoteTV.shared.showRemote = true }
                } label: { Label("Продолжить на ТВ", systemImage: "tv") }
            }
            Section {
                Button { panel = .sleep } label: {
                    Label(c.sleepAt != nil || c.sleepAfterEpisode ? "Таймер сна включён" : "Таймер сна", systemImage: "moon.zzz")
                }
                Button { c.setAudioOnly(!c.audioOnly) } label: {
                    Label(c.audioOnly ? "Вернуть видео" : "Только звук", systemImage: c.audioOnly ? "play.rectangle" : "headphones")
                }
                Button { frameSaved = false; frameShot = c.snapshot() } label: { Label("Стоп-кадр", systemImage: "camera") }
                Button { whoFrame = c.snapshot() } label: { Label("Кто это?", systemImage: "person.crop.rectangle") }
            }
            Section {
                Toggle(isOn: $danmaku) {
                    Label("Комментарии на видео" + (c.timedComments.isEmpty ? "" : " (\(c.timedComments.count))"), systemImage: "text.bubble")
                }
                Toggle(isOn: Binding(get: { enhance }, set: { enhance = $0; c.setEnhance($0) })) {
                    Label("Улучшение чёткости", systemImage: "wand.and.stars")
                }
            }
            if c.isNative && (sharePlay.isEligible || sharePlay.isActive) {
                Button {
                    if sharePlay.isActive { confirmLeaveSharePlay = true } else { Task { await sharePlay.start(with: c) } }
                } label: { Label(sharePlay.isActive ? "SharePlay включён" : "SharePlay", systemImage: "shareplay") }
            }
        } label: {
            Image(systemName: "ellipsis").font(.system(size: 17, weight: .bold))
                .frame(width: 40, height: 40).contentShape(Rectangle())
        }
    }

    private func bottomBar(isLand: Bool) -> some View {
        VStack(spacing: isLand ? 10 : 4) {
            if isLand {
                HStack(spacing: 8) {
                    if c.canSkipOpening && (c.opening != nil || c.currentTime < PlayerPrefs.openingLength + 60) {
                        actionPill("Пропустить опенинг", "forward.fill") { c.skipOpening() }
                    }
                    Spacer()
                    if c.nearEnd && c.hasNext {
                        actionPill("Серия \(c.episodes[c.index + 1].number)", "forward.end.fill") { c.next() }
                    }
                }
            }
            HStack(spacing: 10) {
                if isLand {
                    Text(fmt(c.isScrubbing ? scrubValue : c.currentTime)).font(.caption.monospacedDigit().weight(.semibold))
                }
                ScrubberV2(value: Binding(get: { c.isScrubbing ? scrubValue : c.currentTime }, set: { scrubValue = $0 }),
                           duration: c.duration, buffered: buffered,
                           marks: danmaku ? c.timedComments.map(\.time) : [],
                           opening: c.opening.flatMap { $0.to > $0.from ? $0.from...$0.to : nil }, compact: !isLand) { editing in
                    if editing { scrubValue = c.currentTime; c.isScrubbing = true; hideTask?.cancel() }
                    else { c.seek(to: scrubValue); c.isScrubbing = false; scheduleHide() }
                }
                if isLand {
                    Text("−" + fmt(max(0, c.duration - (c.isScrubbing ? scrubValue : c.currentTime))))
                        .font(.caption.monospacedDigit().weight(.semibold)).foregroundStyle(.white.opacity(0.75))
                }
            }
            HStack(spacing: 8) {
                if !isLand {
                    Text("\(fmt(c.isScrubbing ? scrubValue : c.currentTime)) / \(fmt(c.duration))")
                        .font(.caption2.monospacedDigit().weight(.semibold)).foregroundStyle(.white.opacity(0.85))
                    Spacer()
                    icon("arrow.up.left.and.arrow.down.right", 15) { OrientationController.set(.landscape) }
                } else {
                    chip("Серии", "rectangle.stack") { panel = .episodes }
                    chip(c.team.team.name, "waveform") { panel = .teams }
                    chip(c.rate == 1 ? "1×" : String(format: "%g×", c.rate), "gauge.with.dots.needle.50percent") { panel = .speed }
                    if c.qualities.count > 1 {
                        chip(c.autoQuality ? "Авто · \(c.quality?.label ?? "")" : (c.quality?.label ?? "Авто"), "sparkles.tv") { panel = .quality }
                    }
                    chip(c.subtitle == nil ? "Субтитры" : "Субтитры ✓", "captions.bubble") { panel = .subtitles }
                    Spacer()
                    icon(c.fillScreen ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right", 15) {
                        withAnimation { c.fillScreen.toggle() }
                    }
                }
            }
        }
    }

    private var buffered: Double {
        guard let r = c.player.currentItem?.loadedTimeRanges.last?.timeRangeValue else { return 0 }
        return (r.start + r.duration).seconds
    }

    // MARK: HUD, ripple, lock, next card

    @ViewBuilder
    private var hudLayer: some View {
        if let h = hud {
            VStack(spacing: 8) {
                Image(systemName: h.icon).font(.title2)
                Text(h.text).font(.subheadline.monospacedDigit().weight(.semibold))
                if let v = h.value {
                    Capsule().fill(.white.opacity(0.25)).frame(width: 140, height: 5)
                        .overlay(alignment: .leading) { Capsule().fill(.white).frame(width: 140 * v, height: 5) }
                }
            }
            .padding(.horizontal, 20).padding(.vertical, 14)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .environment(\.colorScheme, .dark)
            .foregroundStyle(.white)
            .allowsHitTesting(false)
            .frame(maxHeight: .infinity, alignment: boosted != nil ? .top : .center)
            .padding(.top, boosted != nil ? 20 : 0)
        }
        if let r = ripple {
            GeometryReader { g in
                ZStack {
                    Circle().fill(.white.opacity(0.12))
                        .frame(width: g.size.height * 1.6, height: g.size.height * 1.6)
                        .offset(x: r.left ? -g.size.width * 0.42 : g.size.width * 0.42)
                    VStack(spacing: 4) {
                        Image(systemName: r.left ? "backward.fill" : "forward.fill").font(.title3)
                        Text("\(r.count * PlayerPrefs.doubleTapStep) с").font(.caption.weight(.bold))
                    }
                    .foregroundStyle(.white)
                    .offset(x: r.left ? -g.size.width * 0.3 : g.size.width * 0.3)
                }
                .frame(width: g.size.width, height: g.size.height)
                .clipped()
            }
            .allowsHitTesting(false)
            .transition(.opacity)
            .task(id: r.id) {
                try? await Task.sleep(nanoseconds: 700_000_000)
                withAnimation { ripple = nil }
            }
        }
    }

    private var lockOverlay: some View {
        VStack {
            if showControls {
                Button {
                    locked = false; showControls = true; scheduleHide()
                } label: {
                    Label("Экран заблокирован — нажмите, чтобы открыть", systemImage: "lock.fill")
                        .font(.footnote.weight(.semibold)).padding(.horizontal, 14).padding(.vertical, 9)
                        .background(.ultraThinMaterial, in: Capsule()).environment(\.colorScheme, .dark)
                        .foregroundStyle(.white)
                }
                .padding(.top, 18)
            }
            Spacer()
        }
    }

    private func nextCard(_ n: Int, isLand: Bool) -> some View {
        let next = c.episodes[c.index + 1]
        return HStack(spacing: 12) {
            ZStack {
                Circle().stroke(.white.opacity(0.25), lineWidth: 3)
                Circle().trim(from: 0, to: CGFloat(n) / 10).stroke(Color.white, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                Text("\(n)").font(.headline.monospacedDigit())
            }
            .frame(width: 40, height: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text("Далее · серия \(next.number)").font(.subheadline.weight(.bold))
                Text(next.displayTitle).font(.caption).foregroundStyle(.white.opacity(0.7)).lineLimit(1)
            }
            Spacer(minLength: 8)
            Button("Отмена") { c.cancelCountdown() }.font(.subheadline).foregroundStyle(.white.opacity(0.8))
            Button { c.cancelCountdown(); c.next() } label: {
                Label("Смотреть", systemImage: "play.fill").font(.subheadline.weight(.bold))
                    .padding(.horizontal, 14).padding(.vertical, 8).background(.white, in: Capsule()).foregroundStyle(.black)
            }
        }
        .padding(12)
        .frame(maxWidth: isLand ? 460 : .infinity)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .environment(\.colorScheme, .dark)
        .foregroundStyle(.white)
        .padding(isLand ? 30 : 8)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: isLand ? .bottomTrailing : .bottom)
    }

    /// Kodik has its own controls: we add close / dub / episodes around it.
    private func webChrome(isLand: Bool) -> some View {
        VStack {
            HStack(spacing: 8) {
                if isLand {
                    Spacer()
                    chip("Свернуть", "chevron.down") { OrientationController.set(.portrait) }
                    chip("Серия \(c.episode.number)", "rectangle.stack") { panel = .episodes }
                    chip(c.team.team.name, "waveform") { panel = .teams }
                    chip("", "arrow.clockwise") { webLoading = true; webReload += 1 }
                } else {
                    Spacer()
                    chip("", "arrow.up.left.and.arrow.down.right") { OrientationController.set(.landscape) }
                }
            }
            .padding(.top, isLand ? max(10, insets.top) : 10)
            .padding(.leading, isLand ? max(10, insets.leading) : 10)
            .padding(.trailing, isLand ? max(10, insets.trailing) : 10)
            .padding(.bottom, 10)
            Spacer()
        }
    }

    // MARK: Portrait details

    private var details: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if !c.isNative {
                        HStack(spacing: 8) {
                            wideButton("Закрыть", "chevron.down", prominent: false) { dismiss() }
                            wideButton("Обновить", "arrow.clockwise", prominent: false) { webLoading = true; webReload += 1 }
                        }
                        .padding(.horizontal)
                        Label("Плеер Kodik: в начале может быть реклама. Озвучки без рекламы отмечены в списке «Озвучка».",
                              systemImage: "info.circle")
                            .font(.caption).foregroundStyle(.white.opacity(0.55)).padding(.horizontal)
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        Text(c.title.title).font(.title3.bold()).lineLimit(2)
                        HStack(spacing: 6) {
                            Text("Серия \(c.episode.number)")
                            if !c.episode.displayTitle.isEmpty && c.episode.displayTitle != "Серия \(c.episode.number)" {
                                Text("·"); Text(c.episode.displayTitle).lineLimit(1)
                            }
                        }
                        .font(.subheadline).foregroundStyle(.white.opacity(0.65))
                    }
                    .padding(.horizontal)

                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            bigChip(c.team.team.name, "waveform", sub: c.team.native ? "без рекламы" : "Kodik") { panel = .teams }
                            if c.qualities.count > 1 {
                                bigChip(c.autoQuality ? "Авто" : (c.quality?.label ?? "—"), "sparkles.tv",
                                        sub: c.autoQuality ? (c.quality?.label ?? "") : "качество") { panel = .quality }
                            }
                            bigChip(c.rate == 1 ? "1×" : String(format: "%g×", c.rate), "gauge.with.dots.needle.50percent", sub: "скорость") { panel = .speed }
                            if c.isNative {
                                bigChip(c.subtitle == nil ? "Выкл" : "Вкл", "captions.bubble", sub: "субтитры") { panel = .subtitles }
                            }
                            bigChip(c.sleepAt != nil || c.sleepAfterEpisode ? "Вкл" : "Выкл", "moon.zzz", sub: "таймер") { panel = .sleep }
                        }
                        .padding(.horizontal)
                    }

                    if c.canSkipOpening || (c.nearEnd && c.hasNext) {
                        HStack(spacing: 10) {
                            if c.canSkipOpening {
                                wideButton("Пропустить опенинг", "forward.fill", prominent: true) { c.skipOpening() }
                            }
                            if c.nearEnd && c.hasNext {
                                wideButton("Серия \(c.episodes[c.index + 1].number)", "forward.end.fill", prominent: true) { c.next() }
                            }
                        }
                        .padding(.horizontal)
                    }

                    if c.isNative {
                        HStack(spacing: 10) {
                            RoomButtonWide(c: c)
                            if sharePlay.isEligible || sharePlay.isActive {
                                wideButton(sharePlay.isActive ? "SharePlay вкл." : "SharePlay", "shareplay", prominent: false) {
                                    if sharePlay.isActive { confirmLeaveSharePlay = true } else { Task { await sharePlay.start(with: c) } }
                                }
                            }
                        }
                        .padding(.horizontal)
                        if room.isActive { PortraitRoomChat().padding(.horizontal) }
                    }

                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Text("Серии").font(.headline)
                            Text("\(c.episodes.count)").font(.subheadline).foregroundStyle(.white.opacity(0.5))
                            Spacer()
                        }
                        .padding(.horizontal)
                        LazyVStack(spacing: 6) {
                            ForEach(Array(c.episodes.enumerated()), id: \.element.id) { i, ep in
                                episodeRow(i, ep).id(i)
                            }
                        }
                        .padding(.horizontal, 10)
                    }
                }
                .padding(.vertical, 16)
                .foregroundStyle(.white)
            }

        }
    }

    private func episodeRow(_ i: Int, _ ep: LibEpisode) -> some View {
        let current = i == c.index
        let progress = library.progress(episodeId: ep.id)
        let watched = library.isWatched(titleId: c.title.id, number: ep.number)
        return Button { if !current { c.go(to: i) } } label: {
            HStack(spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(current ? AnyShapeStyle(LinearGradient(colors: [Theme.accent, .purple], startPoint: .topLeading, endPoint: .bottomTrailing))
                                      : AnyShapeStyle(Color.white.opacity(0.08)))
                    if current && c.isPlaying {
                        Image(systemName: "waveform").font(.subheadline.weight(.bold))
                    } else {
                        Text(ep.number).font(.subheadline.monospacedDigit().weight(.bold))
                    }
                }
                .frame(width: 46, height: 46)
                VStack(alignment: .leading, spacing: 4) {
                    Text(ep.displayTitle).font(.subheadline.weight(current ? .bold : .medium)).lineLimit(1)
                    if progress > 0.02 && !watched {
                        Capsule().fill(.white.opacity(0.15)).frame(height: 3)
                            .overlay(alignment: .leading) {
                                GeometryReader { g in Capsule().fill(Theme.accent).frame(width: g.size.width * progress) }
                            }
                    } else {
                        Text(current ? "Сейчас" : watched ? "Просмотрено" : "Серия \(ep.number)")
                            .font(.caption).foregroundStyle(current ? Theme.accent : .white.opacity(0.5))
                    }
                }
                Spacer(minLength: 0)
                if watched { Image(systemName: "checkmark.circle.fill").foregroundStyle(.white.opacity(0.45)) }
            }
            .padding(8)
            .background(current ? Color.white.opacity(0.07) : .clear, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(PressableStyle())
    }

    // MARK: Landscape drawer

    private func drawer(_ p: PlayerPanel, width: CGFloat) -> some View {
        HStack(spacing: 0) {
            Color.black.opacity(0.35).ignoresSafeArea().onTapGesture { panel = nil }
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text(panelTitle(p)).font(.title3.bold())
                    Spacer()
                    Button { panel = nil } label: {
                        Image(systemName: "xmark").font(.subheadline.weight(.bold)).frame(width: 32, height: 32)
                            .background(.white.opacity(0.12), in: Circle())
                    }
                }
                .padding(.horizontal, 18).padding(.top, 18).padding(.bottom, 10)
                ScrollViewReader { proxy in
                    ScrollView { LazyVStack(alignment: .leading, spacing: 2) { panelContent(p) }.padding(.horizontal, 8) }
                        .onAppear { if p == .episodes { proxy.scrollTo(c.index, anchor: .center) } }
                }
            }
            .padding(.trailing, insets.trailing)
            .padding(.top, insets.top)
            .padding(.bottom, insets.bottom)
            .frame(width: min(400, width * 0.42) + insets.trailing)
            .background(.ultraThinMaterial)
            .environment(\.colorScheme, .dark)
            .foregroundStyle(.white)
        }
        .transition(.move(edge: .trailing).combined(with: .opacity))
    }

    // MARK: Panels (shared by sheet and drawer)

    private func panelTitle(_ p: PlayerPanel) -> String {
        switch p {
        case .episodes: return "Серии"
        case .teams: return "Озвучка"
        case .speed: return "Скорость"
        case .quality: return "Качество"
        case .subtitles: return "Субтитры"
        case .sleep: return "Таймер сна"
        }
    }

    @ViewBuilder
    private func panelContent(_ p: PlayerPanel) -> some View {
        switch p {
        case .episodes:
            ForEach(Array(c.episodes.enumerated()), id: \.element.id) { i, ep in
                row(selected: i == c.index) { c.go(to: i); panel = nil } content: {
                    Text(ep.number).font(.headline.monospacedDigit()).frame(width: 40, alignment: .leading)
                    Text(ep.displayTitle).lineLimit(1)
                    Spacer()
                    if library.isWatched(titleId: c.title.id, number: ep.number) {
                        Image(systemName: "checkmark.circle.fill").foregroundStyle(.white.opacity(0.45))
                    }
                }
                .id(i)
            }
        case .teams:
            ForEach(c.teams) { o in
                row(selected: o.id == c.team.id) { c.select(team: o); panel = nil } content: {
                    Image(systemName: o.team.isSubtitles ? "captions.bubble" : "waveform").frame(width: 22)
                    Text(o.team.name).lineLimit(1)
                    Spacer()
                    Text(o.native ? "без рекламы" : "Kodik").font(.caption.weight(.semibold))
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background((o.native ? Color.green : Color.orange).opacity(0.25), in: Capsule())
                }
            }
        case .speed:
            ForEach([0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0], id: \.self) { r in
                row(selected: abs(Double(c.rate) - r) < 0.01) { c.rate = Float(r); panel = nil } content: {
                    Text(r == 1 ? "Обычная" : String(format: "%g×", r)); Spacer()
                }
            }
            Text("Совет: удерживайте палец на видео — 2× пока держите.").font(.caption).foregroundStyle(.white.opacity(0.5)).padding()
        case .quality:
            row(selected: c.autoQuality) { c.select(autoQuality: true); panel = nil } content: {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Авто")
                    Text("Подбирается по скорости сети").font(.caption2).foregroundStyle(.white.opacity(0.5))
                }
                Spacer()
                if c.autoQuality, let q = c.quality { Text(q.label).font(.caption).foregroundStyle(.white.opacity(0.6)) }
            }
            ForEach(c.qualities) { q in
                row(selected: !c.autoQuality && q == c.quality) { c.select(quality: q); panel = nil } content: {
                    Text(q.label); Spacer()
                    if q.height >= 1080 { Text("HD").font(.caption2.bold()).padding(.horizontal, 5).padding(.vertical, 2).background(.white.opacity(0.2), in: RoundedRectangle(cornerRadius: 4)) }
                }
            }
            if c.isNative {
                delay("Задержка звука", c.audioDelay) { c.setAudioDelay($0) }.padding(.horizontal).padding(.top, 10)
            }
        case .subtitles:
            row(selected: c.subtitle == nil) { c.select(subtitle: nil); panel = nil } content: { Text("Выключены"); Spacer() }
            ForEach(c.subtitleTracks, id: \.url) { sub in
                row(selected: c.subtitle == sub) { c.select(subtitle: sub); panel = nil } content: {
                    Text(sub.label).lineLimit(1); Spacer()
                    Text(sub.format.uppercased()).font(.caption2).foregroundStyle(.white.opacity(0.5))
                }
            }
            row(selected: false) { importingSubtitle = true } content: {
                Label("Загрузить файл SRT / ASS…", systemImage: "doc.badge.plus"); Spacer()
            }
            if c.canTranslate {
                row(selected: false) { c.translateRequest += 1; panel = nil } content: {
                    Label("Перевести на русский", systemImage: "translate"); Spacer()
                }
            }
            delay("Задержка", c.subtitleDelay) { c.setSubtitleDelay($0) }.padding(.horizontal).padding(.vertical, 8)
            VStack(alignment: .leading, spacing: 10) {
                Text("Размер \(Int(subtitleSize))").font(.caption).foregroundStyle(.white.opacity(0.6))
                Slider(value: $subtitleSize, in: 14...40, step: 1).tint(Theme.accent)
                HStack(spacing: 10) {
                    ForEach(["white", "yellow", "cyan", "green", "pink"], id: \.self) { key in
                        Circle().fill(PlayerScreen.subtitleColors[key] ?? .white).frame(width: 26, height: 26)
                            .overlay(Circle().stroke(.white, lineWidth: subtitleColor == key ? 2.5 : 0).padding(-3))
                            .onTapGesture { subtitleColor = key }
                    }
                }
                Picker("Шрифт", selection: $subtitleFont) {
                    Text("Обычный").tag("system"); Text("Скруглённый").tag("rounded"); Text("С засечками").tag("serif")
                }
                .pickerStyle(.segmented)
                Toggle("Жирный", isOn: $subtitleBold)
                Toggle("Обводка", isOn: $subtitleOutline)
                Toggle("Подложка", isOn: $subtitleBackground)
            }
            .font(.subheadline)
            .padding()
        case .sleep:
            row(selected: c.sleepAt == nil && !c.sleepAfterEpisode) { c.sleepAt = nil; c.sleepAfterEpisode = false; panel = nil } content: { Text("Выключен"); Spacer() }
            row(selected: c.sleepAfterEpisode) { c.sleepAt = nil; c.sleepAfterEpisode = true; panel = nil } content: { Text("После этой серии"); Spacer() }
            ForEach([15, 30, 45, 60, 90], id: \.self) { m in
                row(selected: false) { c.sleepAfterEpisode = false; c.sleepAt = Date().addingTimeInterval(Double(m) * 60); panel = nil } content: {
                    Text("Через \(m) мин"); Spacer()
                }
            }
            if let at = c.sleepAt {
                Text("Пауза в \(at.formatted(date: .omitted, time: .shortened))").font(.caption).foregroundStyle(.white.opacity(0.6)).padding()
            }
        }
    }

    private func row<Content: View>(selected: Bool, action: @escaping () -> Void, @ViewBuilder content: () -> Content) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                content()
                if selected { Image(systemName: "checkmark").font(.subheadline.weight(.bold)).foregroundStyle(Theme.accent) }
            }
            .padding(.horizontal, 12).padding(.vertical, 12)
            .background(selected ? Color.white.opacity(0.1) : .clear, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func delay(_ title: String, _ value: Double, set: @escaping (Double) -> Void) -> some View {
        HStack(spacing: 10) {
            Text(title).font(.subheadline)
            Spacer()
            Button { set(value - 0.1) } label: { Image(systemName: "minus").frame(width: 32, height: 32).background(.white.opacity(0.12), in: Circle()) }
            Text(String(format: "%+.1f с", value)).font(.subheadline.monospacedDigit()).frame(minWidth: 58).onTapGesture { set(0) }
            Button { set(value + 0.1) } label: { Image(systemName: "plus").frame(width: 32, height: 32).background(.white.opacity(0.12), in: Circle()) }
        }
        .buttonStyle(.plain)
    }

    // MARK: Small building blocks

    private func icon(_ name: String, _ size: CGFloat, action: @escaping () -> Void) -> some View {
        Button { action(); scheduleHide() } label: {
            Image(systemName: name).font(.system(size: size, weight: .semibold))
                .frame(width: size + 24, height: size + 24).contentShape(Rectangle())
        }
        .buttonStyle(PressableStyle())
    }

    private func roundButton(_ name: String, size: CGFloat, bg: CGFloat, action: @escaping () -> Void) -> some View {
        Button { action(); scheduleHide() } label: {
            Image(systemName: name).font(.system(size: size, weight: .bold))
                .frame(width: bg, height: bg)
                .background(.black.opacity(0.28), in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(PressableStyle())
    }

    private func chip(_ text: String, _ icon: String, action: @escaping () -> Void) -> some View {
        Button { action(); scheduleHide() } label: {
            HStack(spacing: 5) {
                Image(systemName: icon).font(.caption.weight(.bold))
                if !text.isEmpty { Text(text).lineLimit(1) }
            }
            .font(.caption.weight(.semibold))
            .padding(.horizontal, text.isEmpty ? 9 : 12).padding(.vertical, 8)
            .background(.ultraThinMaterial, in: Capsule())
            .environment(\.colorScheme, .dark)
            .foregroundStyle(.white)
        }
        .buttonStyle(PressableStyle())
    }

    private func actionPill(_ text: String, _ icon: String, action: @escaping () -> Void) -> some View {
        Button { action(); scheduleHide() } label: {
            Label(text, systemImage: icon).font(.subheadline.weight(.bold))
                .padding(.horizontal, 16).padding(.vertical, 10)
                .background(.white, in: Capsule()).foregroundStyle(.black)
        }
        .buttonStyle(PressableStyle())
    }

    private func bigChip(_ title: String, _ icon: String, sub: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: icon).font(.body.weight(.semibold)).foregroundStyle(Theme.accent)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.subheadline.weight(.semibold)).lineLimit(1)
                    Text(sub).font(.caption2).foregroundStyle(.white.opacity(0.5)).lineLimit(1)
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(PressableStyle())
    }

    private func wideButton(_ text: String, _ icon: String, prominent: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(text, systemImage: icon).font(.subheadline.weight(.bold))
                .frame(maxWidth: .infinity).padding(.vertical, 12)
                .background(prominent ? AnyShapeStyle(Color.white) : AnyShapeStyle(Color.white.opacity(0.1)),
                            in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .foregroundStyle(prominent ? Color.black : Color.white)
        }
        .buttonStyle(PressableStyle())
    }

    @ViewBuilder
    private var noticeBanner: some View {
        if let n = c.notice {
            Text(n).font(.footnote.weight(.semibold)).foregroundStyle(.white)
                .padding(.horizontal, 14).padding(.vertical, 9)
                .background(.ultraThinMaterial, in: Capsule()).environment(\.colorScheme, .dark)
                .padding(.top, 50)
                .transition(.move(edge: .top).combined(with: .opacity))
                .task(id: n) {
                    try? await Task.sleep(nanoseconds: 4_000_000_000)
                    withAnimation { c.notice = nil }
                }
        } else if c.recovering {
            Label("Восстанавливаем воспроизведение…", systemImage: "arrow.clockwise")
                .font(.footnote.weight(.semibold)).foregroundStyle(.white)
                .padding(.horizontal, 14).padding(.vertical, 9)
                .background(.ultraThinMaterial, in: Capsule()).environment(\.colorScheme, .dark)
                .padding(.top, 50)
        }
    }

    private func framePreview(_ shot: UIImage) -> some View {
        ZStack {
            Color.black.opacity(0.6).ignoresSafeArea().onTapGesture { frameShot = nil }
            VStack(spacing: 14) {
                Image(uiImage: shot).resizable().scaledToFit().frame(maxWidth: 520, maxHeight: 280)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                HStack(spacing: 10) {
                    Button { UIImageWriteToSavedPhotosAlbum(shot, nil, nil, nil); frameSaved = true } label: {
                        Label(frameSaved ? "Сохранено" : "В Фото", systemImage: frameSaved ? "checkmark" : "square.and.arrow.down")
                    }
                    .disabled(frameSaved)
                    ShareLink(item: Image(uiImage: shot), preview: SharePreview(c.title.title, image: Image(uiImage: shot))) {
                        Label("Поделиться", systemImage: "square.and.arrow.up")
                    }
                    Button { frameShot = nil } label: { Image(systemName: "xmark") }
                }
                .buttonStyle(.bordered).tint(.white).font(.subheadline.weight(.semibold))
            }
            .padding()
        }
    }

    /// Hardware keyboard (iPad).
    private var shortcuts: some View {
        ZStack {
            Button("") { c.togglePlay(); showControls = true; scheduleHide() }.keyboardShortcut(.space, modifiers: [])
            Button("") { c.seek(by: -Double(PlayerPrefs.doubleTapStep)) }.keyboardShortcut(.leftArrow, modifiers: [])
            Button("") { c.seek(by: Double(PlayerPrefs.doubleTapStep)) }.keyboardShortcut(.rightArrow, modifiers: [])
            Button("") { c.previous() }.keyboardShortcut(.leftArrow, modifiers: .shift)
            Button("") { c.next() }.keyboardShortcut(.rightArrow, modifiers: .shift)
            Button("") { c.fillScreen.toggle() }.keyboardShortcut("f", modifiers: .command)
            Button("") { if panel != nil { panel = nil } else { dismiss() } }.keyboardShortcut(.escape, modifiers: [])
        }
        .opacity(0).allowsHitTesting(false).accessibilityHidden(true)
    }

    private func resetTransientInteraction() {
        if let previousRate = boosted {
            c.rate = previousRate
            boosted = nil
        }
        dragMode = nil
        seekPreview = nil
        dismissOffset = 0
        hud = nil
        ripple = nil
        if c.isScrubbing { c.isScrubbing = false }
    }

    private func scheduleHide() {
        hideTask?.cancel()
        guard scenePhase == .active else { return }
        hideTask = Task {
            try? await Task.sleep(nanoseconds: 3_500_000_000)
            guard !Task.isCancelled, scenePhase == .active, panel == nil, !c.isScrubbing else { return }
            if c.isPlaying || locked { showControls = false }
        }
    }

    private func fmt(_ t: Double) -> String {
        guard t.isFinite, t > 0 else { return "0:00" }
        let s = Int(t)
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
    }
}

// MARK: - Pieces

/// Thicker, friendlier scrubber: grows while dragging, shows a time bubble, marks the opening.
struct ScrubberV2: View {
    @Binding var value: Double
    let duration: Double
    let buffered: Double
    var marks: [Double] = []
    var opening: ClosedRange<Double>?
    var compact = false
    let onEditing: (Bool) -> Void
    @State private var dragging = false

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let total = max(duration, 1)
            let x = CGFloat(min(1, max(0, value / total))) * w
            let h: CGFloat = dragging ? 10 : (compact ? 3 : 5)
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.22)).frame(height: h)
                Capsule().fill(.white.opacity(0.35)).frame(width: CGFloat(min(1, buffered / total)) * w, height: h)
                if let op = opening, op.upperBound > op.lowerBound {
                    Rectangle().fill(Color.yellow.opacity(0.5))
                        .frame(width: CGFloat((op.upperBound - op.lowerBound) / total) * w, height: h)
                        .offset(x: CGFloat(op.lowerBound / total) * w)
                }
                Capsule().fill(LinearGradient(colors: [Theme.accent, Color(red: 1, green: 0.5, blue: 0.7)], startPoint: .leading, endPoint: .trailing))
                    .frame(width: max(h, x), height: h)
                ForEach(Array(marks.enumerated()), id: \.offset) { _, m in
                    Circle().fill(Color.white.opacity(0.9)).frame(width: 3, height: 3).offset(x: CGFloat(min(1, m / total)) * w - 1.5)
                }
                Circle().fill(.white).shadow(color: .black.opacity(0.4), radius: 3)
                    .frame(width: dragging ? 22 : (compact ? 10 : 14), height: dragging ? 22 : (compact ? 10 : 14))
                    .offset(x: x - (dragging ? 11 : (compact ? 5 : 7)))
                if dragging {
                    Text(Self.fmt(value)).font(.caption.monospacedDigit().weight(.bold))
                        .padding(.horizontal, 8).padding(.vertical, 4)
                        .background(.black.opacity(0.75), in: Capsule())
                        .foregroundStyle(.white)
                        .fixedSize()
                        .offset(x: min(max(0, x - 24), w - 52), y: -26)
                }
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { g in
                    if !dragging { dragging = true; onEditing(true); Haptics.tap() }
                    value = Double(max(0, min(w, g.location.x)) / w) * total
                }
                .onEnded { _ in dragging = false; onEditing(false) })
            .animation(.spring(response: 0.25, dampingFraction: 0.8), value: dragging)
        }
        .frame(height: compact ? 22 : 28)
    }

    static func fmt(_ t: Double) -> String {
        guard t.isFinite, t > 0 else { return "0:00" }
        let s = Int(t)
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
    }
}

/// Portrait sheet wrapper for player settings.
struct PanelSheet<Content: View>: View {
    let title: String
    @ViewBuilder var content: () -> Content
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView { LazyVStack(alignment: .leading, spacing: 2) { content() }.padding(.horizontal, 8).padding(.bottom) }
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } } }
        }
        .preferredColorScheme(.dark)
        .tint(Theme.accent)
    }
}

/// "Смотреть вместе" in the portrait layout.
struct RoomButtonWide: View {
    @ObservedObject var c: PlayerController
    @ObservedObject private var room = WatchRoom.shared
    @State private var inviteURL: URL?

    var body: some View {
        Group {
            if room.isActive, let id = room.roomId {
                Menu {
                    ShareLink(item: WatchRoom.link(id)) { Label("Пригласить по ссылке", systemImage: "link") }
                    Button { room.startPoll(question: WatchRoom.nextEpisodePoll.0, options: WatchRoom.nextEpisodePoll.1) } label: {
                        Label("Голосование: следующая серия?", systemImage: "checklist")
                    }
                    Button(role: .destructive) { room.leave() } label: { Label("Выйти из комнаты", systemImage: "rectangle.portrait.and.arrow.right") }
                } label: { label("В комнате · \(room.others + 1)", "person.2.fill", active: true) }
            } else {
                Button { inviteURL = room.create(with: c) } label: { label("Смотреть вместе", "person.2", active: false) }
            }
        }
        .sheet(item: Binding(get: { inviteURL.map(IdentifiedURL.init) }, set: { inviteURL = $0?.url })) { RoomInviteSheet(url: $0.url) }
    }

    private func label(_ text: String, _ icon: String, active: Bool) -> some View {
        Label(text, systemImage: icon).font(.subheadline.weight(.bold))
            .frame(maxWidth: .infinity).padding(.vertical, 12)
            .background(active ? Color.green.opacity(0.25) : Color.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .foregroundStyle(.white)
    }
}

/// Room chat under the video in portrait.
struct PortraitRoomChat: View {
    @ObservedObject private var room = WatchRoom.shared
    @State private var text = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let p = room.poll {
                VStack(alignment: .leading, spacing: 6) {
                    Label(p.question, systemImage: "checklist").font(.subheadline.bold())
                    ForEach(Array(p.options.enumerated()), id: \.offset) { i, o in
                        Button { room.vote(i) } label: {
                            HStack { Text(o); Spacer(); Text("\(p.count(i))").bold() }.font(.subheadline)
                                .padding(10).background((p.closed && p.winner == i ? Color.green : Color.white).opacity(0.15), in: RoundedRectangle(cornerRadius: 10))
                        }
                        .disabled(p.closed)
                    }
                }
            }
            ForEach(room.chat.suffix(5)) { line in
                (Text(line.name + ": ").bold() + Text(line.text)).font(.footnote)
            }
            HStack(spacing: 6) {
                ForEach(RoomOverlay.emojis.prefix(6), id: \.self) { e in
                    Button { room.react(e) } label: { Text(e).font(.title3) }
                }
            }
            HStack {
                TextField("Сообщение в комнату", text: $text).textFieldStyle(.plain)
                    .padding(.horizontal, 12).padding(.vertical, 9).background(.white.opacity(0.1), in: Capsule())
                    .onSubmit { room.sendChat(text); text = "" }
                Button { room.sendChat(text); text = "" } label: { Image(systemName: "paperplane.fill") }
                    .disabled(text.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(12)
        .background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .foregroundStyle(.white)
    }
}
