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

    /// Material 3 dark tonal roles derived from the accent hue (pastel primary, quiet tinted surfaces).
    struct Tones {
        let primary, onPrimary, secondaryContainer, onSecondaryContainer: Color
        let surface, surfaceContainer, surfaceContainerHigh, outline: Color
    }

    static var tones: Tones {
        var h: CGFloat = 0, sat: CGFloat = 0, b: CGFloat = 0
        UIColor(AccentChoice.current.color).getHue(&h, saturation: &sat, brightness: &b, alpha: nil)
        func t(_ s: CGFloat, _ v: CGFloat) -> Color { Color(hue: Double(h), saturation: Double(s), brightness: Double(v)) }
        // Dark roles first, light roles second (Material 3 tonal palette approximated in HSB).
        return Tones(primary: dyn(t(0.34, 0.97), t(0.72, 0.52)), onPrimary: dyn(t(0.80, 0.24), .white),
                     secondaryContainer: dyn(t(0.28, 0.27), t(0.16, 0.91)),
                     onSecondaryContainer: dyn(t(0.10, 0.94), t(0.70, 0.22)),
                     surface: dyn(t(0.10, 0.065), t(0.03, 0.99)), surfaceContainer: dyn(t(0.10, 0.115), t(0.06, 0.955)),
                     surfaceContainerHigh: dyn(t(0.10, 0.155), t(0.08, 0.925)), outline: dyn(t(0.10, 0.30), t(0.10, 0.75)))
    }

    /// A colour that follows light / dark mode.
    static func dyn(_ dark: Color, _ light: Color) -> Color {
        let d = UIColor(dark), l = UIColor(light)
        return Color(UIColor { $0.userInterfaceStyle == .dark ? d : l })
    }

    var palette: Palette {
        switch self {
        case .classic:
            return Palette(background: Color(.systemBackground), card: Color(.secondarySystemBackground),
                           cardHighlight: Color(.tertiarySystemBackground), stroke: Color.primary.opacity(0.08))
        case .glass:
            return Palette(background: Self.dyn(Color(red: 0.03, green: 0.03, blue: 0.045), Color(red: 0.94, green: 0.94, blue: 0.96)),
                           card: Self.dyn(Color.white.opacity(0.055), Color.white.opacity(0.72)),
                           cardHighlight: Self.dyn(Color.white.opacity(0.09), Color.white.opacity(0.9)),
                           stroke: Self.dyn(Color.white.opacity(0.09), Color.black.opacity(0.06)))
        case .material:
            let t = Self.tones
            return Palette(background: t.surface, card: t.surfaceContainer, cardHighlight: t.surfaceContainerHigh,
                           stroke: .clear)
        case .oled:
            return Palette(background: .black, card: Color(white: 0.06), cardHighlight: Color(white: 0.1),
                           stroke: Color.white.opacity(0.11))
        }
    }

    /// App-wide tint: Material uses the pastel primary tone instead of the raw accent.
    var tint: Color { self == .material ? Self.tones.primary : AccentChoice.current.color }

    var radius: CGFloat {
        switch self {
        case .classic: return 10
        case .glass: return 14
        case .material: return 14
        case .oled: return 8
        }
    }

    var cardRadius: CGFloat {
        switch self {
        case .classic: return 14
        case .glass: return 20
        case .material: return 20
        case .oled: return 10
        }
    }

    var sectionFont: Font {
        switch self {
        case .classic, .glass: return .title2.bold()
        case .material: return .system(size: 22, weight: .medium, design: .rounded)
        case .oled: return .title3.weight(.heavy)
        }
    }

    var primaryButtonText: Color {
        switch self {
        case .classic: return Self.dyn(.black, .white)
        case .oled: return .black
        case .glass: return .white
        case .material: return Self.tones.onPrimary
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
            switch level {
            // Content stays calm; glass is reserved for the control layer, as in iOS 26.
            case .card:
                background(Theme.card, in: shape)
                    .overlay(shape.stroke(Theme.stroke, lineWidth: 0.5))
            case .control: glass(shape, interactive: true)
            case .chip: glass(shape)
            }
        case .material:
            let t = AppStyle.tones
            switch level {
            case .card: background(t.surfaceContainer, in: shape)
            case .control, .chip: background(t.secondaryContainer, in: shape).foregroundStyle(t.onSecondaryContainer)
            }
        case .oled:
            switch level {
            case .card: background(Theme.card, in: shape).overlay(shape.stroke(Theme.stroke, lineWidth: 0.6))
            case .control: background(Color.black.opacity(0.6), in: shape).overlay(shape.stroke(Color.white.opacity(0.22), lineWidth: 0.8))
            case .chip: background(Color.black.opacity(0.75), in: shape)
            }
        }
    }

    /// Liquid Glass: the real system material on iOS 26, a restrained imitation before that.
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
        if AppStyle.current == .classic || AppStyle.current == .glass {
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
                    if let tint {
                        shape.fill(tint)
                    } else {
                        shape.fill(.ultraThinMaterial)
                        shape.fill(Color.white.opacity(0.04))
                    }
                    // Soft specular highlight along the top edge.
                    shape.fill(LinearGradient(colors: [.white.opacity(tint == nil ? 0.10 : 0.18), .clear],
                                              startPoint: .top, endPoint: .center))
                }
            }
            .overlay {
                shape.stroke(LinearGradient(colors: [.white.opacity(0.28), .white.opacity(0.04), .white.opacity(0.10)],
                                            startPoint: .top, endPoint: .bottom), lineWidth: 0.6)
            }
    }
}

struct StyledBackground: View {
    var body: some View {
        let style = AppStyle.current
        ZStack {
            style.palette.background
            if style == .glass {
                // Faint ambient light low on the screen only, so nothing seams against hero images at the top.
                RadialGradient(colors: [Theme.accent.opacity(0.09), .clear], center: UnitPoint(x: 0.1, y: 1.05),
                               startRadius: 0, endRadius: 460)
                RadialGradient(colors: [Color.indigo.opacity(0.10), .clear], center: UnitPoint(x: 0.95, y: 1.0),
                               startRadius: 0, endRadius: 420)
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
                    RadialGradient(colors: [Theme.accent.opacity(0.35), .clear], center: .leading, startRadius: 0, endRadius: 90)
                    RadialGradient(colors: [Color.indigo.opacity(0.4), .clear], center: .bottomTrailing, startRadius: 0, endRadius: 100)
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
                        Circle().fill(i == 0 ? style.tint : Color.white.opacity(0.35)).frame(width: 7, height: 7)
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
        case .material: shape.fill(AppStyle.tones.primary).frame(height: 18)
        case .glass: shape.fill(.white.opacity(0.18)).overlay(shape.stroke(.white.opacity(0.4), lineWidth: 0.7)).frame(height: 18)
        }
    }

    @ViewBuilder private var tabBackground: some View {
        switch style {
        case .classic: Color(white: 0.1)
        case .glass: Color.white.opacity(0.14)
        case .material: AppStyle.tones.surfaceContainer
        case .oled: Color.black
        }
    }
}
