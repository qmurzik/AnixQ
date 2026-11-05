import SwiftUI
import UIKit

/// Native iOS look: system materials and semantic colours, one accent, SF typography.
enum Theme {
    /// User-selectable accent (Settings → Оформление).
    static var accent: Color { AccentChoice.current.color }
    static let background = Color(.systemBackground)
    static let groupedBackground = Color(.systemGroupedBackground)
    static let card = Color(.secondarySystemBackground)
    static let cardHighlight = Color(.tertiarySystemBackground)
    static let secondaryText = Color.secondary
    static let stroke = Color.primary.opacity(0.08)

    static func scoreColor(_ score: Double) -> Color {
        switch score {
        case 8...: return .green
        case 6.5..<8: return .orange
        default: return .red
        }
    }

    static func configureAppearance() {
        UITabBar.appearance().scrollEdgeAppearance = {
            let a = UITabBarAppearance()
            a.configureWithDefaultBackground()
            return a
        }()
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
            Text(title).font(.title2.bold())
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
        .background(.ultraThinMaterial, in: Capsule())
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

/// White capsule "Play" button as in the TV app.
struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(.black)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(.white, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .opacity(configuration.isPressed ? 0.8 : 1)
    }
}

struct GlassButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(.primary)
            .frame(minWidth: 50, minHeight: 50)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
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
    func cardStyle(radius: CGFloat = 14) -> some View {
        background(Theme.card, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}
