package com.github.jvsena42.loopky.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Loopky's brand palette. Hand-maintained and the source of truth for Android; the iOS side
 * mirrors it in `LoopkyColor.swift`, so a change here wants the same change there.
 */
@Immutable
data class LoopkyColors(
    val surfacePrimary: Color,
    val surfaceSecondary: Color,
    val surfaceCard: Color,
    val accentPrimary: Color,
    val accentPrimarySoft: Color,
    val accentSecondary: Color,
    val accentSecondarySoft: Color,
    val foregroundPrimary: Color,
    val foregroundSecondary: Color,
    val foregroundMuted: Color,
    val foregroundOnAccent: Color,
    /**
     * Quieter ink **on an accent fill** — a caption under the number on the Today hero.
     *
     * Distinct from [accentPrimarySoft], which it used to borrow: that one is a pale *fill* on the
     * app's own ground and has to darken in dark mode, while this stays pale in both, because the
     * orange it sits on does not change. Sharing one token hid the conflict for as long as there
     * was only a light palette.
     */
    val foregroundOnAccentMuted: Color,
    val navBarBackground: Color,
    val navBarInactive: Color,
    val borderSubtle: Color,
    val srsGood: Color,
    val srsAgain: Color,
    val srsHard: Color,
    val srsEasy: Color,
    val danger: Color,
    val dangerSoft: Color,
    // Shadow tints — accent "glow" and neutral elevation shadows by depth.
    val shadowAccent: Color,
    val shadowElevationLow: Color,
    val shadowElevationMedium: Color,
    val shadowElevationHigh: Color,
    val shadowElevationXHigh: Color,
)

val LoopkyLightColors = LoopkyColors(
    surfacePrimary = Color(0xFFFFFBF5),
    surfaceSecondary = Color(0xFFFFF4EA),
    surfaceCard = Color(0xFFFFFFFF),
    accentPrimary = Color(0xFFFF5C00),
    accentPrimarySoft = Color(0xFFFFE8D6),
    accentSecondary = Color(0xFF7A4CFF),
    accentSecondarySoft = Color(0xFFE9E0FF),
    foregroundPrimary = Color(0xFF1B1B1F),
    foregroundSecondary = Color(0xFF5A5A66),
    foregroundMuted = Color(0xFF8B8B99),
    foregroundOnAccent = Color(0xFFFFFFFF),
    foregroundOnAccentMuted = Color(0xFFFFE8D6),
    navBarBackground = Color(0xFF1A1326),
    navBarInactive = Color(0xFF9A93A3),
    borderSubtle = Color(0xFFF0E6D9),
    srsGood = Color(0xFF21C97A),
    srsAgain = Color(0xFFFF4E64),
    // Amber, not orange: #FF8A1F sat right next to accentPrimary #FF5C00, so "Hard"
    // read as the brand action colour.
    srsHard = Color(0xFFF5A524),
    srsEasy = Color(0xFF3B82F6),
    danger = Color(0xFFD92C2C),
    dangerSoft = Color(0x14FF4E64),
    shadowAccent = Color(0x33FF5C00),
    shadowElevationLow = Color(0x0D1A1326),
    shadowElevationMedium = Color(0x121A1326),
    shadowElevationHigh = Color(0x141A1326),
    shadowElevationXHigh = Color(0x261A1326),
)

/**
 * The dark palette.
 *
 * Three decisions worth keeping, each of which the first draft got wrong.
 *
 * **The neutrals are barely tinted.** They carry the brand plum at ~15% saturation rather than the
 * ~29% of `#1A1326`, the nav bar's light-mode colour. A dark ground built at the brand's own chroma
 * does not read as "Loopky in the dark", it reads as a purple app — and Material's dark guidance
 * warns off large areas of saturated colour for the eye strain besides. The hue is still there; it
 * is what keeps these from being the default grey.
 *
 * The *steps* between them are wide, though — 1.37:1 from ground to card rather than the 1.17:1 a
 * first pass had. On a dark theme the tonal step is the only thing separating a raised surface
 * from the one under it: a shadow is invisible against near-black, so the study card and every
 * bottom sheet had no visible edge at all.
 *
 * **The accent lifts, the ink on it does not change relationship.** `#FF6B2C` clears 6.6:1 against
 * the ground, where light mode's `#FF5C00` manages only 3.0:1 against cream — so the accent is
 * *more* legible here, not less. [foregroundOnAccentMuted] is lightened to `#FFF3EC` to land at
 * 2.6:1 on the lifted orange, the same ratio the cream tint gives in light mode: the hero's caption
 * reads identically in both.
 *
 * **The four SRS colours are the light values, deliberately unchanged.** They read 5.3–9.6:1 as ink
 * on the surfaces they are actually used on — against 2.0–3.6:1 on cream — so there is nothing to
 * fix, and lifting them would only cost the grade buttons, where they are fills under white ink
 * (2.16:1 becomes 1.88:1). A dark theme is not an obligation to brighten everything.
 */
val LoopkyDarkColors = LoopkyColors(
    surfacePrimary = Color(0xFF0D0B11),
    surfaceSecondary = Color(0xFF1E1A28),
    surfaceCard = Color(0xFF2D2839),
    accentPrimary = Color(0xFFFF6B2C),
    accentPrimarySoft = Color(0xFF38221A),
    accentSecondary = Color(0xFF9B7BFF),
    accentSecondarySoft = Color(0xFF2B2443),
    foregroundPrimary = Color(0xFFEDEBF1),
    foregroundSecondary = Color(0xFFB5AEBD),
    foregroundMuted = Color(0xFF9C94A8),
    foregroundOnAccent = Color(0xFFFFFFFF),
    foregroundOnAccentMuted = Color(0xFFFFF3EC),
    // Lighter than [surfacePrimary], not darker: in light mode the nav bar is the one dark thing on
    // screen, and inverting that relationship leaves it invisible against the ground. Sitting just
    // above the card tone is what Material means by elevation on a dark theme.
    navBarBackground = Color(0xFF262032),
    navBarInactive = Color(0xFF9A93A3),
    borderSubtle = Color(0xFF3B3550),
    srsGood = Color(0xFF21C97A),
    srsAgain = Color(0xFFFF4E64),
    srsHard = Color(0xFFF5A524),
    srsEasy = Color(0xFF3B82F6),
    // The one signal colour that does move: `#D92C2C` is 3.9:1 on this ground, under the 4.5:1 it
    // needs as the label on a destructive row.
    danger = Color(0xFFFF6B6E),
    dangerSoft = Color(0x1FFF4E64),
    shadowAccent = Color(0x33FF6B2C),
    // Black rather than the light palette's plum tint: a tinted shadow is invisible on a dark
    // ground, which is exactly where a raised card most needs an edge.
    shadowElevationLow = Color(0x40000000),
    shadowElevationMedium = Color(0x4D000000),
    shadowElevationHigh = Color(0x59000000),
    shadowElevationXHigh = Color(0x73000000),
)
