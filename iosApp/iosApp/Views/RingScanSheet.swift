import SwiftUI

/// The way in to a Ring sign-in: the pending authorisation as a QR code for Ring on the phone that
/// holds the key — which is as often the user's other phone as the one in their hand.
///
/// The code carries the same one-shot `pubkyauth://` URL the deeplink would have, and the relay
/// poll behind it is the same one — Ring does not care whether it was opened by a tap here or a
/// camera over there. That is also why both escape hatches reuse the live authorisation rather
/// than starting a new one: a fresh sign-in would invalidate the code the user may already be
/// pointing a phone at.
///
/// Two presentations, because the two windows want different things. On a phone this is a `.sheet`
/// — the sign-in screen is full of hero, and the code is a moment rather than a place. On an iPad
/// it is rendered **inline**, in the sign-in column beside the hero: there is a column to put it
/// in, and burying it in a modal floating over an otherwise empty screen is the wrong shape.
struct RingScanPanel: View {
    let authUrl: String
    /// Drives the body copy and the "open it here instead" escape hatch — never whether this panel
    /// is shown. Ring answering `pubkyauth://` here says only that some app does, not that it holds
    /// this user's key, so the code leads on every device and the button is what lets whoever does
    /// have their key here take the short path.
    let ringInstalledHere: Bool
    /// Set once the wait has run long, never as a failure: a late approval is still accepted (#299).
    var stillWaiting: Bool = false
    var onOpenRingHere: () -> Void
    /// Where Ring comes from, for someone who has it on no phone at all. This lives here rather
    /// than on onboarding: the panel is the one place that has already established Ring is not
    /// reachable, and a permanent link under the sign-in button was answering a question nobody on
    /// that screen had asked.
    var onGetRing: () -> Void
    var onCancel: () -> Void

    var body: some View {
        RingScanContent(
            authUrl: authUrl,
            message: ringInstalledHere ? "onboarding_qr_body" : "onboarding_qr_sheet_body",
            stillWaiting: stillWaiting
        ) {
            if ringInstalledHere {
                Button("onboarding_qr_open_here", action: onOpenRingHere)
                    .buttonStyle(.loopkySoft)
                    .accessibilityIdentifier("onboarding_qr_open_here")
            }
            CopyLinkButton(authUrl: authUrl)

            Button("onboarding_get_ring", action: onGetRing)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(LoopkyColor.accentPrimary)
                .accessibilityIdentifier("onboarding_qr_get_ring")

            // The panel's only way out: it replaces the sign-in buttons rather than covering them.
            Button("onboarding_qr_cancel", action: onCancel)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .accessibilityIdentifier("onboarding_qr_cancel")
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 28)
        .frame(maxWidth: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 28, style: .continuous).fill(LoopkyColor.surfaceCard)
        )
    }
}

/// The phone presentation of [RingScanPanel]: a native sheet with detents, so it gets the system's
/// drag-to-dismiss and Liquid Glass chrome on the iOS 26 SDK for free.
///
/// There is no Cancel button, matching Android: dragging the sheet away already lands on
/// `onCancelSignIn` through the presenting binding, so a third control would restate the gesture.
struct RingScanSheet: View {
    let authUrl: String
    let ringInstalledHere: Bool
    var stillWaiting: Bool = false
    var onOpenRingHere: () -> Void
    var onGetRing: () -> Void

    @State private var contentHeight: CGFloat = 0

    var body: some View {
        // A QR big enough to scan plus its controls does not fit a short phone, and a VStack that
        // overflows a sheet clips in silence.
        ScrollView {
            RingScanContent(
                authUrl: authUrl,
                message: ringInstalledHere ? "onboarding_qr_body" : "onboarding_qr_sheet_body",
                stillWaiting: stillWaiting
            ) {
                if ringInstalledHere {
                    Button("onboarding_qr_open_here", action: onOpenRingHere)
                        .buttonStyle(.loopkySoft)
                        .accessibilityIdentifier("onboarding_qr_open_here")
                }
                CopyLinkButton(authUrl: authUrl)
                if !ringInstalledHere {
                    Button("onboarding_get_ring", action: onGetRing)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(LoopkyColor.accentPrimary)
                        .accessibilityIdentifier("onboarding_qr_get_ring")
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 32)
            .padding(.bottom, 24)
            .frame(maxWidth: .infinity)
            // The code is a single-task screen, so it keeps a focused measure on a regular width
            // rather than spreading a 220pt QR across a form sheet.
            .contentPane(PaneWidth.focused)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentHeight = $0 }
        }
        .scrollBounceBehavior(.basedOnSize)
        // On the presentation, not the content: a `.background` on the content only paints as far
        // as the content reaches, and the rest of the `.large` detent showed the system material.
        .presentationBackground(LoopkyColor.surfaceCard)
        // Sized to the content, like Android's sheet wrapping its column. The system clamps a
        // detent taller than the screen to full height, where the ScrollView takes over.
        .presentationDetents(contentHeight > 0 ? [.height(contentHeight)] : [.large])
        .presentationDragIndicator(.visible)
        .accessibilityIdentifier("onboarding_ring_qr_sheet")
    }
}

/// The handoff itself, shared by the iPad panel and the phone sheet so the two cannot drift. Only
/// the body copy and the trailing controls differ, so both are passed in.
private struct RingScanContent<Actions: View>: View {
    let authUrl: String
    let message: LocalizedStringKey
    let stillWaiting: Bool
    @ViewBuilder let actions: Actions

    var body: some View {
        VStack(spacing: 16) {
            Text("onboarding_qr_title")
                .font(.system(size: 20, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
                .multilineTextAlignment(.center)

            Text(message)
                .font(.system(size: 14))
                .lineSpacing(4)
                .foregroundStyle(LoopkyColor.foregroundSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)

            QrCodeView(text: authUrl, size: 220)

            // In place of the waiting line, not under it, so the panel does not grow and push
            // Cancel, the way out the note points to, below the fold.
            if stillWaiting {
                StillWaitingNote()
            } else {
                Text("onboarding_qr_waiting")
                    .font(.system(size: 13))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
                    .multilineTextAlignment(.center)
            }

            actions
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("onboarding_ring_qr")
    }
}

/// The escape hatch for a camera that will not read the code: the same one-shot URL, on the
/// clipboard, to be pasted into Ring by hand.
private struct CopyLinkButton: View {
    let authUrl: String
    @State private var didCopy = false

    var body: some View {
        Button(didCopy ? "onboarding_qr_copied" : "onboarding_qr_copy") {
            UIPasteboard.general.string = authUrl
            didCopy = true
        }
        .font(.system(size: 14, weight: .semibold))
        .foregroundStyle(LoopkyColor.accentSecondary)
        .accessibilityIdentifier("onboarding_qr_copy")
    }
}

/// Said once the wait has run long, under the waiting line or the spinning button.
struct StillWaitingNote: View {
    var body: some View {
        Text("onboarding_still_waiting")
            .font(.footnote)
            .foregroundStyle(LoopkyColor.foregroundSecondary)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("onboarding_still_waiting")
    }
}
