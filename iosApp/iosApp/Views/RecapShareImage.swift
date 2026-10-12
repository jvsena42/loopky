import UIKit
import Shared

/// The recap as a picture, at the pixel size each network wants.
///
/// Drawn with UIKit rather than captured from the SwiftUI view: a capture is the size of the
/// device it ran on. Every measure is in 1080ths of the short side, so the three formats are one
/// layout — which is why this file is a page of bare numbers.
enum RecapShareImage {
    private static let accentOrange = UIColor(red: 1, green: 0x5C / 255, blue: 0, alpha: 1)
    private static let accentPurple = UIColor(red: 0x7A / 255, green: 0x4C / 255, blue: 1, alpha: 1)
    private static let muted = UIColor.white.withAlphaComponent(0.86)
    /// A story is read at arm's length on a phone held upright, with twice the height to fill.
    private static let storyScale: CGFloat = 1.3

    static func render(_ text: RecapCardText, format: RecapImageFormat) -> UIImage? {
        let size = CGSize(width: CGFloat(format.width), height: CGFloat(format.height))
        let scale = format == RecapImageFormat.story ? storyScale : 1
        let unit = min(size.width, size.height) / 1080 * scale
        let rendererFormat = UIGraphicsImageRendererFormat()
        // Pinned to 1: the default is the screen's scale, which would triple every dimension.
        rendererFormat.scale = 1
        return UIGraphicsImageRenderer(size: size, format: rendererFormat).image { context in
            draw(text, in: context.cgContext, size: size, unit: unit)
        }
    }

    private static func draw(_ text: RecapCardText, in context: CGContext, size: CGSize, unit: CGFloat) {
        let colors = [accentOrange.cgColor, accentPurple.cgColor] as CFArray
        if let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1]) {
            context.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: size.width, y: size.height), options: [])
        }
        UIColor.white.withAlphaComponent(0.11).setFill()
        context.fillEllipse(in: circle(x: size.width * 0.92, y: size.height * 0.08, radius: 300 * unit))
        context.fillEllipse(in: circle(x: size.width * 0.05, y: size.height * 0.95, radius: 220 * unit))

        let margin = 84 * unit
        let width = size.width - margin * 2
        var blocks: [(NSAttributedString, CGFloat)] = [
            (styled(text.title, size: 46 * unit, color: muted, weight: .regular), 18 * unit),
            (styled(text.headline, size: 92 * unit, color: .white, weight: .bold), 40 * unit),
        ]
        blocks += text.stats.map { (styled($0, size: 46 * unit, color: .white, weight: .bold), 14 * unit) }
        let heights = blocks.map { height(of: $0.0, width: width) }
        let brandHeight = 96 * unit
        let pillHeight = 84 * unit
        let contentHeight = brandHeight + 56 * unit
            + zip(heights, blocks).map { $0 + $1.1 }.reduce(0, +)
            + 26 * unit + pillHeight
        // Centred in whatever room the format has: a story is nearly twice as tall as the content.
        var y = max((size.height - contentHeight - 120 * unit) / 2, margin)

        drawBrand(at: CGPoint(x: margin, y: y), side: brandHeight, unit: unit)
        y += brandHeight + 56 * unit
        for (index, block) in blocks.enumerated() {
            block.0.draw(
                with: CGRect(x: margin, y: y, width: width, height: heights[index]),
                options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine],
                context: nil
            )
            y += heights[index] + block.1
        }
        y += 26 * unit
        drawPill(text.persona, at: CGPoint(x: margin, y: y), height: pillHeight, unit: unit)

        let footerStyle = { (value: String) in styled(value, size: 34 * unit, color: muted, weight: .bold) }
        let tagline = footerStyle(text.tagline)
        let site = footerStyle("loopky.app")
        let baseline = size.height - margin - tagline.size().height
        tagline.draw(at: CGPoint(x: margin, y: baseline))
        site.draw(at: CGPoint(x: size.width - margin - site.size().width, y: baseline))
    }

    private static func drawBrand(at origin: CGPoint, side: CGFloat, unit: CGFloat) {
        let plate = CGRect(origin: origin, size: CGSize(width: side, height: side))
        UIColor.white.setFill()
        UIBezierPath(roundedRect: plate, cornerRadius: 26 * unit).fill()
        UIImage(named: "LaunchLogo")?.draw(in: plate.insetBy(dx: side * 0.14, dy: side * 0.14))
        let word = styled("Loopky", size: 52 * unit, color: .white, weight: .bold)
        word.draw(at: CGPoint(x: plate.maxX + 24 * unit, y: plate.midY - word.size().height / 2))
    }

    private static func drawPill(_ label: String, at origin: CGPoint, height: CGFloat, unit: CGFloat) {
        let text = styled(label, size: 40 * unit, color: accentOrange, weight: .bold)
        let textSize = text.size()
        let plate = CGRect(origin: origin, size: CGSize(width: textSize.width + 72 * unit, height: height))
        UIColor.white.setFill()
        UIBezierPath(roundedRect: plate, cornerRadius: height / 2).fill()
        text.draw(at: CGPoint(x: plate.minX + 36 * unit, y: plate.midY - textSize.height / 2))
    }

    private static func styled(_ value: String, size: CGFloat, color: UIColor, weight: UIFont.Weight) -> NSAttributedString {
        NSAttributedString(
            string: value,
            attributes: [.font: UIFont.systemFont(ofSize: size, weight: weight), .foregroundColor: color]
        )
    }

    private static func height(of text: NSAttributedString, width: CGFloat) -> CGFloat {
        let bounds = text.boundingRect(
            with: CGSize(width: width, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin],
            context: nil
        )
        return ceil(bounds.height)
    }

    private static func circle(x: CGFloat, y: CGFloat, radius: CGFloat) -> CGRect {
        CGRect(x: x - radius, y: y - radius, width: radius * 2, height: radius * 2)
    }

    /// Hands [image] to Instagram as a story background. False when Instagram is not installed
    /// (or the scheme is missing from `LSApplicationQueriesSchemes`), so the caller can fall back
    /// to the share sheet.
    ///
    /// Stories read the picture from the pasteboard, not from the URL, and Meta's documented
    /// contract asks for the calling app's identifier as `source_application`.
    static func openInstagramStory(_ image: UIImage?) -> Bool {
        guard
            let data = image?.pngData(),
            let bundleId = Bundle.main.bundleIdentifier,
            let url = URL(string: "instagram-stories://share?source_application=\(bundleId)"),
            UIApplication.shared.canOpenURL(url)
        else { return false }
        UIPasteboard.general.setItems(
            [["com.instagram.sharedSticker.backgroundImage": data]],
            options: [.expirationDate: Date().addingTimeInterval(300)]
        )
        UIApplication.shared.open(url)
        return true
    }
}
