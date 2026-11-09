import AVFoundation
import CoreImage
import MediaToolbox
import SwiftUI

// MARK: - Picture: colour tuning (live, per frame)

/// Colour settings applied on the GPU to every frame. Changes take effect on the next frame.
struct PictureTuning: Codable, Equatable {
    var saturation: Double = 1
    /// "Smart" saturation: lifts dull colours more than already-vivid ones and protects skin tones.
    var vibrance: Double = 0
    var brightness: Double = 0
    var contrast: Double = 1

    var isNeutral: Bool {
        abs(saturation - 1) < 0.01 && abs(vibrance) < 0.01 && abs(brightness) < 0.005 && abs(contrast - 1) < 0.01
    }

    static let presets: [(name: String, value: PictureTuning)] = [
        ("Как есть", PictureTuning()),
        ("Сочно", PictureTuning(saturation: 1.15, vibrance: 0.55, brightness: 0, contrast: 1.05)),
        ("Ярко", PictureTuning(saturation: 1.1, vibrance: 0.3, brightness: 0.04, contrast: 1.08)),
        ("Кино", PictureTuning(saturation: 0.95, vibrance: 0.15, brightness: -0.02, contrast: 1.12)),
        ("Мягко", PictureTuning(saturation: 0.92, vibrance: 0, brightness: 0.02, contrast: 0.94)),
    ]

    static var stored: PictureTuning {
        guard let d = UserDefaults.standard.data(forKey: "player.picture"),
              let t = try? JSONDecoder().decode(PictureTuning.self, from: d) else { return PictureTuning() }
        return t
    }

    static func save(_ t: PictureTuning) {
        if let d = try? JSONEncoder().encode(t) { UserDefaults.standard.set(d, forKey: "player.picture") }
    }
}

/// The tuning the render thread reads for each frame.
enum VideoProcessing {
    private static let lock = NSLock()
    private static var value = PictureTuning.stored

    static var live: PictureTuning {
        get { lock.lock(); defer { lock.unlock() }; return value }
        set { lock.lock(); value = newValue; lock.unlock() }
    }
}

// MARK: - Sound: smart enhancement (audio processing tap)

enum SoundMode: String, CaseIterable, Identifiable {
    case off, smart, voice, night
    var id: String { rawValue }

    var title: String {
        switch self {
        case .off: return "Выключено"
        case .smart: return "Умное улучшение"
        case .voice: return "Чёткие голоса"
        case .night: return "Ночной режим"
        }
    }

    var subtitle: String {
        switch self {
        case .off: return "Звук как в файле"
        case .smart: return "Ровная громкость, разборчивые голоса, вернёт «воздух» сжатому звуку"
        case .voice: return "Голоса вперёд, музыка и фон тише"
        case .night: return "Тихие сцены громче, взрывы тише — можно смотреть, не будя соседей"
        }
    }

    var icon: String {
        switch self {
        case .off: return "speaker.wave.1"
        case .smart: return "wand.and.stars"
        case .voice: return "person.wave.2"
        case .night: return "moon.stars"
        }
    }
}

enum SoundEnhancer {
    private static let lock = NSLock()
    private static var value = SoundMode(rawValue: UserDefaults.standard.string(forKey: "player.sound") ?? "") ?? .off

    static var mode: SoundMode {
        get { lock.lock(); defer { lock.unlock() }; return value }
        set {
            lock.lock(); value = newValue; lock.unlock()
            UserDefaults.standard.set(newValue.rawValue, forKey: "player.sound")
        }
    }

    /// Puts the processing tap on the item's audio. Works on MP4 streams, downloads and the audio-delay composition.
    static func apply(to item: AVPlayerItem) async {
        guard let track = try? await item.asset.loadTracks(withMediaType: .audio).first else { return }
        let dsp = SoundDSP()
        var callbacks = MTAudioProcessingTapCallbacks(
            version: kMTAudioProcessingTapCallbacksVersion_0,
            clientInfo: UnsafeMutableRawPointer(Unmanaged.passRetained(dsp).toOpaque()),
            init: tapInit, finalize: tapFinalize, prepare: tapPrepare, unprepare: tapUnprepare, process: tapProcess)
        var tap: MTAudioProcessingTap?
        guard MTAudioProcessingTapCreate(kCFAllocatorDefault, &callbacks, kMTAudioProcessingTapCreationFlag_PostEffects, &tap) == noErr,
              let tap else {
            Unmanaged.passUnretained(dsp).release()
            return
        }
        let params = AVMutableAudioMixInputParameters(track: track)
        params.audioTapProcessor = tap
        let mix = AVMutableAudioMix()
        mix.inputParameters = [params]
        await MainActor.run { item.audioMix = mix }
        DebugLog.log("player: sound processing on (\(mode.rawValue))")
    }
}

private let tapInit: MTAudioProcessingTapInitCallback = { _, clientInfo, storageOut in
    storageOut.pointee = clientInfo
}

private let tapFinalize: MTAudioProcessingTapFinalizeCallback = { tap in
    Unmanaged<SoundDSP>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).release()
}

private let tapPrepare: MTAudioProcessingTapPrepareCallback = { tap, _, format in
    let dsp = Unmanaged<SoundDSP>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).takeUnretainedValue()
    let f = format.pointee
    dsp.prepare(rate: f.mSampleRate, channels: Int(f.mChannelsPerFrame),
                usable: f.mFormatFlags & kAudioFormatFlagIsFloat != 0 && f.mFormatFlags & kAudioFormatFlagIsNonInterleaved != 0
                    && f.mBitsPerChannel == 32)
}

private let tapUnprepare: MTAudioProcessingTapUnprepareCallback = { _ in }

private let tapProcess: MTAudioProcessingTapProcessCallback = { tap, frames, _, bufferList, framesOut, flagsOut in
    guard MTAudioProcessingTapGetSourceAudio(tap, frames, bufferList, flagsOut, nil, framesOut) == noErr else { return }
    let dsp = Unmanaged<SoundDSP>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).takeUnretainedValue()
    dsp.process(UnsafeMutableAudioBufferListPointer(bufferList), frames: Int(framesOut.pointee))
}

/// Second-order IIR filter (RBJ audio-EQ cookbook).
struct Biquad {
    var b0: Float = 1, b1: Float = 0, b2: Float = 0, a1: Float = 0, a2: Float = 0
    var z1: Float = 0, z2: Float = 0

    mutating func run(_ x: Float) -> Float {
        let y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    private static func make(_ b0: Double, _ b1: Double, _ b2: Double, _ a0: Double, _ a1: Double, _ a2: Double) -> Biquad {
        Biquad(b0: Float(b0 / a0), b1: Float(b1 / a0), b2: Float(b2 / a0), a1: Float(a1 / a0), a2: Float(a2 / a0))
    }

    static func peaking(_ f: Double, q: Double, db: Double, rate: Double) -> Biquad {
        let a = pow(10, db / 40), w = 2 * Double.pi * f / rate, alpha = sin(w) / (2 * q), c = cos(w)
        return make(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
    }

    static func highpass(_ f: Double, q: Double = 0.707, rate: Double) -> Biquad {
        let w = 2 * Double.pi * f / rate, alpha = sin(w) / (2 * q), c = cos(w)
        return make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }

    static func lowShelf(_ f: Double, db: Double, rate: Double) -> Biquad {
        let a = pow(10, db / 40), w = 2 * Double.pi * f / rate, c = cos(w), s = sin(w)
        let alpha = s / 2 * sqrt(2.0), sa = 2 * sqrt(a) * alpha
        return make(a * ((a + 1) - (a - 1) * c + sa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sa),
                    (a + 1) + (a - 1) * c + sa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sa)
    }
}

/// The processing itself. Runs on the audio thread: no allocations or locks in `process` except reading the mode.
final class SoundDSP {
    private var rate: Double = 48_000
    private var channels = 2
    private var usable = false
    private var mode: SoundMode = .off
    private var presence: [Biquad] = []
    private var shelf: [Biquad] = []
    private var exciteIn: [Biquad] = []
    private var exciteOut: [Biquad] = []
    private var exciteMix: Float = 0
    private var sideGain: Float = 1
    private var targetRMS: Float = 0.12
    private var maxGain: Float = 3
    private var level: Float = 0.1
    private var gain: Float = 1

    func prepare(rate: Double, channels: Int, usable: Bool) {
        self.rate = rate > 0 ? rate : 48_000
        self.channels = max(1, channels)
        self.usable = usable
        configure(SoundEnhancer.mode)
    }

    private func configure(_ m: SoundMode) {
        mode = m
        let (presenceDb, shelfDb, excite, side, target, maxG): (Double, Double, Float, Float, Float, Float) = {
            switch m {
            case .off: return (0, 0, 0, 1, 0.12, 1)
            case .smart: return (2.5, 1.5, 0.12, 1, 0.12, 3)
            case .voice: return (5, -3, 0.06, 0.6, 0.12, 3)
            case .night: return (3, -4, 0.05, 0.85, 0.11, 6)
            }
        }()
        presence = Array(repeating: .peaking(2800, q: 0.9, db: presenceDb, rate: rate), count: channels)
        shelf = Array(repeating: .lowShelf(120, db: shelfDb, rate: rate), count: channels)
        exciteIn = Array(repeating: .highpass(min(5000, rate * 0.2), rate: rate), count: channels)
        exciteOut = Array(repeating: .highpass(min(6500, rate * 0.25), rate: rate), count: channels)
        exciteMix = excite
        sideGain = side
        targetRMS = target
        maxGain = maxG
    }

    func process(_ abl: UnsafeMutableAudioBufferListPointer, frames: Int) {
        guard usable, frames > 0 else { return }
        let m = SoundEnhancer.mode
        if m != mode { configure(m) }
        if mode == .off { return }
        let n = min(abl.count, channels)
        var bufs: [UnsafeMutablePointer<Float>] = []
        bufs.reserveCapacity(n)
        for i in 0..<n {
            guard let p = abl[i].mData?.assumingMemoryBound(to: Float.self) else { return }
            bufs.append(p)
        }

        // Stereo: pull the centre (voices) forward by lowering the sides.
        if n == 2 && sideGain < 0.999 {
            let l = bufs[0], r = bufs[1]
            for f in 0..<frames {
                let mid = (l[f] + r[f]) * 0.5, side = (l[f] - r[f]) * 0.5 * sideGain
                l[f] = mid + side
                r[f] = mid - side
            }
        }

        // EQ and the exciter (synthesises the top octave that lossy compression cut off).
        var sum: Float = 0
        for c in 0..<n {
            let p = bufs[c]
            for f in 0..<frames {
                var x = shelf[c].run(presence[c].run(p[f]))
                if exciteMix > 0 {
                    let h = exciteIn[c].run(x)
                    x += exciteOut[c].run(tanhf(h * 4)) * exciteMix
                }
                p[f] = x
                sum += x * x
            }
        }

        // Automatic level: quiet dubs and loud ones end up equally loud. Slow, so music keeps its dynamics;
        // night mode reacts faster and allows more gain for whispers.
        let rms = sqrtf(sum / Float(max(1, frames * n)))
        let blockSeconds = Float(frames) / Float(rate)
        let attack: Float = mode == .night ? 0.05 : 0.3, release: Float = mode == .night ? 0.6 : 2.5
        let k = 1 - expf(-blockSeconds / (rms > level ? attack : release))
        level += (rms - level) * k
        // Silence isn't amplified.
        let wanted = level > 0.003 ? min(maxGain, max(0.5, targetRMS / level)) : gain
        gain += (wanted - gain) * (1 - expf(-blockSeconds / 0.4))

        // Gain + soft limiter (no clipping on peaks).
        for c in 0..<n {
            let p = bufs[c]
            for f in 0..<frames {
                let y = p[f] * gain
                p[f] = abs(y) < 0.6 ? y : (y > 0 ? 1 : -1) * (0.6 + 0.4 * tanhf((abs(y) - 0.6) / 0.4))
            }
        }
    }
}

// MARK: - Panel

struct PictureSoundPanel: View {
    let c: PlayerController
    @State private var t = PictureTuning.stored
    @State private var sound = SoundEnhancer.mode

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Картинка").font(.headline)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(PictureTuning.presets, id: \.name) { p in
                        Button { t = p.value } label: {
                            Text(p.name).font(.subheadline.weight(.semibold))
                                .padding(.horizontal, 12).padding(.vertical, 7)
                                .background(t == p.value ? Color.white : Color.white.opacity(0.12), in: Capsule())
                                .foregroundStyle(t == p.value ? Color.black : Color.white)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            slider("Насыщенность", value: $t.saturation, range: 0.5...1.6, neutral: 1)
            slider("Сочность", hint: "умная: поднимает блёклые цвета, не трогает кожу", value: $t.vibrance, range: 0...1, neutral: 0)
            slider("Яркость", value: $t.brightness, range: -0.15...0.15, neutral: 0)
            slider("Контраст", value: $t.contrast, range: 0.8...1.3, neutral: 1)

            Divider().overlay(Color.white.opacity(0.15))
            Text("Звук").font(.headline)
            ForEach(SoundMode.allCases) { m in
                Button { sound = m; c.setSound(m) } label: {
                    HStack(spacing: 12) {
                        Image(systemName: m.icon).frame(width: 26)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(m.title).font(.subheadline.weight(.semibold))
                            Text(m.subtitle).font(.caption).foregroundStyle(.white.opacity(0.55)).fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer(minLength: 0)
                        if sound == m { Image(systemName: "checkmark").font(.subheadline.weight(.bold)).foregroundStyle(Theme.accent) }
                    }
                    .padding(10)
                    .background(sound == m ? Color.white.opacity(0.1) : .clear, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            if !c.isNative {
                Text("Работает в плеере AnimeLib. В Kodik картинку и звук настроить нельзя.")
                    .font(.caption).foregroundStyle(.white.opacity(0.55))
            }
        }
        .padding()
        .onChange(of: t) { c.setPicture($0) }
    }

    private func slider(_ title: String, hint: String? = nil, value: Binding<Double>, range: ClosedRange<Double>, neutral: Double) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title).font(.subheadline)
                if let hint { Text(hint).font(.caption2).foregroundStyle(.white.opacity(0.45)).lineLimit(1) }
                Spacer()
                let pct = Int(((value.wrappedValue - neutral) / max(abs(range.upperBound - neutral), abs(range.lowerBound - neutral))) * 100)
                Text(pct == 0 ? "0" : String(format: "%+d", pct)).font(.caption.monospacedDigit()).foregroundStyle(.white.opacity(0.6))
                    .onTapGesture { value.wrappedValue = neutral }
            }
            Slider(value: value, in: range).tint(Theme.accent)
        }
    }
}
