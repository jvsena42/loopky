import SwiftUI
import UIKit

/// Loopky's brand palette. Hand-maintained and mirrors `LoopkyColors.kt` on Android, so a change
/// here wants the same change there — including the reasoning in `LoopkyDarkColors`' doc comment,
/// which is where the dark values come from.
///
/// Every entry is a **dynamic** colour rather than a fixed one, so the palette and the native
/// chrome around it resolve from the same trait collection. That is what lets `RootView` express
/// the user's choice as one `preferredColorScheme` at the window root: SwiftUI propagates it into
/// the traits, and a `List`'s row fills, an alert, a sheet and every Loopky surface all answer to
/// it together. A fixed palette is what made Settings unreadable on a dark device before
/// (`#7caa5112`), and pinning the scheme to `.light` was the stopgap this replaces.
enum LoopkyColor {
    static let surfacePrimary          = dynamic(light: 0xFFFBF5, dark: 0x0D0B11)
    static let surfaceSecondary        = dynamic(light: 0xFFF4EA, dark: 0x1E1A28)
    static let surfaceCard             = dynamic(light: 0xFFFFFF, dark: 0x2D2839)
    static let accentPrimary           = dynamic(light: 0xFF5C00, dark: 0xFF6B2C)
    static let accentPrimarySoft       = dynamic(light: 0xFFE8D6, dark: 0x38221A)
    static let accentSecondary         = dynamic(light: 0x7A4CFF, dark: 0x9B7BFF)
    static let accentSecondarySoft     = dynamic(light: 0xE9E0FF, dark: 0x2B2443)
    static let foregroundPrimary       = dynamic(light: 0x1B1B1F, dark: 0xEDEBF1)
    static let foregroundSecondary     = dynamic(light: 0x5A5A66, dark: 0xB5AEBD)
    static let foregroundMuted         = dynamic(light: 0x8B8B99, dark: 0x9C94A8)
    static let foregroundOnAccent      = Color.white

    /// Quieter ink **on an accent fill** — the captions under the number on the Today hero.
    ///
    /// Distinct from ``accentPrimarySoft``, which it used to borrow: that one is a pale *fill* on
    /// the app's own ground and has to darken in dark mode, while this stays pale in both, because
    /// the orange under it does not change. The dark value is lifted to land at the same 2.6:1 on
    /// the lifted accent that the light value gives on `#FF5C00`.
    static let foregroundOnAccentMuted = dynamic(light: 0xFFE8D6, dark: 0xFFF3EC)

    /// Lighter than ``surfacePrimary`` in dark mode, not darker: in light mode the tab bar is the
    /// one dark thing on screen, and inverting that relationship leaves it invisible.
    static let navBarBackground        = dynamic(light: 0x1A1326, dark: 0x262032)
    static let navBarInactive          = dynamic(light: 0x9A93A3, dark: 0x9A93A3)
    static let borderSubtle            = dynamic(light: 0xF0E6D9, dark: 0x3B3550)

    // The four grade colours are deliberately identical in both modes: they read 5.3–9.6:1 as ink
    // on the dark surfaces they are actually used on, against 2.0–3.6:1 on cream, so there is
    // nothing to fix — and lifting them would only cost the grade buttons, where they are fills
    // under white ink.
    static let srsGood                 = dynamic(light: 0x21C97A, dark: 0x21C97A)
    static let srsAgain                = dynamic(light: 0xFF4E64, dark: 0xFF4E64)
    static let srsHard                 = dynamic(light: 0xF5A524, dark: 0xF5A524)
    static let srsEasy                 = dynamic(light: 0x3B82F6, dark: 0x3B82F6)

    /// The one signal colour that does move: `#D92C2C` is 3.9:1 on the dark ground, under the
    /// 4.5:1 it needs as the label on a destructive row.
    static let danger                  = dynamic(light: 0xD92C2C, dark: 0xFF6B6E)
    static let dangerSoft              = srsAgain.opacity(0.08)

    // Shadow tints — accent "glow" and neutral elevation shadows by depth. The neutral ones go
    // fully black and heavier in dark mode: a tinted shadow is invisible on a dark ground, which
    // is exactly where a raised card most needs an edge.
    static let shadowAccent            = accentPrimary.opacity(0.2)
    static let shadowElevationLow      = elevation(light: 0.05, dark: 0.25)
    static let shadowElevationMedium   = elevation(light: 0.07, dark: 0.30)
    static let shadowElevationHigh     = elevation(light: 0.08, dark: 0.35)

    /// A colour that resolves per trait collection, so it follows whatever scheme the view is in.
    private static func dynamic(light: UInt32, dark: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(rgb: dark) : UIColor(rgb: light) })
    }

    private static func elevation(light: CGFloat, dark: CGFloat) -> Color {
        Color(UIColor { UIColor.black.withAlphaComponent($0.userInterfaceStyle == .dark ? dark : light) })
    }
}

private extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: 1
        )
    }
}
