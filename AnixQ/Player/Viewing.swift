import SwiftUI

/// "Без спойлеров": names of episodes you haven't watched are hidden (they often give the plot away).
enum Spoilers {
    static func title(_ ep: LibEpisode, watched: Bool) -> String {
        PlayerPrefs.noSpoilers && !watched ? "Серия \(ep.number)" : ep.displayTitle
    }
}

/// Marathon: after a few episodes in a row, a gentle stop instead of the next one.
struct MarathonBreakCard: View {
    @ObservedObject var c: PlayerController

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "cup.and.saucer.fill").font(.system(size: 34)).foregroundStyle(.white)
            Text("Уже \(PlayerController.marathonBreakAfter) серии подряд").font(.title3.weight(.bold))
            Text("Может, перерыв? Глазам и спине тоже нужен отдых.")
                .font(.subheadline).foregroundStyle(.white.opacity(0.75)).multilineTextAlignment(.center)
            HStack(spacing: 10) {
                Button { c.breakPrompt = false } label: {
                    Text("Перерыв").font(.subheadline.weight(.semibold)).frame(maxWidth: .infinity, minHeight: 44)
                        .background(.white.opacity(0.15), in: Capsule())
                }
                Button { c.breakPrompt = false; c.next() } label: {
                    Label("Ещё одну", systemImage: "play.fill").font(.subheadline.weight(.bold)).frame(maxWidth: .infinity, minHeight: 44)
                        .background(.white, in: Capsule()).foregroundStyle(.black)
                }
            }
        }
        .foregroundStyle(.white)
        .padding(22)
        .frame(maxWidth: 380)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .environment(\.colorScheme, .dark)
        .padding()
    }
}
