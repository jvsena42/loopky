import SwiftUI

/// The December invitation to the year's recap. On the *secondary* accent so it does not read as
/// a second daily-goal hero, which is the primary one directly beneath it.
struct RecapBanner: View {
    let year: Int
    var onOpen: () -> Void = {}
    var onDismiss: () -> Void = {}

    var body: some View {
        HStack(spacing: 12) {
            // Two Buttons side by side rather than one tappable row holding the other: a bare
            // tap gesture is not a control, and a Button nested in a Button swallows the inner tap.
            Button(action: onOpen) {
                HStack(spacing: 12) {
                    Text(verbatim: "🎁").font(.system(size: 28))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: String.localizedStringWithFormat(
                            NSLocalizedString("recap_banner_title", comment: ""), year
                        ))
                        .font(.system(size: 16, weight: .heavy))
                        .foregroundStyle(LoopkyColor.foregroundOnAccent)
                        Text("recap_banner_body")
                            .font(.system(size: 13))
                            .foregroundStyle(LoopkyColor.foregroundOnAccentMuted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("recap_banner")
            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(LoopkyColor.foregroundOnAccent)
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel(Text("recap_banner_dismiss"))
            .accessibilityIdentifier("recap_banner_dismiss")
        }
        .padding(.leading, 18)
        .padding(.trailing, 4)
        .padding(.vertical, 10)
        .background(LoopkyColor.accentSecondary, in: RoundedRectangle(cornerRadius: 22))
    }
}
