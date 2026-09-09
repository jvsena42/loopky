import SwiftUI
import UIKit

/// The two ways Loopky points at pubky.app.
///
/// A Loopky account *is* a Pubky account — the same `profile.json`, the same follow graph, the
/// same key — and nothing on iOS said so: a foreign profile offered a generic globe, and the self
/// profile a plain "Open on pubky.app" button with no explanation of what that even is.
///
/// Both are deliberately quiet. The network underneath is worth knowing about, but it is not what
/// someone opened a flashcards app to do. Neither builds its own URL: the address comes from the
/// ViewModel, which reads it off `PubkyEnvironment`, so a debug build points at the staging
/// instance where its account actually exists.

/// pubky.app's mark, tinted like every other icon on the screen.
///
/// Monochrome on purpose. pubky.app sets the mark in lime on black, but that disc was the
/// highest-contrast thing on a cream screen and pulled the eye before the primary action beside
/// it — and the lime without the disc is the faintest thing on the screen. The shape alone says
/// whose logo it is.
struct PubkyMark: View {
    var size: CGFloat = 22

    var body: some View {
        Image("PubkyMark")
            .renderingMode(.template)
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .foregroundStyle(LoopkyColor.foregroundSecondary)
    }
}

/// pubky.app's lime, and the halo the icon button below wears.
///
/// Deliberately *not* a `LoopkyColor` entry: it is another product's identity, and that palette is
/// Loopky's. It lives beside the mark it belongs to, and `PubkyAppLink.kt` holds the same values
/// for the same reason.
private enum PubkyBrand {
    /// `#C8FF00`, the `--brand` of pubky-app's own stylesheet.
    static let lime = UIColor(red: 200 / 255, green: 1, blue: 0, alpha: 1)

    /// One lime for both schemes — a glow is light, and light does not invert with the ground —
    /// but two alphas, because a shadow *composites* rather than adds: the lime at the light
    /// value over the dark ground lands on an olive that is no longer the brand colour, while the
    /// same value on cream already reads as lime.
    static let glowNear = glow(light: 0.70, dark: 0.85)
    static let glowFar = glow(light: 0.50, dark: 0.65)

    private static func glow(light: CGFloat, dark: CGFloat) -> Color {
        Color(UIColor { lime.withAlphaComponent($0.userInterfaceStyle == .dark ? dark : light) })
    }
}

private extension View {
    /// Two shadows rather than one, because SwiftUI's is a literal blur with no elevation model to
    /// lean on: the near one reads as the edge of the light, the far one is the halo. A single
    /// radius gives either a hard ring or a formless smudge. Neither is offset — nothing here is
    /// lit from above.
    func pubkyGlow() -> some View {
        shadow(color: PubkyBrand.glowNear, radius: 6)
            .shadow(color: PubkyBrand.glowFar, radius: 16)
    }
}

/// The button that leaves for pubky.app, in the same circle Share wears beside it — so it carries
/// no more weight in the row than that does — lit by ``PubkyBrand``'s halo.
///
/// The mark itself stays grey: the lime *as ink* is the faintest thing on a cream screen, 1.3:1,
/// and the dark plate that would fix that made this secondary control louder than the primary
/// action beside it. The brand colour rides underneath instead, where being pale costs it nothing.
struct PubkyAppIconButton: View {
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            PubkyMark(size: 22)
                .frame(width: 44, height: 44)
                .background(Circle().fill(LoopkyColor.surfaceCard).pubkyGlow())
                .overlay(Circle().stroke(LoopkyColor.borderSubtle, lineWidth: 1))
        }
        .buttonStyle(.plain)
        // The mark has no text of its own, so the label goes on the button.
        .accessibilityLabel(Text("pubky_app_open_profile"))
        .accessibilityIdentifier("pubky_app_open_profile")
    }
}

/// The self-profile call to action: one soft row explaining what the button does, for the person
/// who has no reason to know that the key they signed in with is also a social account.
///
/// A card rather than a banner, and it never claims a Loopky deck appears there — it does not.
/// What travels is the profile and, when they choose to announce one, the post.
struct PubkyAppProfileCta: View {
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                PubkyMark(size: 26)
                VStack(alignment: .leading, spacing: 2) {
                    Text("pubky_app_cta_title")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(LoopkyColor.foregroundPrimary)
                    Text("pubky_app_cta_body")
                        .font(.system(size: 12))
                        .foregroundStyle(LoopkyColor.foregroundMuted)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                Image(systemName: "arrow.up.forward.square")
                    .font(.system(size: 16))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 20).fill(LoopkyColor.surfaceSecondary))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("profile_pubky_app_cta")
    }
}
