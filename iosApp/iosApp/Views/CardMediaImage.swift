import SwiftUI
import Shared

/// Draws a card or cover picture, whichever of the three shapes it is in.
///
/// A `MediaRef.Image` is either a **remote** web URL (an Unsplash pick or a pasted address, stored
/// as-is with no blob) or a **blob** on a homeserver, which has to be fetched. On top of that a
/// freshly picked image exists only as `pendingBytes` until the deck is saved. All three have to
/// render, or a picture disappears at some point between choosing it and reloading the screen.
///
/// The fetch is keyed on the ref's sha, so switching cards re-fetches and re-rendering does not.
struct CardMediaImage: View {
    let ref: MediaRef.Image?
    /// Bytes chosen in this session and not yet uploaded. Takes precedence: it is the newest.
    var pendingBytes: Data?
    /// The *deck's* author, not the signed-in user — blobs on a deck you do not own live under
    /// their pubky, not yours.
    var authorPubky: String
    var deckId: String
    var contentMode: ContentMode = .fit

    @State private var blob: Data?
    @State private var isLoading = false

    var body: some View {
        Group {
            if let data = pendingBytes ?? blob, let image = UIImage(data: data) {
                scaled(Image(uiImage: image))
            } else if let url = ref?.url, let link = URL(string: url) {
                AsyncImage(url: link) { image in
                    scaled(image)
                } placeholder: {
                    placeholder
                }
            } else {
                placeholder
            }
        }
        .task(id: ref?.sha256) { await loadBlobIfNeeded() }
    }

    /// A `.fill` picture is cropped to the box it was handed, never fitted into it.
    ///
    /// `aspectRatio(contentMode: .fill)` alone does not do that: the image reports its *own* size
    /// to the parent, so a picture drawn on its own — a deck cover in its `ZStack` — decides how
    /// wide the box is rather than filling it, and a square or portrait source ends up inset with
    /// a strip of card down each side while a landscape neighbour runs corner to corner (#255).
    /// `Color.clear` accepts whatever it is offered and the overlay is sized to *that*, which is
    /// what makes every tile agree. The `.fit` half is unchanged — card media is fitted on purpose.
    @ViewBuilder
    private func scaled(_ image: Image) -> some View {
        if contentMode == .fill {
            Color.clear
                .overlay { image.resizable().scaledToFill() }
                .clipped()
        } else {
            image.resizable().aspectRatio(contentMode: .fit)
        }
    }

    private var placeholder: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 12).fill(LoopkyColor.borderSubtle.opacity(0.4))
            if isLoading { ProgressView().controlSize(.small) }
        }
    }

    private func loadBlobIfNeeded() async {
        blob = nil
        guard pendingBytes == nil, let ref, ref.url == nil, !ref.sha256.isEmpty else { return }
        isLoading = true
        defer { isLoading = false }
        let bytes = try? await IosDependencies.shared.mediaBytes(
            authorPubky: authorPubky,
            deckId: deckId,
            ref: ref
        )
        blob = bytes?.toData()
    }
}
