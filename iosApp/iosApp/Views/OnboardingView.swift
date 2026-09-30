import SwiftUI

/// SwiftUI onboarding screen.
///
/// Pure layout — state comes from the shared `OnboardingViewModel` via `OnboardingScreen`.
struct OnboardingView: View {
    var isWorking: Bool = false
    var errorMessage: String?
    /// The deeplink was fired and the wait has run long: the spinning button needs a way out.
    var stillWaiting: Bool = false
    var onSignInTapped: () -> Void = {}
    var onRestoreTapped: () -> Void = {}
    var onCreatePubkyTapped: () -> Void = {}
    var onCancelTapped: () -> Void = {}
    /// A live authorisation whose approval has to happen on another device. Rendered inline in the
    /// sign-in column at expanded width; on narrower windows `OnboardingScreen` raises the same
    /// panel as a sheet and leaves this nil.
    var scan: RingScanPrompt?

    @Environment(\.loopkyWidthClass) private var widthClass

    var body: some View {
        ZStack {
            LoopkyColor.surfacePrimary.ignoresSafeArea()
            VStack(spacing: 24) {
                brandRow
                if widthClass.isExpanded { wideBody } else { stackedBody }
            }
            .padding(.horizontal, 24)
            .padding(.top, 24)
            .padding(.bottom, 32)
            .contentPane(widthClass.isExpanded ? PaneWidth.wide : PaneWidth.focused)
        }
    }

    private var stackedBody: some View {
        VStack(spacing: 24) {
            Spacer(minLength: 0)
            heroBlock
            Spacer(minLength: 0)
            signInColumn
        }
    }

    /// An iPad in landscape gets the hero and the sign-in side by side. Stacked, the same content
    /// on a 1366x1024 window puts the fox against the ceiling and the button against the floor with
    /// a screen's worth of cream between them; side by side each half is a normal size.
    private var wideBody: some View {
        HStack(spacing: 48) {
            heroBlock.frame(maxWidth: heroMaxWidth)
            // `ViewThatFits`, not a bare `ScrollView`. The column has to scroll when it overflows
            // — the `HStack` bounds it to the window height, and a `VStack` that overflows a
            // bounded parent clips in silence: no error, no ellipsis, just a button sliced in half,
            // and the QR panel is the tallest thing that lands here. But a `ScrollView` is greedy
            // about height whether it needs it or not, which pinned the three sign-in buttons to
            // the ceiling while the hero sat centred beside them. This takes the plain column
            // whenever it fits, so it sizes to its content and the row centres both halves.
            ViewThatFits(in: .vertical) {
                signInColumn
                ScrollView { signInColumn }
            }
            .frame(maxWidth: PaneWidth.focused)
        }
        .frame(maxHeight: .infinity)
    }

    /// The sign-in half: the three doors, or the QR code once one of them is waiting on a phone.
    @ViewBuilder
    private var signInColumn: some View {
        if let scan {
            RingScanPanel(
                authUrl: scan.authUrl,
                ringInstalledHere: scan.ringInstalledHere,
                stillWaiting: scan.stillWaiting,
                onOpenRingHere: scan.onOpenRingHere,
                onGetRing: scan.onGetRing,
                onCancel: scan.onCancel
            )
        } else {
            ctaBlock
        }
    }

    private var brandRow: some View {
        HStack(spacing: 10) {
            Text("onboarding_brand_name")
                .font(.system(size: 24, weight: .heavy))
                .foregroundColor(LoopkyColor.foregroundPrimary)
        }
    }

    private var heroBlock: some View {
        VStack(spacing: 20) {
            FoxPlate(size: 160, glyphSize: 96, containerColor: LoopkyColor.accentPrimarySoft)
            Text("brand_tagline")
                .font(.system(size: 30, weight: .heavy))
                .foregroundColor(LoopkyColor.foregroundPrimary)
                .multilineTextAlignment(.center)
                .lineSpacing(2)
            Text("onboarding_hero_subtitle")
                .font(.system(size: 15))
                .foregroundColor(LoopkyColor.foregroundSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 16)
        }
    }

    private var ctaBlock: some View {
        VStack(spacing: 12) {
            Button(action: onSignInTapped) {
                HStack(spacing: 10) {
                    Image(systemName: "key.fill")
                    Text(isWorking ? "onboarding_signin_waiting" : "onboarding_signin_default")
                }
            }
            .buttonStyle(.loopkyFilled)
            .shadow(color: LoopkyColor.shadowAccent, radius: 24, x: 0, y: 8)
            .disabled(isWorking)

            // Ring was opened over the deeplink, so this is the only way out of the wait — and a
            // signer that does not send the user back leaves them on a button still spinning (#299).
            // In the restore button's place, which is disabled for the whole wait, so the column
            // does not grow and squeeze the hero.
            if stillWaiting {
                StillWaitingNote()
                Button(action: onCancelTapped) {
                    Text("onboarding_qr_cancel")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(LoopkyColor.foregroundMuted)
                }
                .accessibilityIdentifier("onboarding_signin_cancel")
            } else {
                // The second door, presented as a matched pair with Ring rather than a footnote:
                // someone arriving with a recovery phrase has as much right to the front of the
                // screen as someone arriving with the app.
                Button(action: onRestoreTapped) {
                    HStack(spacing: 10) {
                        Image(systemName: "arrow.down.doc")
                        Text("onboarding_restore")
                    }
                }
                .buttonStyle(.loopkySoft)
                .disabled(isWorking)
                .accessibilityIdentifier("onboarding_restore")
            }

            Text("onboarding_no_email_notice")
                .font(.system(size: 13))
                .foregroundColor(LoopkyColor.foregroundMuted)
                .multilineTextAlignment(.center)

            if let error = errorMessage {
                Text(error)
                    .font(.system(size: 13))
                    .foregroundColor(LoopkyColor.danger)
            }

            // Deliberately a text link, not a third button — Android does the same. Creating a
            // pubky is the least common way in, and it costs money or a code.
            Button(action: onCreatePubkyTapped) {
                Text("onboarding_create_pubky")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(LoopkyColor.accentSecondary)
            }
            .accessibilityIdentifier("onboarding_create_pubky")
        }
    }
}

/// Everything the inline QR panel needs, bundled so `OnboardingView` takes one optional rather
/// than five parallel ones that are only ever all-set or all-nil together.
struct RingScanPrompt {
    let authUrl: String
    let ringInstalledHere: Bool
    let stillWaiting: Bool
    var onOpenRingHere: () -> Void
    var onGetRing: () -> Void
    var onCancel: () -> Void
}

/// The hero's ceiling in the wide layout — the fox and its two lines of copy at the size they are
/// on a phone, rather than a 96pt glyph adrift in half an iPad.
private let heroMaxWidth: CGFloat = 360

#if DEBUG
struct OnboardingView_Previews: PreviewProvider {
    static var previews: some View { OnboardingView() }
}
#endif
