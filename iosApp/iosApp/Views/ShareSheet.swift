import SwiftUI
import UIKit

/// `UIActivityViewController` wrapper — what `ShareLinkSheet`'s Share button presents, with the
/// link's QR code and the named line beside it.
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
