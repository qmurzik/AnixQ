import SwiftUI
import UIKit
import ImageIO

/// Image loader used instead of `AsyncImage`.
///
/// `AsyncImage` cancels its request whenever a cell leaves a lazy container and then stays in the
/// failure state, which made posters disappear while scrolling. This pipeline:
/// - keeps requests alive even if the view disappears (detached tasks, de-duplicated per URL),
/// - caches decoded images in memory and raw responses on disk (URLCache),
/// - retries, and falls back to the `static.anixart.tv` mirror when `*.anixmirai.com` is unreachable.
actor ImagePipeline {
    static let shared = ImagePipeline()

    nonisolated let memory: NSCache<NSURL, UIImage> = {
        let c = NSCache<NSURL, UIImage>()
        c.countLimit = 400
        c.totalCostLimit = 160 * 1024 * 1024
        return c
    }()

    private var inflight: [URL: Task<UIImage?, Never>] = [:]

    static let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.urlCache = URLCache(memoryCapacity: 32 * 1024 * 1024, diskCapacity: 400 * 1024 * 1024,
                                   diskPath: "images")
        config.requestCachePolicy = .returnCacheDataElseLoad
        config.timeoutIntervalForRequest = 20
        config.httpMaximumConnectionsPerHost = 8
        config.httpAdditionalHeaders = [
            "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
            "Accept": "image/webp,image/jpeg,image/png,image/*;q=0.8"
        ]
        return URLSession(configuration: config)
    }()

    nonisolated func cached(_ url: URL) -> UIImage? {
        memory.object(forKey: url as NSURL)
    }

    func image(for url: URL) async -> UIImage? {
        if let c = cached(url) { return c }
        if let task = inflight[url] { return await task.value }
        let task = Task.detached(priority: .userInitiated) { await ImagePipeline.fetch(url) }
        inflight[url] = task
        let image = await task.value
        inflight[url] = nil
        if let image {
            let cost = Int(image.size.width * image.size.height * image.scale * image.scale * 4)
            memory.setObject(image, forKey: url as NSURL, cost: cost)
        }
        return image
    }

    static func candidates(for url: URL) -> [URL] {
        var list = [url]
        if let host = url.host, host.hasSuffix("anixmirai.com"), host != "s3.anixmirai.com",
           var comps = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            comps.host = "static.anixart.tv"
            if let mirror = comps.url { list.append(mirror) }
        }
        if url.scheme == "http", var comps = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            comps.scheme = "https"
            if let secure = comps.url { list.insert(secure, at: 0) }
        }
        return list
    }

    /// Decodes straight to a bitmap no larger than needed on screen (backgrounds are 1920 px wide);
    /// much faster and lighter than decoding full size.
    private static func downsample(_ data: Data, maxPixels: Int = 1400) -> UIImage? {
        let opts = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let src = CGImageSourceCreateWithData(data as CFData, opts) else { return UIImage(data: data) }
        let thumbOpts = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixels
        ] as CFDictionary
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, thumbOpts) else { return UIImage(data: data) }
        return UIImage(cgImage: cg)
    }

    private static func fetch(_ url: URL) async -> UIImage? {
        for candidate in candidates(for: url) {
            for attempt in 0..<2 {
                do {
                    var req = URLRequest(url: candidate)
                    // AnimeLib covers are only served with the site as referer.
                    if candidate.host?.contains("imglib") == true || candidate.host?.contains("cdnlibs") == true {
                        req.setValue("https://anilib.me/", forHTTPHeaderField: "Referer")
                    }
                    let (data, response) = try await session.data(for: req)
                    let status = (response as? HTTPURLResponse)?.statusCode ?? 200
                    if status == 404 { break }
                    guard (200..<300).contains(status), let image = downsample(data) else { continue }
                    return image
                } catch {
                    if attempt == 0 { try? await Task.sleep(nanoseconds: 400_000_000) }
                }
            }
        }
        return nil
    }
}

/// Fills the space it is given (crop-to-fill) and clips to it.
struct RemoteImage: View {
    let url: URL?
    var placeholderIcon: String = "photo"

    @State private var image: UIImage?
    @State private var failed = false

    init(url: URL?, placeholderIcon: String = "photo") {
        self.url = url
        self.placeholderIcon = placeholderIcon
        _image = State(initialValue: url.flatMap { ImagePipeline.shared.cached($0) })
    }

    var body: some View {
        Color.clear
            .overlay {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .transition(.opacity)
                } else {
                    ZStack {
                        LinearGradient(colors: [Theme.card, Theme.cardHighlight],
                                       startPoint: .topLeading, endPoint: .bottomTrailing)
                        if failed {
                            Image(systemName: placeholderIcon)
                                .font(.title3)
                                .foregroundStyle(.white.opacity(0.25))
                        } else {
                            ShimmerView()
                        }
                    }
                }
            }
            .clipped()
            .task(id: url) {
                guard let url else { failed = true; return }
                if let c = ImagePipeline.shared.cached(url) { image = c; failed = false; return }
                image = nil
                let loaded = await ImagePipeline.shared.image(for: url)
                withAnimation(.easeOut(duration: 0.25)) {
                    image = loaded
                    failed = loaded == nil
                }
            }
    }
}

struct ShimmerView: View {
    @State private var phase: CGFloat = -1

    var body: some View {
        GeometryReader { geo in
            LinearGradient(colors: [.clear, .white.opacity(0.06), .clear],
                           startPoint: .leading, endPoint: .trailing)
                .frame(width: geo.size.width * 0.8)
                .offset(x: phase * geo.size.width * 1.4)
        }
        .onAppear {
            withAnimation(.linear(duration: 1.3).repeatForever(autoreverses: false)) { phase = 1 }
        }
        .allowsHitTesting(false)
    }
}
