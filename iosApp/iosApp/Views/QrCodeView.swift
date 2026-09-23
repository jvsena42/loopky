import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit

/// A QR code rendered from CoreImage — no dependency, unlike Android's zxing.
///
/// Three details are load-bearing, and all three are about staying scannable:
///
/// - The generator emits one pixel per module, so the image is a few dozen points across and is
///   then scaled up. Left to interpolate, the modules smear into each other and a camera has to be
///   nursed into reading it; `.interpolation(.none)` keeps the edges hard.
/// - The plate is **white in both themes**. A QR inverted for dark mode is not one any scanner
///   will read, so this deliberately does not follow the colour scheme.
/// - `showsMark` destroys the modules under the centre, so it raises the correction level from M
///   to H: a reader recovers ~30% of the code at H and the mark covers ~6%. Never widen
///   `markPlateFraction` without checking that margin.
struct QrCodeView: View {
    let text: String
    var size: CGFloat = 240
    /// Quiet zone. The spec asks for four modules of blank margin; without it, readers that find
    /// the code flush against other content often fail to lock on.
    var padding: CGFloat = 16
    /// What VoiceOver reads. Defaults to the sign-in code this view was written for.
    var label: LocalizedStringKey = "onboarding_qr_title"
    /// The Loopky fox in the middle, marking whose link this is. Off for the sign-in and invoice
    /// codes, whose payloads are dense one-shot credentials with nothing to brand.
    var showsMark: Bool = false

    private static let context = CIContext()
    private static let markAsset = "LaunchLogo"
    private static let markPlateFraction: CGFloat = 0.24
    private static let markInsetFraction: CGFloat = 0.08

    var body: some View {
        Group {
            if let image = Self.render(text, correction: Self.correction(showsMark)) {
                Image(decorative: image, scale: 1)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: size, height: size)
                    .overlay { if showsMark { mark(plate: size * Self.markPlateFraction) } }
            } else {
                // Nothing actionable to offer if CoreImage refuses the payload; the caller's
                // "copy the link" affordance is the way out.
                Color.clear.frame(width: size, height: size)
            }
        }
        .padding(padding)
        .background(Color.white)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .accessibilityLabel(Text(label))
    }

    /// The white ring is what makes the fox read as part of the code rather than as a stain on it:
    /// the mark's own disc lands flush against the modules without one.
    private func mark(plate: CGFloat) -> some View {
        Image(Self.markAsset)
            .resizable()
            .scaledToFit()
            .padding(plate * Self.markInsetFraction)
            .frame(width: plate, height: plate)
            .background(Circle().fill(Color.white))
    }

    /// The same code as a shareable image: the modules blown up onto a white square with a margin.
    ///
    /// The margin is the quiet zone. A code pasted into a chat lands flush against a dark bubble,
    /// and a reader that cannot find the border will not lock on to what is inside it.
    ///
    /// Drawn through `UIImage`, never `CGContext.draw`: the renderer's context is y-flipped, and a
    /// vertically mirrored QR code is one no scanner reads.
    static func shareImage(
        _ text: String,
        side: CGFloat = 720,
        margin: CGFloat = 48,
        showsMark: Bool = false
    ) -> UIImage? {
        guard let code = render(text, correction: correction(showsMark)) else { return nil }
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = true
        let size = CGSize(width: side, height: side)
        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: size))
            // One module per pixel scaled up: interpolating smears the edges, and a smeared code
            // is one a camera has to be nursed into reading.
            context.cgContext.interpolationQuality = .none
            UIImage(cgImage: code).draw(
                in: CGRect(x: margin, y: margin, width: side - margin * 2, height: side - margin * 2)
            )
            guard showsMark, let mark = UIImage(named: markAsset) else { return }
            let plate = side * markPlateFraction
            let rect = CGRect(
                x: (side - plate) / 2,
                y: (side - plate) / 2,
                width: plate,
                height: plate
            )
            UIColor.white.setFill()
            context.cgContext.fillEllipse(in: rect)
            // Back to smooth for the artwork: the quality above is set for the modules, and the
            // fox drawn under it comes out as visibly stepped edges.
            context.cgContext.interpolationQuality = .high
            let inset = plate * markInsetFraction
            mark.draw(in: rect.insetBy(dx: inset, dy: inset))
        }
    }

    /// H once something is drawn over the centre; M otherwise — see the type's note.
    private static func correction(_ showsMark: Bool) -> String {
        showsMark ? "H" : "M"
    }

    private static func render(_ text: String, correction: String) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        // Medium correction by default: the payload is a long one-shot URL, and L would push the
        // module count up without buying anything on a screen, where there is no print noise to
        // correct for.
        filter.correctionLevel = correction
        guard let output = filter.outputImage else { return nil }
        return context.createCGImage(output, from: output.extent)
    }
}
