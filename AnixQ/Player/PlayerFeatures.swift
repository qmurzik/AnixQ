import SwiftUI
import AVFoundation
import CoreImage
#if canImport(Translation)
import Translation
#endif

// MARK: - Timeline comments (AnimeLib comments with timecodes)

struct TimedComment: Identifiable, Hashable {
    let id: Int
    let time: Double
    let text: String
    let user: String
}

extension LibAPI {
    /// Comments of an episode that carry a timecode ("03:34 Это всё план Айзена!").
    /// They are rare, so a few pages are scanned.
    static func timecodedComments(episodeId: Int, pages: Int = 6) async -> [TimedComment] {
        var out: [TimedComment] = []
        for page in 1...pages {
            guard let json = try? await request("/comments", query: [
                URLQueryItem(name: "post_id", value: String(episodeId)),
                URLQueryItem(name: "post_type", value: "episodes"),
                URLQueryItem(name: "page", value: String(page))
            ], auth: false) as? JSON else { break }
            let data = json.obj("data") ?? [:]
            for c in data.arr("root") + data.arr("replies") {
                guard let id = c.int("id"), let html = c.str("comment"), html.contains("timecode-node") else { continue }
                guard let r = html.range(of: #"timecode-node[^>]*>\s*([0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)\s*</span>"#,
                                         options: .regularExpression) else { continue }
                let match = String(html[r])
                guard let tr = match.range(of: #"[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?"#, options: .regularExpression) else { continue }
                let parts = match[tr].split(separator: ":").compactMap { Double($0) }
                let seconds = parts.reduce(0) { $0 * 60 + $1 }
                var text = html
                text.removeSubrange(r)
                text = ShikiAPI.plainText(text.replacingOccurrences(of: "<span class=\"", with: "<span class=\""))
                text = text.replacingOccurrences(of: #"^[^\p{L}\p{N}]+"#, with: "", options: .regularExpression)
                guard !text.isEmpty, text.count < 300 else { continue }
                out.append(TimedComment(id: id, time: seconds, text: text,
                                        user: c.obj("user")?.str("username") ?? ""))
            }
            if (json.obj("meta")?["has_next_page"] as? Bool) != true { break }
        }
        return out.sorted { $0.time < $1.time }
    }
}

/// Niconico-style comments flying across the video at their moment.
struct DanmakuLayer: View {
    let comments: [TimedComment]
    let time: Double

    private struct Flying: Identifiable {
        let comment: TimedComment
        let lane: Int
        var id: Int { comment.id }
    }

    @State private var flying: [Flying] = []
    @State private var shown: Set<Int> = []
    @State private var lastTime: Double = 0

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                ForEach(flying) { f in
                    FlyingText(text: f.comment.text, width: geo.size.width)
                        .offset(y: 56 + CGFloat(f.lane) * 34)
                }
            }
        }
        .allowsHitTesting(false)
        .onChange(of: Int(time * 2)) { _ in tick() }
    }

    private func tick() {
        // Jumped back (seek): the same comments may fly again.
        if time < lastTime - 3 { shown = [] }
        lastTime = time
        for c in comments where c.time <= time && c.time > time - 1.5 && !shown.contains(c.id) {
            shown.insert(c.id)
            let busy = Set(flying.map(\.lane))
            let lane = (0..<4).first { !busy.contains($0) } ?? Int.random(in: 0..<4)
            flying.append(Flying(comment: c, lane: lane))
            let id = c.id
            DispatchQueue.main.asyncAfter(deadline: .now() + 9.5) { flying.removeAll { $0.id == id } }
        }
    }
}

private struct FlyingText: View {
    let text: String
    let width: CGFloat
    @State private var x: CGFloat = 0
    @State private var size: CGFloat = 0

    var body: some View {
        Text(text)
            .font(.system(size: 17, weight: .bold))
            .foregroundStyle(.white)
            .shadow(color: .black, radius: 0, x: 1, y: 1)
            .shadow(color: .black.opacity(0.8), radius: 2)
            .lineLimit(1)
            .fixedSize()
            .background(GeometryReader { g in Color.clear.onAppear { size = g.size.width } })
            .offset(x: x)
            .onAppear {
                x = width
                withAnimation(.linear(duration: 9)) { x = -max(size, 300) - 40 }
            }
    }
}

enum DanmakuPrefs {
    static var enabled: Bool {
        get { UserDefaults.standard.object(forKey: "player.danmaku") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "player.danmaku") }
    }
}

// MARK: - "Кто это?"

struct WhoIsThisView: View {
    let frame: UIImage
    @State private var matches: [CharacterMatch] = []
    @State private var loading = true
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Image(uiImage: frame).resizable().scaledToFit()
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .listRowInsets(EdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 8))
                if loading {
                    HStack { Spacer(); ProgressView("Узнаём персонажей…"); Spacer() }
                } else if matches.isEmpty {
                    Text("Не удалось узнать. Поставьте паузу на кадре, где крупно видно лицо.").foregroundStyle(.secondary)
                }
                ForEach(matches) { m in
                    Section(m.work) {
                        ForEach(m.characters.prefix(4), id: \.self) { name in
                            HStack {
                                Image(systemName: "person.crop.circle").foregroundStyle(Theme.accent)
                                Text(name).font(.headline)
                                Spacer()
                                Link(destination: URL(string: "https://shikimori.io/characters?search=\(name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "")")!) {
                                    Text("Shikimori").font(.caption.weight(.semibold))
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Кто это?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } } }
            .task {
                matches = await AnimeTraceAPI.search(frame)
                loading = false
            }
        }
        .preferredColorScheme(.dark)
    }
}

// MARK: - Sharper picture

enum VideoEnhancer {
    static var enabled: Bool {
        get { UserDefaults.standard.bool(forKey: "player.enhance") }
        set { UserDefaults.standard.set(newValue, forKey: "player.enhance") }
    }

    /// Upscales low resolutions (Lanczos, up to 1080p) and sharpens line art — a light-weight
    /// take on Anime4K done with Core Image on the GPU. Works on MP4 streams and downloads.
    /// Installs the frame pipeline: sharpening/upscale when "Улучшение чёткости" is on, plus the colour
    /// tuning (read for every frame, so sliders work live).
    static func apply(to item: AVPlayerItem) async {
        let asset = item.asset
        guard let track = try? await asset.loadTracks(withMediaType: .video).first,
              let natural = try? await track.load(.naturalSize), natural.height > 0 else { return }
        let sharpen = enabled
        let scale = sharpen ? min(2, max(1, 1080 / abs(natural.height))) : 1
        guard let comp = try? await AVMutableVideoComposition.videoComposition(with: asset, applyingCIFiltersWithHandler: { request in
            var img = request.sourceImage.clampedToExtent()
            if scale > 1.05 {
                img = img.applyingFilter("CILanczosScaleTransform", parameters: ["inputScale": scale, "inputAspectRatio": 1])
            }
            if sharpen {
                img = img.applyingFilter("CIUnsharpMask", parameters: ["inputRadius": 1.6 * scale, "inputIntensity": 0.55])
                    .applyingFilter("CIColorControls", parameters: ["inputSaturation": 1.06, "inputContrast": 1.03])
            }
            let t = VideoProcessing.live
            if !t.isNeutral {
                img = img.applyingFilter("CIColorControls", parameters: [kCIInputSaturationKey: t.saturation,
                                                                         kCIInputBrightnessKey: t.brightness,
                                                                         kCIInputContrastKey: t.contrast])
                if t.vibrance > 0.01 { img = img.applyingFilter("CIVibrance", parameters: ["inputAmount": t.vibrance]) }
            }
            let size = request.renderSize
            request.finish(with: img.cropped(to: CGRect(origin: .zero, size: size)), context: nil)
        }) else { return }
        let w = abs(natural.width) * scale, h = abs(natural.height) * scale
        comp.renderSize = CGSize(width: (w / 2).rounded() * 2, height: (h / 2).rounded() * 2)
        await MainActor.run { item.videoComposition = comp }
        DebugLog.log(String(format: "player: picture pipeline %.0fp -> %.0fp (sharpen %@)", abs(natural.height), h, sharpen ? "on" : "off"))
    }
}

// MARK: - Subtitle translation (iOS 18, on device)

struct SubtitleTranslation: ViewModifier {
    @ObservedObject var c: PlayerController

    func body(content: Content) -> some View {
        #if canImport(Translation)
        if #available(iOS 18.0, *) {
            content.modifier(SubtitleTranslation18(c: c))
        } else {
            content
        }
        #else
        content
        #endif
    }
}

#if canImport(Translation)
@available(iOS 18.0, *)
private struct SubtitleTranslation18: ViewModifier {
    @ObservedObject var c: PlayerController
    @State private var config: TranslationSession.Configuration?

    func body(content: Content) -> some View {
        content
            .onChange(of: c.translateRequest) { n in
                guard n > 0 else { return }
                if config == nil {
                    config = TranslationSession.Configuration(source: nil, target: Locale.Language(identifier: "ru"))
                } else {
                    config?.invalidate()
                }
            }
            .translationTask(config) { session in
                let texts = await MainActor.run { c.cueTexts() }
                guard !texts.isEmpty else { return }
                await MainActor.run { c.notice = "Переводим \(texts.count) реплик…" }
                let requests = texts.enumerated().map { TranslationSession.Request(sourceText: $0.element, clientIdentifier: String($0.offset)) }
                do {
                    let responses = try await session.translations(from: requests)
                    var out = texts
                    for r in responses { if let i = r.clientIdentifier.flatMap(Int.init), out.indices.contains(i) { out[i] = r.targetText } }
                    await MainActor.run {
                        c.applyTranslation(out)
                        c.notice = "Субтитры переведены на русский"
                    }
                } catch {
                    await MainActor.run { c.notice = "Перевод не удался: \(error.localizedDescription)" }
                }
            }
    }
}
#endif

enum TranslationSupport {
    static var available: Bool {
        if #available(iOS 18.0, *) {
            #if canImport(Translation)
            return true
            #else
            return false
            #endif
        }
        return false
    }
}
