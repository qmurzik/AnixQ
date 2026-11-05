import SwiftUI
import AVKit
import MediaPlayer
import WebKit

enum PlayerPanel: String, Identifiable {
    case episodes, teams, speed, quality, subtitles, sleep
    var id: String { rawValue }
}

struct PlayerScreen: View {
    @StateObject private var c: PlayerController
    @EnvironmentObject private var library: LibraryStore
    @Environment(\.dismiss) private var dismiss
    let background: URL?

    @State private var showControls = true
    @State private var locked = false
    @State private var panel: PlayerPanel?
    @State private var hideTask: Task<Void, Never>?
    @State private var pip: AVPictureInPictureController?
    @State private var gestureHUD: String?
    @State private var gestureIcon = ""
    @State private var gestureValue: Double?
    @State private var dragMode: DragMode?
    @State private var dragStart: Double = 0
    @State private var seekPreview: Double?
    @State private var ripple: (left: Bool, id: UUID)?
    @State private var scrubValue: Double = 0
    @State private var webLoading = true
    @State private var webReload = 0
    @AppStorage("subs.size") private var subtitleSize: Double = 20
    @AppStorage("subs.background") private var subtitleBackground = true

    enum DragMode { case brightness, volume, seek, ignored }

    init(title: Title, background: URL?, episodes: [LibEpisode], teams: [TeamOption], startIndex: Int, team: TeamOption) {
        _c = StateObject(wrappedValue: PlayerController(title: title, episodes: episodes, teams: teams,
                                                       index: startIndex, team: team))
        self.background = background
    }

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black.ignoresSafeArea()
                video(size: geo.size)
                if c.isNative {
                    gestureLayer(size: geo.size)
                    if !locked && (showControls || !c.isPlaying || panel != nil) {
                        controls(landscape: geo.size.width > geo.size.height)
                            .transition(.opacity)
                    }
                    overlays
                    if locked { lockOverlay }
                } else {
                    webChrome
                }
                if let panel { panelView(panel, size: geo.size) }
                VolumeSliderHost().frame(width: 1, height: 1).opacity(0.01)
            }
        }
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        .preferredColorScheme(.dark)
        .animation(.easeInOut(duration: 0.2), value: showControls)
        .animation(.easeInOut(duration: 0.25), value: panel)
        .onAppear {
            c.library = library
            c.start()
            OrientationController.set(.landscape)
            scheduleHide()
        }
        .onDisappear {
            // Picture-in-Picture keeps playing after the player screen is closed.
            if let pip, pip.isPictureInPictureActive {
                PiPKeeper.shared.keep(c, pip: pip)
            } else {
                c.stop()
            }
            OrientationController.set(.portrait)
        }
        .onChange(of: c.isPlaying) { playing in if playing { scheduleHide() } }
    }

    // MARK: Video

    @ViewBuilder
    private func video(size: CGSize) -> some View {
        ZStack {
            PlayerSurface(player: c.player, fill: c.fillScreen, pip: $pip)
                .opacity(c.isNative ? 1 : 0)
                .ignoresSafeArea()
            if !c.isNative, let url = c.kodikURL {
                KodikWebView(url: url, isLoading: $webLoading)
                    .id("\(url.absoluteString)#\(webReload)")
            }
            if c.isLoading || (!c.isNative && c.kodikURL != nil && webLoading) {
                VStack(spacing: 14) {
                    ProgressView().tint(.white).scaleEffect(1.4)
                    Text(c.isNative ? "Серия \(c.episode.number)" : "Загружаем Kodik… (с рекламой)")
                        .font(.footnote).foregroundStyle(.white.opacity(0.7))
                }
                .allowsHitTesting(false)
            }
            if let text = c.subtitleText {
                VStack {
                    Spacer()
                    Text(text)
                        .font(.system(size: subtitleSize, weight: .semibold))
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.white)
                        .shadow(color: .black, radius: 2)
                        .shadow(color: .black, radius: 4)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(Color.black.opacity(subtitleBackground ? 0.45 : 0), in: RoundedRectangle(cornerRadius: 6))
                        .padding(.horizontal, 40)
                        .padding(.bottom, showControls ? 110 : 28)
                }
                .allowsHitTesting(false)
                .animation(.easeInOut(duration: 0.2), value: showControls)
            }
            if let e = c.error {
                VStack(spacing: 12) {
                    Image(systemName: "exclamationmark.triangle.fill").font(.largeTitle).foregroundStyle(.yellow)
                    Text(e).multilineTextAlignment(.center).foregroundStyle(.white.opacity(0.85))
                    HStack {
                        Button("Повторить") { Task { await c.load() } }
                        Button("Другая озвучка") { panel = .teams }
                    }
                    .buttonStyle(.bordered)
                }
                .padding(30)
            }
        }
    }

    // MARK: Gestures

    private func gestureLayer(size: CGSize) -> some View {
        HStack(spacing: 0) {
            tapZone(left: true)
            tapZone(left: false)
        }
        .contentShape(Rectangle())
        .gesture(locked ? nil : dragGesture(size: size))
        .simultaneousGesture(locked ? nil : MagnificationGesture().onEnded { scale in
            withAnimation { c.fillScreen = scale > 1 }
            flashHUD(icon: c.fillScreen ? "arrow.up.left.and.arrow.down.right" : "arrow.down.right.and.arrow.up.left",
                     text: c.fillScreen ? "Заполнить экран" : "Вписать")
        })
    }

    private func tapZone(left: Bool) -> some View {
        Color.clear
            .contentShape(Rectangle())
            .onTapGesture(count: 2) {
                guard !locked else { return }
                c.seek(by: Double(left ? -PlayerPrefs.doubleTapStep : PlayerPrefs.doubleTapStep))
                ripple = (left, UUID())
            }
            .onTapGesture {
                if locked { showControls.toggle(); scheduleHide(); return }
                if panel != nil { panel = nil; return }
                showControls.toggle()
                if showControls { scheduleHide() }
            }
    }

    private func dragGesture(size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 12)
            .onChanged { v in
                if dragMode == nil {
                    if abs(v.translation.width) > abs(v.translation.height) {
                        dragMode = PlayerPrefs.gestureSeek ? .seek : .ignored
                        dragStart = c.currentTime
                    } else {
                        let left = v.startLocation.x < size.width / 2
                        if left { dragMode = PlayerPrefs.gestureBrightness ? .brightness : .ignored }
                        else { dragMode = PlayerPrefs.gestureVolume ? .volume : .ignored }
                        dragStart = dragMode == .brightness ? Double(UIScreen.main.brightness)
                                                            : Double(AVAudioSession.sharedInstance().outputVolume)
                    }
                }
                switch dragMode {
                case .seek:
                    let secondsPerPoint = max(60, c.duration) / Double(size.width) * 0.5
                    let target = max(0, min(c.duration, dragStart + Double(v.translation.width) * secondsPerPoint))
                    seekPreview = target
                    let delta = Int(target - dragStart)
                    gestureIcon = delta >= 0 ? "goforward" : "gobackward"
                    gestureHUD = "\(format(target)) (\(delta >= 0 ? "+" : "−")\(abs(delta)) с)"
                    gestureValue = nil
                case .brightness, .volume:
                    let value = max(0, min(1, dragStart - Double(v.translation.height) / Double(size.height * 0.7)))
                    if dragMode == .brightness {
                        UIScreen.main.brightness = CGFloat(value)
                        gestureIcon = "sun.max.fill"
                    } else {
                        VolumeSliderHost.set(Float(value))
                        gestureIcon = value == 0 ? "speaker.slash.fill" : "speaker.wave.2.fill"
                    }
                    gestureHUD = "\(Int(value * 100))%"
                    gestureValue = value
                case .ignored, nil: break
                }
            }
            .onEnded { _ in
                if dragMode == .seek, let t = seekPreview { c.seek(to: t) }
                dragMode = nil
                seekPreview = nil
                withAnimation(.easeOut(duration: 0.3).delay(0.4)) { gestureHUD = nil }
            }
    }

    private func flashHUD(icon: String, text: String) {
        gestureIcon = icon
        gestureHUD = text
        gestureValue = nil
        Task {
            try? await Task.sleep(nanoseconds: 900_000_000)
            withAnimation { gestureHUD = nil }
        }
    }

    // MARK: Controls

    private func controls(landscape: Bool) -> some View {
        ZStack {
            LinearGradient(colors: [.black.opacity(0.7), .clear, .clear, .black.opacity(0.75)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
                .allowsHitTesting(false)

            VStack(spacing: 0) {
                topBar
                Spacer()
                bottomBar
            }
            .padding(.horizontal, landscape ? 28 : 14)
            .padding(.vertical, 10)

            if !c.isLoading {
                HStack(spacing: landscape ? 70 : 46) {
                    iconButton(c.hasPrevious ? "backward.end.fill" : "gobackward.10", size: 22) {
                        c.hasPrevious && c.currentTime < 5 ? c.previous() : c.seek(by: -10)
                    }
                    iconButton(c.isPlaying ? "pause.fill" : "play.fill", size: 40) { c.togglePlay() }
                    iconButton("goforward.10", size: 22) { c.seek(by: 10) }
                }
            }
        }
        .foregroundStyle(.white)
    }

    private var topBar: some View {
        HStack(spacing: 14) {
            iconButton("xmark", size: 17) { dismiss() }
            VStack(alignment: .leading, spacing: 1) {
                Text(c.title.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                Text("Серия \(c.episode.number) · \(c.team.team.name)").font(.caption).foregroundStyle(.white.opacity(0.7))
                    .lineLimit(1)
            }
            Spacer()
            RoutePicker().frame(width: 30, height: 30)
            if let pip, AVPictureInPictureController.isPictureInPictureSupported() {
                iconButton("pip.enter", size: 17) { pip.startPictureInPicture() }
            }
            iconButton(c.sleepAt != nil || c.sleepAfterEpisode ? "moon.zzz.fill" : "moon.zzz", size: 17) {
                panel = .sleep
            }
            iconButton("lock.open", size: 17) {
                locked = true
                showControls = true
                scheduleHide()
            }
        }
    }

    private var bottomBar: some View {
        VStack(spacing: 10) {
            HStack(spacing: 8) {
                if c.canSkipOpening && (c.opening != nil || c.currentTime < PlayerPrefs.openingLength + 60) {
                    pill("Пропустить опенинг", icon: "forward.fill", prominent: true) { c.skipOpening() }
                }
                Spacer()
                if c.nearEnd {
                    pill("Серия \(c.episodes[c.index + 1].number)", icon: "forward.end.fill", prominent: true) { c.next() }
                }
            }
            Scrubber(value: Binding(get: { c.isScrubbing ? scrubValue : c.currentTime },
                                    set: { scrubValue = $0 }),
                     duration: c.duration, buffered: bufferedTime) { editing in
                if editing {
                    scrubValue = c.currentTime
                    c.isScrubbing = true
                    hideTask?.cancel()
                } else {
                    c.seek(to: scrubValue)
                    c.isScrubbing = false
                    scheduleHide()
                }
            }
            HStack(spacing: 8) {
                Text("\(format(c.isScrubbing ? scrubValue : c.currentTime)) / \(format(c.duration))")
                    .font(.caption.monospacedDigit().weight(.medium))
                    .foregroundStyle(.white.opacity(0.85))
                Spacer()
                pill("Серии", icon: "list.number") { panel = .episodes }
                pill(c.team.team.name, icon: "waveform") { panel = .teams }
                pill(c.rate == 1 ? "1×" : String(format: "%g×", c.rate), icon: "gauge.with.dots.needle.50percent") { panel = .speed }
                if c.qualities.count > 1 { pill(c.quality?.label ?? "Авто", icon: "sparkles.tv") { panel = .quality } }
                if !c.subtitleTracks.isEmpty {
                    pill(c.subtitle == nil ? "Субтитры выкл" : "Субтитры", icon: "captions.bubble") { panel = .subtitles }
                }
            }
        }
    }

    private var bufferedTime: Double {
        guard let r = c.player.currentItem?.loadedTimeRanges.last?.timeRangeValue else { return 0 }
        return (r.start + r.duration).seconds
    }

    private func iconButton(_ icon: String, size: CGFloat, action: @escaping () -> Void) -> some View {
        Button {
            action()
            scheduleHide()
        } label: {
            Image(systemName: icon)
                .font(.system(size: size, weight: .semibold))
                .frame(width: size + 26, height: size + 26)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressableStyle())
    }

    private func pill(_ text: String, icon: String, prominent: Bool = false, action: @escaping () -> Void) -> some View {
        Button {
            action()
            scheduleHide()
        } label: {
            HStack(spacing: 5) {
                Image(systemName: icon).font(.caption.weight(.bold))
                Text(text).lineLimit(1)
            }
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 11)
            .padding(.vertical, 7)
            .background(prominent ? AnyShapeStyle(Color.white) : AnyShapeStyle(.ultraThinMaterial), in: Capsule())
            .foregroundStyle(prominent ? Color.black : Color.white)
        }
        .buttonStyle(PressableStyle())
    }

    // MARK: Overlays

    @ViewBuilder
    private var overlays: some View {
        if let hud = gestureHUD {
            VStack(spacing: 8) {
                Image(systemName: gestureIcon).font(.title2)
                Text(hud).font(.subheadline.monospacedDigit().weight(.semibold))
                if let v = gestureValue {
                    ProgressView(value: v).tint(.white).frame(width: 120)
                }
            }
            .padding(18)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .foregroundStyle(.white)
            .allowsHitTesting(false)
        }
        if let r = ripple {
            HStack {
                if !r.left { Spacer() }
                Label(r.left ? "−\(PlayerPrefs.doubleTapStep) с" : "+\(PlayerPrefs.doubleTapStep) с",
                      systemImage: r.left ? "gobackward" : "goforward")
                    .font(.headline)
                    .padding(.horizontal, 18).padding(.vertical, 12)
                    .background(.ultraThinMaterial, in: Capsule())
                    .foregroundStyle(.white)
                    .padding(.horizontal, 60)
                if r.left { Spacer() }
            }
            .allowsHitTesting(false)
            .task(id: r.id) {
                try? await Task.sleep(nanoseconds: 650_000_000)
                withAnimation { ripple = nil }
            }
        }
        if let n = c.nextCountdown, c.hasNext {
            VStack(spacing: 12) {
                Text("Следующая серия через \(n)").font(.headline)
                Text(c.episodes[c.index + 1].displayTitle).font(.subheadline).foregroundStyle(.white.opacity(0.7))
                HStack {
                    Button("Отмена") { c.cancelCountdown() }.buttonStyle(.bordered)
                    Button("Смотреть") { c.cancelCountdown(); c.next() }.buttonStyle(.borderedProminent)
                }
            }
            .padding(22)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .foregroundStyle(.white)
        }
    }

    private var lockOverlay: some View {
        VStack {
            HStack {
                Spacer()
                if showControls {
                    Button {
                        locked = false
                        showControls = true
                        scheduleHide()
                    } label: {
                        Label("Разблокировать", systemImage: "lock.fill")
                            .font(.subheadline.weight(.semibold))
                            .padding(.horizontal, 14).padding(.vertical, 9)
                            .background(.ultraThinMaterial, in: Capsule())
                            .foregroundStyle(.white)
                    }
                    .transition(.opacity)
                }
            }
            Spacer()
        }
        .padding(20)
    }

    /// Kodik has its own controls; we only add a slim bar with close / episodes / dub.
    private var webChrome: some View {
        VStack {
            HStack(spacing: 10) {
                pill("Закрыть", icon: "xmark") { dismiss() }
                pill("Серия \(c.episode.number)", icon: "list.number") { panel = .episodes }
                pill(c.team.team.name, icon: "waveform") { panel = .teams }
                pill("", icon: "arrow.clockwise") { webLoading = true; webReload += 1 }
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.top, 6)
            Spacer()
        }
    }

    // MARK: Panels

    private func panelView(_ p: PlayerPanel, size: CGSize) -> some View {
        HStack(spacing: 0) {
            Color.black.opacity(0.001).onTapGesture { panel = nil }
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text(panelTitle(p)).font(.headline)
                    Spacer()
                    Button { panel = nil } label: { Image(systemName: "xmark.circle.fill").font(.title3) }
                        .foregroundStyle(.white.opacity(0.6))
                }
                .padding()
                Divider().overlay(Color.white.opacity(0.15))
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 0) { panelContent(p) }
                    }
                    .onAppear { if p == .episodes { proxy.scrollTo(c.index, anchor: .center) } }
                }
            }
            .frame(width: min(360, size.width * 0.85))
            .background(.ultraThinMaterial)
            .environment(\.colorScheme, .dark)
            .foregroundStyle(.white)
        }
        .transition(.move(edge: .trailing))
        .ignoresSafeArea(edges: .vertical)
    }

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
                panelRow(selected: i == c.index) {
                    c.go(to: i)
                    panel = nil
                } content: {
                    Text(ep.number).font(.headline.monospacedDigit()).frame(width: 40, alignment: .leading)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(ep.displayTitle).lineLimit(1)
                        let prog = library.progress(episodeId: ep.id)
                        if prog > 0.02 { ProgressView(value: prog).tint(.white) }
                    }
                    Spacer()
                    if library.isWatched(titleId: c.title.id, number: ep.number) {
                        Image(systemName: "checkmark").foregroundStyle(.white.opacity(0.6))
                    }
                }
                .id(i)
            }
        case .teams:
            ForEach(c.teams) { o in
                panelRow(selected: o.id == c.team.id) {
                    c.select(team: o)
                    panel = nil
                } content: {
                    Image(systemName: o.team.isSubtitles ? "captions.bubble" : "waveform")
                    Text(o.team.name).lineLimit(1)
                    Spacer()
                    Text(o.native ? "без рекламы" : "Kodik").font(.caption).foregroundStyle(.white.opacity(0.6))
                }
            }
        case .speed:
            ForEach([0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0], id: \.self) { r in
                panelRow(selected: abs(Double(c.rate) - r) < 0.01) {
                    c.rate = Float(r)
                    panel = nil
                } content: {
                    Text(r == 1 ? "Обычная" : String(format: "%g×", r))
                    Spacer()
                }
            }
        case .subtitles:
            panelRow(selected: c.subtitle == nil) {
                c.select(subtitle: nil)
                panel = nil
            } content: {
                Text("Выключены")
                Spacer()
            }
            ForEach(c.subtitleTracks, id: \.url) { sub in
                panelRow(selected: c.subtitle == sub) {
                    c.select(subtitle: sub)
                    panel = nil
                } content: {
                    Text(sub.label).lineLimit(1)
                    Spacer()
                    Text(sub.format.uppercased()).font(.caption2).foregroundStyle(.white.opacity(0.5))
                }
            }
            VStack(alignment: .leading, spacing: 10) {
                Text("Размер: \(Int(subtitleSize))").font(.caption).foregroundStyle(.white.opacity(0.7))
                Slider(value: $subtitleSize, in: 14...34, step: 1).tint(.white)
                Toggle("Подложка", isOn: $subtitleBackground).font(.subheadline)
            }
            .padding()
        case .sleep:
            sleepPanel
        case .quality:
            ForEach(c.qualities) { q in
                panelRow(selected: q == c.quality) {
                    c.select(quality: q)
                    panel = nil
                } content: {
                    Text(q.label)
                    Spacer()
                }
            }
        }
    }

    @ViewBuilder
    private var sleepPanel: some View {
        panelRow(selected: c.sleepAt == nil && !c.sleepAfterEpisode) {
            c.sleepAt = nil
            c.sleepAfterEpisode = false
            panel = nil
        } content: {
            Text("Выключен")
            Spacer()
        }
        panelRow(selected: c.sleepAfterEpisode) {
            c.sleepAt = nil
            c.sleepAfterEpisode = true
            panel = nil
        } content: {
            Text("После этой серии")
            Spacer()
        }
        ForEach([15, 30, 45, 60, 90], id: \.self) { m in
            panelRow(selected: false) {
                c.sleepAfterEpisode = false
                c.sleepAt = Date().addingTimeInterval(Double(m) * 60)
                panel = nil
            } content: {
                Text("Через \(m) мин")
                Spacer()
            }
        }
        if let at = c.sleepAt {
            Text("Пауза в \(at.formatted(date: .omitted, time: .shortened))")
                .font(.caption).foregroundStyle(.white.opacity(0.6)).padding()
        }
    }

    private func panelRow<Content: View>(selected: Bool, action: @escaping () -> Void,
                                         @ViewBuilder content: () -> Content) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                content()
                if selected { Image(systemName: "checkmark").fontWeight(.bold) }
            }
            .padding(.horizontal)
            .padding(.vertical, 12)
            .background(selected ? Color.white.opacity(0.12) : .clear)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: Helpers

    private func scheduleHide() {
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(nanoseconds: 3_500_000_000)
            guard !Task.isCancelled, panel == nil, !c.isScrubbing else { return }
            if c.isPlaying || locked { showControls = false }
        }
    }

    private func format(_ t: Double) -> String {
        guard t.isFinite, t > 0 else { return "0:00" }
        let s = Int(t)
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                         : String(format: "%d:%02d", s / 60, s % 60)
    }
}

// MARK: - Scrubber

struct Scrubber: View {
    @Binding var value: Double
    let duration: Double
    let buffered: Double
    let onEditing: (Bool) -> Void
    @State private var dragging = false

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let total = max(duration, 1)
            let x = CGFloat(min(1, max(0, value / total))) * w
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.25))
                Capsule().fill(.white.opacity(0.35)).frame(width: CGFloat(min(1, buffered / total)) * w)
                Capsule().fill(.white).frame(width: x)
                Circle().fill(.white)
                    .frame(width: dragging ? 18 : 12, height: dragging ? 18 : 12)
                    .offset(x: x - (dragging ? 9 : 6))
            }
            .frame(height: dragging ? 8 : 5)
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { g in
                    if !dragging { dragging = true; onEditing(true) }
                    value = Double(max(0, min(w, g.location.x)) / w) * total
                }
                .onEnded { _ in
                    dragging = false
                    onEditing(false)
                })
            .animation(.easeOut(duration: 0.15), value: dragging)
        }
        .frame(height: 26)
    }
}

// MARK: - UIKit bridges

final class PlayerLayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}

struct PlayerSurface: UIViewRepresentable {
    let player: AVPlayer
    let fill: Bool
    @Binding var pip: AVPictureInPictureController?

    func makeUIView(context: Context) -> PlayerLayerView {
        let v = PlayerLayerView()
        v.backgroundColor = .black
        v.playerLayer.player = player
        v.playerLayer.videoGravity = .resizeAspect
        if AVPictureInPictureController.isPictureInPictureSupported() {
            let controller = AVPictureInPictureController(playerLayer: v.playerLayer)
            controller?.canStartPictureInPictureAutomaticallyFromInline = true
            DispatchQueue.main.async { pip = controller }
        }
        return v
    }

    func updateUIView(_ v: PlayerLayerView, context: Context) {
        if v.playerLayer.player !== player { v.playerLayer.player = player }
        v.playerLayer.videoGravity = fill ? .resizeAspectFill : .resizeAspect
    }
}

struct RoutePicker: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView()
        v.tintColor = .white
        v.activeTintColor = .systemBlue
        v.prioritizesVideoDevices = true
        return v
    }
    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}

/// Hidden MPVolumeView: the only public way to change system volume from a gesture.
struct VolumeSliderHost: UIViewRepresentable {
    static weak var slider: UISlider?

    static func set(_ value: Float) {
        slider?.setValue(value, animated: false)
        slider?.sendActions(for: .valueChanged)
    }

    func makeUIView(context: Context) -> MPVolumeView {
        let v = MPVolumeView(frame: CGRect(x: -100, y: -100, width: 10, height: 10))
        v.showsVolumeSlider = true
        VolumeSliderHost.slider = v.subviews.compactMap { $0 as? UISlider }.first
        return v
    }

    func updateUIView(_ uiView: MPVolumeView, context: Context) {
        if VolumeSliderHost.slider == nil {
            VolumeSliderHost.slider = uiView.subviews.compactMap { $0 as? UISlider }.first
        }
    }
}

enum OrientationController {
    enum Target { case portrait, landscape }

    static func set(_ target: Target) {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { return }
        let mask: UIInterfaceOrientationMask = target == .landscape ? .landscapeRight : .portrait
        for window in scene.windows {
            window.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
            window.rootViewController?.presentedViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
        }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { _ in }
    }
}

// MARK: - Kodik web player

struct KodikWebView: UIViewRepresentable {
    let url: URL
    @Binding var isLoading: Bool

    func makeCoordinator() -> Coordinator { Coordinator(isLoading: $isLoading) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.allowsPictureInPictureMediaPlayback = true
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        if #available(iOS 15.4, *) { config.preferences.isElementFullscreenEnabled = false }
        let probe = """
        (function(){
          ['playing','error'].forEach(function(ev){
            document.addEventListener(ev, function(e){
              var t=e.target; if(!(t instanceof HTMLMediaElement)) return;
              try{ window.webkit.messageHandlers.dbg.postMessage(ev+' '+location.host+' '+(t.currentSrc||'').slice(0,60)); }catch(x){}
            }, true);
          });
        })();
        """
        config.userContentController.addUserScript(WKUserScript(source: probe, injectionTime: .atDocumentEnd, forMainFrameOnly: false))
        config.userContentController.add(context.coordinator, name: "dbg")

        let web = WKWebView(frame: .zero, configuration: config)
        web.isOpaque = false
        web.backgroundColor = .black
        web.scrollView.isScrollEnabled = false
        web.scrollView.contentInsetAdjustmentBehavior = .never
        web.navigationDelegate = context.coordinator
        web.uiDelegate = context.coordinator
        let src = url.absoluteString.replacingOccurrences(of: "\"", with: "&quot;")
        let html = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
        iframe{position:absolute;inset:0;width:100%;height:100%;border:0}</style></head><body>
        <iframe src="\(src)" allow="autoplay; fullscreen; picture-in-picture" allowfullscreen referrerpolicy="origin"></iframe>
        </body></html>
        """
        DebugLog.log("web: load \(url.absoluteString)")
        Task { @MainActor in
            if AdBlocker.isEnabled, let rules = await AdBlocker.shared.rules() {
                web.configuration.userContentController.add(rules)
            }
            web.loadHTMLString(html, baseURL: URL(string: "https://anilib.me/"))
        }
        return web
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        uiView.configuration.userContentController.removeScriptMessageHandler(forName: "dbg")
        uiView.stopLoading()
        uiView.loadHTMLString("", baseURL: nil)
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
        private let isLoading: Binding<Bool>
        init(isLoading: Binding<Bool>) { self.isLoading = isLoading }

        func userContentController(_ ucc: WKUserContentController, didReceive message: WKScriptMessage) {
            DebugLog.log("web: \(message.body)")
        }

        private func allowed(_ host: String) -> Bool {
            ["kodik", "aniqit", "anilib", "solodcdn"].contains { host.contains($0) }
        }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard action.targetFrame?.isMainFrame ?? false else { decisionHandler(.allow); return }
            let url = action.request.url
            if url?.scheme == "about" { decisionHandler(.allow); return }
            if let host = url?.host, allowed(host) { decisionHandler(.allow); return }
            DebugLog.log("web: blocked redirect to \(url?.host ?? "?")")
            decisionHandler(.cancel)
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            DispatchQueue.main.async { self.isLoading.wrappedValue = false }
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            DispatchQueue.main.async { self.isLoading.wrappedValue = false }
        }

        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                     for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? { nil }
    }
}


/// Holds the playback controller alive while Picture-in-Picture runs without the player screen.
final class PiPKeeper: NSObject, AVPictureInPictureControllerDelegate {
    static let shared = PiPKeeper()
    private var controller: PlayerController?

    func keep(_ c: PlayerController, pip: AVPictureInPictureController) {
        controller = c
        pip.delegate = self
    }

    func pictureInPictureControllerDidStopPictureInPicture(_ pip: AVPictureInPictureController) {
        let c = controller
        controller = nil
        Task { @MainActor in c?.stop() }
    }

    func pictureInPictureController(_ pip: AVPictureInPictureController,
                                    restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completion: @escaping (Bool) -> Void) {
        completion(true)
    }
}
