import SwiftUI
import UIKit

/// Crop step for "search by frame": trace.moe is very accurate on a clean frame but gets confused by
/// player controls, status bars or app UI around the video. The video area is detected automatically
/// and can be adjusted by dragging the frame or its corners.
struct CropEditor: View {
    let image: UIImage
    @Binding var crop: CGRect            // normalised 0...1

    private let handle: CGFloat = 26

    var body: some View {
        GeometryReader { geo in
            let fit = fitted(in: geo.size)
            let rect = CGRect(x: fit.minX + crop.minX * fit.width, y: fit.minY + crop.minY * fit.height,
                              width: crop.width * fit.width, height: crop.height * fit.height)
            ZStack(alignment: .topLeading) {
                Image(uiImage: image).resizable().scaledToFit()
                    .frame(width: geo.size.width, height: geo.size.height)
                // Dim everything outside the crop.
                Path { p in
                    p.addRect(CGRect(origin: .zero, size: geo.size))
                    p.addRect(rect)
                }
                .fill(Color.black.opacity(0.55), style: FillStyle(eoFill: true))
                .allowsHitTesting(false)
                Rectangle()
                    .stroke(Color.white, lineWidth: 2)
                    .frame(width: rect.width, height: rect.height)
                    .offset(x: rect.minX, y: rect.minY)
                    .contentShape(Rectangle())
                    .gesture(DragGesture().onChanged { v in move(by: v.translation, fit: fit) }
                        .onEnded { _ in dragOrigin = nil })
                ForEach(0..<4, id: \.self) { corner in
                    Circle().fill(.white)
                        .frame(width: 18, height: 18)
                        .frame(width: handle, height: handle)
                        .contentShape(Rectangle())
                        .offset(x: (corner % 2 == 0 ? rect.minX : rect.maxX) - handle / 2,
                                y: (corner < 2 ? rect.minY : rect.maxY) - handle / 2)
                        .gesture(DragGesture().onChanged { v in resize(corner: corner, to: v.location, fit: fit) })
                }
            }
        }
    }

    @State private var dragOrigin: CGRect?

    private func fitted(in size: CGSize) -> CGRect {
        let s = min(size.width / image.size.width, size.height / image.size.height)
        let w = image.size.width * s, h = image.size.height * s
        return CGRect(x: (size.width - w) / 2, y: (size.height - h) / 2, width: w, height: h)
    }

    private func move(by t: CGSize, fit: CGRect) {
        if dragOrigin == nil { dragOrigin = crop }
        guard let o = dragOrigin else { return }
        var r = o.offsetBy(dx: t.width / fit.width, dy: t.height / fit.height)
        r.origin.x = min(max(0, r.origin.x), 1 - r.width)
        r.origin.y = min(max(0, r.origin.y), 1 - r.height)
        crop = r
    }

    private func resize(corner: Int, to p: CGPoint, fit: CGRect) {
        let x = min(max(0, (p.x - fit.minX) / fit.width), 1)
        let y = min(max(0, (p.y - fit.minY) / fit.height), 1)
        var minX = crop.minX, minY = crop.minY, maxX = crop.maxX, maxY = crop.maxY
        if corner % 2 == 0 { minX = min(x, maxX - 0.1) } else { maxX = max(x, minX + 0.1) }
        if corner < 2 { minY = min(y, maxY - 0.1) } else { maxY = max(y, minY + 0.1) }
        crop = CGRect(x: minX, y: minY, width: maxX - minX, height: maxY - minY)
    }

    // MARK: Auto-detection

    /// Finds the video area: the tallest band of "busy" rows (the picture) between flat UI/black rows.
    static func detectVideo(in image: UIImage) -> CGRect {
        guard let cg = image.cgImage else { return CGRect(x: 0, y: 0, width: 1, height: 1) }
        let w = 64, h = 128
        var pixels = [UInt8](repeating: 0, count: w * h)
        guard let ctx = CGContext(data: &pixels, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w,
                                  space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue)
        else { return CGRect(x: 0, y: 0, width: 1, height: 1) }
        ctx.interpolationQuality = .medium
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        // Row activity: horizontal detail + spread of brightness. UI bars and letterboxing are flat.
        var activity = [Double](repeating: 0, count: h)
        for y in 0..<h {
            var diff = 0.0, minV = 255.0, maxV = 0.0
            for x in 0..<w {
                let v = Double(pixels[y * w + x])
                minV = min(minV, v); maxV = max(maxV, v)
                if x > 0 { diff += abs(v - Double(pixels[y * w + x - 1])) }
            }
            activity[y] = diff / Double(w) + (maxV - minV) / 8
        }
        let sorted = activity.sorted()
        let threshold = max(6, sorted[h / 2] * 0.35)
        // Longest run of active rows (allowing short gaps for dark scenes).
        var best = (0, h), cur = (0, 0), gap = 0, inRun = false
        for y in 0..<h {
            if activity[y] >= threshold {
                if !inRun { cur = (y, y); inRun = true }
                cur.1 = y; gap = 0
            } else if inRun {
                gap += 1
                if gap > 3 {
                    if cur.1 - cur.0 > best.1 - best.0 || best == (0, h) { best = cur }
                    inRun = false
                }
            }
        }
        if inRun, cur.1 - cur.0 > best.1 - best.0 || best == (0, h) { best = cur }
        let top = Double(best.0) / Double(h), bottom = Double(best.1 + 1) / Double(h)
        // The bitmap buffer starts with the top row of the image.
        var rect = CGRect(x: 0, y: top, width: 1, height: bottom - top)
        if rect.height < 0.15 || rect.height > 0.97 { rect = CGRect(x: 0, y: 0, width: 1, height: 1) }
        return rect
    }

    static func cropped(_ image: UIImage, to r: CGRect) -> UIImage {
        guard let cg = image.cgImage else { return image }
        let px = CGRect(x: r.minX * CGFloat(cg.width), y: r.minY * CGFloat(cg.height),
                        width: r.width * CGFloat(cg.width), height: r.height * CGFloat(cg.height)).integral
        guard let out = cg.cropping(to: px) else { return image }
        return UIImage(cgImage: out)
    }
}
