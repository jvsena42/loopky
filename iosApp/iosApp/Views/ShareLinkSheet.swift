import Shared
import SwiftUI
import UIKit

/// A link a share button raised the sheet for.
///
/// `message` is what leaves the app — the named line a recipient reads — while `link` is the bare
/// address that goes into the code and onto the pasteboard. Sharing the sentence and copying the
/// address is deliberate: a pasted link is usually about to be opened, and a pasted sentence is not.
struct ShareLinkTarget: Identifiable {
    let id = UUID()
    let title: String
    let link: String
    let message: String
}

extension ShareLinkTarget {
    /// Someone's profile. Named, not a bare key: a recipient sees who it is before tapping.
    init(profile: PubkyIdentity, uri: String) {
        let label = IdentityData(profile).label
        self.init(
            title: label,
            link: uri,
            message: String(format: NSLocalizedString("share_profile_body", comment: ""), label, uri)
        )
    }

    init(deckTitle: String, uri: String) {
        self.init(
            title: deckTitle,
            link: uri,
            message: String(format: NSLocalizedString("share_deck_body", comment: ""), deckTitle, uri)
        )
    }
}

/// What a share button raises: the link as a QR code, with the ways out of the app underneath.
///
/// The code is the point of the sheet. Sharing used to go straight to `UIActivityViewController`,
/// which only ever helps someone who already has the recipient in a messaging app — the person
/// sitting across the table had no way to take the link off the screen. A code they can point a
/// camera at needs no channel at all, and the same picture is what Share attaches.
struct ShareLinkSheet: View {
    let target: ShareLinkTarget

    @State private var isSystemSharePresented = false
    @State private var copied = false
    /// Measured, because iOS has no "fit the content" detent and `.large` leaves the code stranded
    /// at the top of a full-height sheet. The initial value is what the first frame uses.
    @State private var sheetHeight: CGFloat = 540

    var body: some View {
        VStack(spacing: 16) {
            RoundedRectangle(cornerRadius: 2)
                .fill(LoopkyColor.borderSubtle)
                .frame(width: 36, height: 4)
                .padding(.top, 12)

            Text(verbatim: target.title)
                .font(.system(size: 20, weight: .heavy))
                .foregroundColor(LoopkyColor.foregroundPrimary)
                .multilineTextAlignment(.center)
                .lineLimit(2)

            Text("share_sheet_hint")
                .font(.system(size: 14))
                .foregroundColor(LoopkyColor.foregroundSecondary)
                .multilineTextAlignment(.center)

            QrCodeView(text: target.link, size: 220, label: "share_sheet_qr_content_description")

            Text(verbatim: target.link)
                .font(.system(size: 12))
                .foregroundColor(LoopkyColor.foregroundMuted)
                .multilineTextAlignment(.center)
                // Middle, not tail: a pubky URI's two ends are what identify it — the account at
                // the front, the deck id at the back — and eliding the back leaves 60 characters
                // of key saying nothing. One line, since a wrapped address never elides at all.
                .lineLimit(1)
                .truncationMode(.middle)
                .padding(.horizontal, 8)

            HStack(spacing: 12) {
                Button(copied ? "share_sheet_copied" : "share_sheet_copy", action: copy)
                    .buttonStyle(.loopkyOutline)
                    .accessibilityIdentifier("share_link_copy")
                Button("share_sheet_send") { isSystemSharePresented = true }
                    .buttonStyle(LoopkyFilledButtonStyle(fill: LoopkyColor.accentPrimary, fontSize: 16))
                    .accessibilityIdentifier("share_link_send")
            }
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 32)
        .background(
            GeometryReader { proxy in
                Color.clear.onAppear { sheetHeight = proxy.size.height }
            }
        )
        .presentationDetents([.height(sheetHeight)])
        .presentationDragIndicator(.hidden)
        .sheet(isPresented: $isSystemSharePresented) {
            ShareSheet(items: systemShareItems)
        }
    }

    /// The picture goes first: a receiving app that takes one item takes the code, and the link is
    /// inside it. Without a code to attach, the sentence alone still carries the address.
    private var systemShareItems: [Any] {
        guard let image = QrCodeView.shareImage(target.link) else { return [target.message] }
        return [image, target.message]
    }

    private func copy() {
        UIPasteboard.general.string = target.link
        withAnimation { copied = true }
        Task {
            try? await Task.sleep(for: .seconds(2))
            withAnimation { copied = false }
        }
    }
}

#Preview {
    ShareLinkSheet(target: ShareLinkTarget(
        title: "Spanish Verbs",
        link: "pubky://abcdefghij1234567890/pub/loopky/decks/deck1/manifest.json",
        message: "Spanish Verbs on Loopky"
    ))
}
