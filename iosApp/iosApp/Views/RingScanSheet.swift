import SwiftUI

/// The way in when Pubky Ring cannot be reached on this device: the pending authorisation as a QR
/// code for Ring on the phone that holds the key.
///
/// The code carries the same one-shot `pubkyauth://` URL the deeplink would have, and the relay
/// poll behind it is the same one — Ring does not care whether it was opened by a tap here or a
/// camera over there. That is also why both escape hatches reuse the live authorisation rather
/// than starting a new one: a fresh sign-in would invalidate the code the user may already be
/// pointing a phone at.
///
/// Two presentations, because the two windows want different things. On a phone this is a `.sheet`
/// — the sign-in screen is full of hero, and the code is a moment rather than a place. On an iPad
/// it is rendered **inline**, in the sign-in column beside the hero: the QR *is* the primary path
/// there (an iPad's owner keeps their key on their phone), and burying the primary path in a modal
/// floating over an otherwise empty screen is the wrong shape for it.
struct RingScanPanel: View {
    let authUrl: String
    /// Drives the body copy and the "open it here instead" escape hatch. When Ring *is* here the
    /// deeplink has already been fired on a phone, so the panel is a fallback rather than the main
    /// event — but on an iPad the handoff is to another device regardless, and the button is what
    /// lets someone whose key happens to be in Ring here take the short path anyway.
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

    @State private var didCopy = false

    var body: some View {
        VStack(spacing: 20) {
            Text("onboarding_qr_title")
                .font(.title2.bold())
                .foregroundStyle(LoopkyColor.foregroundPrimary)
                .multilineTextAlignment(.center)

            Text(ringInstalledHere ? "onboarding_qr_body" : "onboarding_qr_sheet_body")
                .font(.subheadline)
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)

            QrCodeView(text: authUrl)

            // In place of the waiting line, not under it, so the panel does not grow and push
            // Cancel — the way out the note points to — below the fold.
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                if stillWaiting {
                    StillWaitingNote()
                } else {
                    Text("onboarding_qr_waiting")
                        .font(.footnote)
                        .foregroundStyle(LoopkyColor.foregroundMuted)
                }
            }

            VStack(spacing: 10) {
                if ringInstalledHere {
                    Button("onboarding_qr_open_here", action: onOpenRingHere)
                        .buttonStyle(.loopkySoft)
                }
                Button(didCopy ? "onboarding_qr_copied" : "onboarding_qr_copy") {
                    UIPasteboard.general.string = authUrl
                    didCopy = true
                }
                .buttonStyle(.loopkyOutline)

                Button("onboarding_get_ring", action: onGetRing)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
                    .accessibilityIdentifier("onboarding_qr_get_ring")

                Button("onboarding_qr_cancel", action: onCancel)
                    .font(.subheadline)
                    .foregroundStyle(LoopkyColor.foregroundMuted)
            }
        }
    }
}

/// The phone presentation of [RingScanPanel]: a native sheet with detents, so it gets the system's
/// drag-to-dismiss and Liquid Glass chrome on the iOS 26 SDK for free.
struct RingScanSheet: View {
    let authUrl: String
    let ringInstalledHere: Bool
    var stillWaiting: Bool = false
    var onOpenRingHere: () -> Void
    var onGetRing: () -> Void
    var onCancel: () -> Void

    var body: some View {
        RingScanPanel(
            authUrl: authUrl,
            ringInstalledHere: ringInstalledHere,
            stillWaiting: stillWaiting,
            onOpenRingHere: onOpenRingHere,
            onGetRing: onGetRing,
            onCancel: onCancel
        )
        .padding(24)
        .frame(maxWidth: .infinity)
        // The code is a single-task screen, so it keeps a focused measure on a regular width
        // rather than spreading a 200pt QR across a form sheet.
        .contentPane(PaneWidth.focused)
        .background(LoopkyColor.surfacePrimary)
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        // Dragging the sheet away is the same intent as tapping Cancel: back out without leaving
        // an error behind. Without this the authorisation would keep polling behind a gone sheet.
        .interactiveDismissDisabled(false)
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
