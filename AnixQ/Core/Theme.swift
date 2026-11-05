import SwiftUI
import UIKit

/// Native iOS look: system materials and semantic colours, one accent, SF typography.
enum Theme {
    /// User-selectable accent (Settings → Оформление).
    static var accent: Color { AppStyle.current.tint }
    static var style: AppStyle { AppStyle.current }
    static var background: Color { style.palette.background }
    static var groupedBackground: Color { style.palette.background }
    static var card: Color { style.palette.card }
    static var cardHighlight: Color { style.palette.cardHighlight }
    static let secondaryText = Color.secondary
    static var stroke: Color { style.palette.stroke }
    /// Corner radius of posters and cards for the current style.
    static var radius: CGFloat { style.radius }

    static func scoreColor(_ score: Double) -> Color {
        switch score {
        case 8...: return .green
        case 6.5..<8: return .orange
        default: return .red
        }
    }

    static func configureAppearance() {
        let style = AppStyle.current
        let tab = UITabBarAppearance()
        let nav = UINavigationBarAppearance()
        switch style {
        case .classic:
            tab.configureWithDefaultBackground()
            nav.configureWithDefaultBackground()
        case .glass:
            tab.configureWithTransparentBackground()
            tab.backgroundEffect = UIBlurEffect(style: .systemUltraThinMaterialDark)
            tab.shadowColor = UIColor.white.withAlphaComponent(0.06)
            nav.configureWithTransparentBackground()
            nav.backgroundEffect = UIBlurEffect(style: .systemUltraThinMaterialDark)
        case .material:
            let t = AppStyle.tones
            tab.configureWithOpaqueBackground()
            tab.backgroundColor = UIColor(t.surfaceContainer)
            tab.shadowColor = .clear
            for item in [tab.stackedLayoutAppearance, tab.inlineLayoutAppearance, tab.compactInlineLayoutAppearance] {
                item.selected.iconColor = UIColor(t.primary)
                item.selected.titleTextAttributes = [.foregroundColor: UIColor(t.primary)]
                item.normal.iconColor = UIColor(white: 0.62, alpha: 1)
                item.normal.titleTextAttributes = [.foregroundColor: UIColor(white: 0.62, alpha: 1)]
            }
            nav.configureWithOpaqueBackground()
            nav.backgroundColor = UIColor(t.surface)
            nav.shadowColor = .clear
        case .oled:
            tab.configureWithOpaqueBackground()
            tab.backgroundColor = .black
            tab.shadowColor = UIColor.white.withAlphaComponent(0.12)
            nav.configureWithOpaqueBackground()
            nav.backgroundColor = .black
            nav.shadowColor = UIColor.white.withAlphaComponent(0.12)
        }
        // On iOS 26 the system bars are Liquid Glass already; keep them for the glass style.
        if style == .glass, #available(iOS 26.0, *) { return }
        UITabBar.appearance().standardAppearance = tab
        UITabBar.appearance().scrollEdgeAppearance = tab
        UINavigationBar.appearance().standardAppearance = nav
        UINavigationBar.appearance().compactAppearance = nav
    }
}

enum Layout {
    static var isPad: Bool { UIDevice.current.userInterfaceIdiom == .pad }
    static var heroHeight: CGFloat { isPad ? 680 : 540 }
    static var headerHeight: CGFloat { isPad ? 620 : 520 }
    /// Readable width for text blocks on iPad.
    static let maxTextWidth: CGFloat = 760
}

enum AccentChoice: String, CaseIterable, Identifiable {
    case rose, red, orange, yellow, green, mint, blue, purple

    var id: String { rawValue }
    static var current: AccentChoice {
        AccentChoice(rawValue: UserDefaults.standard.string(forKey: "accent") ?? "") ?? .rose
    }
    var color: Color {
        switch self {
        case .rose: return Color("AccentColor")
        case .red: return .red
        case .orange: return .orange
        case .yellow: return .yellow
        case .green: return .green
        case .mint: return .mint
        case .blue: return .blue
        case .purple: return .purple
        }
    }
}

enum AppIconChoice: String, CaseIterable, Identifiable {
    case standard = "AppIcon", dark = "AppIcon-Dark", ocean = "AppIcon-Ocean", mint = "AppIcon-Mint", sunset = "AppIcon-Sunset"
    var id: String { rawValue }
    var title: String {
        switch self {
        case .standard: return "Классика"
        case .dark: return "Тёмная"
        case .ocean: return "Океан"
        case .mint: return "Мята"
        case .sunset: return "Закат"
        }
    }
    var preview: String { rawValue + "-Preview" }
    var alternateName: String? { self == .standard ? nil : rawValue }
}

// MARK: - Shared building blocks

struct SectionTitle<Trailing: View>: View {
    let title: String
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title).font(Theme.style.sectionFont)
            Spacer()
            trailing()
        }
        .padding(.horizontal)
    }
}

extension SectionTitle where Trailing == EmptyView {
    init(_ title: String) {
        self.title = title
        self.trailing = { EmptyView() }
    }
}

struct ScoreBadge: View {
    let score: Double

    var body: some View {
        HStack(spacing: 2) {
            Image(systemName: "star.fill").font(.system(size: 9, weight: .bold))
            Text(String(format: "%.1f", score)).font(.system(size: 11, weight: .bold, design: .rounded))
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 3)
        .surface(Capsule(), level: .chip)
        .foregroundStyle(Theme.scoreColor(score))
    }
}

struct PressableStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

/// Main "Play" button: white slab (classic), glass, filled tonal pill (Material You).
struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        let style = Theme.style
        return configuration.label
            .font(style == .material ? .system(.headline, design: .rounded) : .headline)
            .foregroundStyle(style.primaryButtonText)
            .frame(maxWidth: .infinity, minHeight: 50)
            .modifier(PrimaryButtonBackground(style: style))
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

private struct PrimaryButtonBackground: ViewModifier {
    let style: AppStyle
    func body(content: Content) -> some View {
        switch style {
        case .classic, .oled:
            content.background(.white, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        case .material:
            content.background(AppStyle.tones.primary, in: Capsule())
        case .glass:
            content.glass(Capsule(), tint: AccentChoice.current.color.opacity(0.9), interactive: true)
        }
    }
}

/// Secondary square button next to "Play".
struct GlassButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        let style = Theme.style
        return configuration.label
            .font(.headline)
            .foregroundStyle(.primary)
            .frame(minWidth: 50, minHeight: 50)
            .surface(style == .material || style == .glass ? AnyShape(Capsule())
                        : AnyShape(RoundedRectangle(cornerRadius: 12, style: .continuous)), level: .control)
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

struct ErrorState: View {
    let message: String
    let retry: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "wifi.exclamationmark").font(.largeTitle).foregroundStyle(.secondary)
            Text(message).multilineTextAlignment(.center).foregroundStyle(.secondary)
            Button("Повторить", action: retry).buttonStyle(.bordered)
        }
        .padding()
        .frame(maxWidth: .infinity, minHeight: 220)
    }
}

struct EmptyState: View {
    let icon: String
    let title: String
    var subtitle: String? = nil

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: icon).font(.system(size: 44)).foregroundStyle(.tertiary)
            Text(title).font(.headline)
            if let subtitle {
                Text(subtitle).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity, minHeight: 240)
    }
}

extension View {
    func cardStyle(radius: CGFloat? = nil) -> some View {
        surface(RoundedRectangle(cornerRadius: radius ?? Theme.style.cardRadius, style: .continuous), level: .card)
    }
}
