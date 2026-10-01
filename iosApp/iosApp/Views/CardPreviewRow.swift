import SwiftUI
import Shared

struct CardPreviewData: Identifiable {
    let id: String
    let front: String
    let back: String
    /// The front's picture: an Anki front is often a picture and nothing else, and such a row
    /// otherwise reads as its answer alone.
    var frontImage: MediaRef.Image?
}

/// One row of a deck's card list: the front's picture when it has one, the front, the back.
struct CardPreviewRow: View {
    let card: CardPreviewData
    /// The deck's author, not the reader: a followed deck's blobs live on their pubky.
    let authorPubky: String
    let deckId: String

    var body: some View {
        HStack(spacing: 0) {
            if let image = card.frontImage {
                // Fitted into a wide slot rather than cropped to a square: a
                // picture standing in for a whole front is often a strip.
                ThumbnailLayout {
                    CardMediaImage(ref: image, authorPubky: authorPubky, deckId: deckId)
                }
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .padding(.trailing, 10)
            }
            Text(card.front)
                .font(.system(size: 15, weight: .bold))
                .foregroundColor(LoopkyColor.foregroundPrimary)
                .lineLimit(1)
            Spacer(minLength: 12)
            Text(card.back)
                .font(.system(size: 13))
                .foregroundColor(LoopkyColor.foregroundMuted)
                .lineLimit(1)
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 14)
                .fill(LoopkyColor.surfaceCard)
        )
        .shadow(color: LoopkyColor.shadowElevationLow, radius: 8, x: 0, y: 2)
    }
}

/// Sizes a fitted picture to its own width at 36pt tall, between square and 96pt. A flexible
/// `frame(maxWidth:)` always takes the whole 96pt and leaves a gap beside a narrow picture.
private struct ThumbnailLayout: Layout {
    let height: CGFloat = 36
    let maxWidth: CGFloat = 96

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        CGSize(width: width(of: subviews), height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let size = ProposedViewSize(width: bounds.width, height: height)
        subviews.first?.place(at: CGPoint(x: bounds.minX, y: bounds.midY), anchor: .leading, proposal: size)
    }

    private func width(of subviews: Subviews) -> CGFloat {
        let natural = subviews.first?.sizeThatFits(ProposedViewSize(width: nil, height: height)).width ?? 0
        return min(max(natural, height), maxWidth)
    }
}
