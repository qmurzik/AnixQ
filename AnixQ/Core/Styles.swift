import SwiftUI
import UIKit

/// Whole-app look, chosen in Settings → Оформление.
enum AppStyle: String, CaseIterable, Identifiable {
    case classic, glass, material, oled

    var id: String { rawValue }
    static let storageKey = "uiStyle"
    static var current: AppStyle {
        AppStyle(rawValue: UserDefaults.standard.string(forKey: storageKey) ?? "") ?? .classic
    }

    var title: String {
        switch self {
        case .classic: return "Классика"
        case .glass: return "Liquid Glass"
        case .material: return "Material You"
        case .oled: return "OLED"
        }
    }

    var subtitle: String {
        switch self {
        case .classic: return "Как приложение Apple TV"
        case .glass: return "Стекло и глубина"
        case .material: return "Мягкие тона от акцента"
        case .oled: return "Чистый чёрный, экономит заряд"
        }
    }

    struct Palette {
        let background: Color
        let card: Color
        let cardHighlight: Color
        let stroke: Color
    }

    var palette: Palette {
        let accent = UIColor(AccentChoice.current.color)
        switch self {
        case .classic:
            return Palette(background: Color(.systemBackground), card: Color(.secondarySystemBackground),
                           cardHighlight: Color(.tertiarySystemBackground), stroke: Color.primary.opacity(0.08))
        case .glass:
            return Palette(background: Color(red: 0.035, green: 0.035, blue: 0.07),
                           card: Color.white.opacity(0.07), cardHighlight: Color.white.opacity(0.12),
                           stroke: Color.white.opacity(0.16))
        case .material:
            // Material 3 dark tonal surfaces tinted by the accent ("dynamic colour").
            return Palette(background: Self.mix(UIColor(white: 0.07, alpha: 1), accent, 0.05),
                           card: Self.mix(UIColor(white: 0.12, alpha: 1), accent, 0.11),
                           cardHighlight: Self.mix(UIColor(white: 0.17, alpha: 1), accent, 0.17),
                           stroke: .clear)
        case .oled:
            return Palette(background: .black, card: Color(white: 0.07), cardHighlight: Color(white: 0.11),
                           stroke: Color.white.opacity(0.12))
        }
    }

    var radius: CGFloat {
        switch self {
        case .classic: return 10
        case .glass: return 16
        case .material: return 18
        case .oled: return 8
        }
    }

    var cardRadius: CGFloat {
        switch self {
        case .classic: return 14
        case .glass: return 22
        case .material: return 24
        case .oled: return 10
        }
    }

    var sectionFont: Font {
        switch self {
        case .classic, .glass: return .title2.bold()
        case .material: return .system(.title2, design: .rounded).weight(.semibold)
        case .oled: return .title3.weight(.heavy)
        }
    }

    var primaryButtonText: Color {
        switch self {
        case .classic, .oled: return .black
        case .glass: return .white
        case .material: return Self.onAccent
        }
    }

    /// Text colour on top of a filled accent (dark on light accents such as yellow or mint).
    static var onAccent: Color {
        var w: CGFloat = 0
        UIColor(AccentChoice.current.color).getWhite(&w, alpha: nil)
        return w > 0.62 ? Color.black.opacity(0.85) : .white
    }

    static func mix(_ base: UIColor, _ tint: UIColor, _ t: CGFloat) -> Color {
        var (r1, g1, b1, a1): (CGFloat, CGFloat, CGFloat, CGFloat) = (0, 0, 0, 0)
        var (r2, g2, b2, a2): (CGFloat, CGFloat, CGFloat, CGFloat) = (0, 0, 0, 0)
        base.getRed(&r1, green: &g1, blue: &b1, alpha: &a1)
        tint.getRed(&r2, green: &g2, blue: &b2, alpha: &a2)
        return Color(red: r1 + (r2 - r1) * t, green: g1 + (g2 - g1) * t, blue: b1 + (b2 - b1) * t)
    }
}

/// How prominent a surface is.
enum SurfaceLevel { case card, control, chip }

extension View {
    /// Background for cards, chips and secondary buttons in the current style.
    @ViewBuilder
    func surface<S: Shape>(_ shape: S, level: SurfaceLevel = .card) -> some View {
        switch AppStyle.current {
        case .classic:
            switch level {
            case .card: background(Theme.card, in: shape)
            case .control, .chip: background(.ultraThinMaterial, in: shape)
            }
        case .glass:
            glass(shape, interactive: level == .control)
        case .material:
            background(level == .card ? Theme.card : Theme.cardHighlight, in: shape)
        case .oled:
            switch level {
            case .card: background(Theme.card, in: shape).overlay(shape.stroke(Theme.stroke, lineWidth: 0.8))
            case .control: background(Color.black.opacity(0.55), in: shape).overlay(shape.stroke(Theme.stroke, lineWidth: 1))
            case .chip: background(Color.black.opacity(0.75), in: shape)
            }
        }
    }

    /// Liquid Glass: the real system material on iOS 26, a hand-made imitation before that.
    @ViewBuilder
    func glass<S: Shape>(_ shape: S, tint: Color? = nil, interactive: Bool = false) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            glassEffect(Glass.regular.tint(tint).interactive(interactive), in: shape)
        } else {
            modifier(GlassFallback(shape: shape, tint: tint))
        }
        #else
        modifier(GlassFallback(shape: shape, tint: tint))
        #endif
    }

    /// Screen background for the current style (gradient glow for glass, tonal for Material).
    func styledBackground() -> some View {
        background(StyledBackground().ignoresSafeArea())
    }

    /// Lists and forms: let the style background show through.
    @ViewBuilder
    func styledList() -> some View {
        if AppStyle.current == .classic {
            self
        } else {
            scrollContentBackground(.hidden).styledBackground()
        }
    }
}

private struct GlassFallback<S: Shape>: ViewModifier {
    let shape: S
    let tint: Color?

    func body(content: Content) -> some View {
        content
            .background {
                ZStack {
                    shape.fill(.ultraThinMaterial)
                    if let tint { shape.fill(tint.opacity(0.6)) }
                    shape.fill(LinearGradient(colors: [.white.opacity(0.14), .white.opacity(0.02)],
                                              startPoint: .top, endPoint: .bottom))
                }
                .shadow(color: .black.opacity(0.28), radius: 14, y: 8)
            }
            .overlay {
                shape.stroke(LinearGradient(colors: [.white.opacity(0.45), .white.opacity(0.06), .white.opacity(0.18)],
                                            startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 0.9)
            }
    }
}

struct StyledBackground: View {
    var body: some View {
        let style = AppStyle.current
        ZStack {
            style.palette.background
            if style == .glass {
                // Soft coloured light behind the glass so it has something to refract.
                GeometryReader { geo in
                    let w = geo.size.width
                    Circle().fill(Theme.accent.opacity(0.32)).frame(width: w * 1.1)
                        .blur(radius: 90).offset(x: -w * 0.45, y: geo.size.height * 0.28)
                    Circle().fill(Color.indigo.opacity(0.35)).frame(width: w)
                        .blur(radius: 100).offset(x: w * 0.4, y: geo.size.height * 0.62)
                    Circle().fill(Color.cyan.opacity(0.14)).frame(width: w * 0.8)
                        .blur(radius: 90).offset(x: w * 0.1, y: geo.size.height * 0.95)
                }
            }
        }
    }
}

// MARK: - Picker preview

/// Mini mock-up of the home screen in a given style, for the settings picker.
struct StylePreview: View {
    let style: AppStyle
    let selected: Bool

    var body: some View {
        let p = style.palette
        VStack(spacing: 8) {
            ZStack(alignment: .bottom) {
                p.background
                if style == .glass {
                    Circle().fill(Theme.accent.opacity(0.5)).frame(width: 90).blur(radius: 26).offset(x: -30, y: -10)
                    Circle().fill(Color.indigo.opacity(0.6)).frame(width: 80).blur(radius: 26).offset(x: 34, y: -60)
                }
                VStack(alignment: .leading, spacing: 6) {
                    RoundedRectangle(cornerRadius: style.radius * 0.5)
                        .fill(LinearGradient(colors: [Theme.accent, .purple], startPoint: .topLeading, endPoint: .bottomTrailing))
                        .frame(height: 46)
                    HStack(spacing: 5) {
                        ForEach(0..<3) { _ in
                            RoundedRectangle(cornerRadius: style.radius * 0.45)
                                .fill(p.cardHighlight)
                                .overlay(RoundedRectangle(cornerRadius: style.radius * 0.45).stroke(p.stroke, lineWidth: 0.6))
                                .frame(height: 40)
                        }
                    }
                    previewButton
                    Spacer(minLength: 0)
                }
                .padding(8)
                // Tab bar
                HStack {
                    ForEach(0..<4) { i in
                        Circle().fill(i == 0 ? Theme.accent : Color.white.opacity(0.35)).frame(width: 7, height: 7)
                            .frame(maxWidth: .infinity)
                    }
                }
                .frame(height: 20)
                .background(tabBackground)
                .clipShape(style == .glass ? AnyShape(Capsule()) : AnyShape(Rectangle()))
                .padding(style == .glass ? 6 : 0)
            }
            .frame(width: 104, height: 150)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(selected ? Theme.accent : Color.white.opacity(0.12), lineWidth: selected ? 3 : 1))
            VStack(spacing: 1) {
                Text(style.title).font(.caption.weight(.semibold)).foregroundStyle(.primary)
                Text(style.subtitle).font(.system(size: 9)).foregroundStyle(.secondary)
                    .multilineTextAlignment(.center).lineLimit(2).frame(width: 104)
            }
        }
    }

    @ViewBuilder private var previewButton: some View {
        let shape = RoundedRectangle(cornerRadius: style == .material || style == .glass ? 9 : 4)
        switch style {
        case .classic, .oled: shape.fill(.white).frame(height: 18)
        case .material: shape.fill(Theme.accent).frame(height: 18)
        case .glass: shape.fill(.white.opacity(0.18)).overlay(shape.stroke(.white.opacity(0.4), lineWidth: 0.7)).frame(height: 18)
        }
    }

    @ViewBuilder private var tabBackground: some View {
        switch style {
        case .classic: Color(white: 0.1)
        case .glass: Color.white.opacity(0.14)
        case .material: style.palette.cardHighlight
        case .oled: Color.black
        }
    }
}
