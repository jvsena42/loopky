import SwiftUI

extension View {
    /// Paints a screen's ground so it reaches all four edges of the display.
    ///
    /// **Not** `.background(color.ignoresSafeArea())`, which is what every screen here used to do
    /// and what silently fails inside a `NavigationStack` destination: the background sizes to the
    /// pushed view's frame, that frame is already inset by the safe area, and `ignoresSafeArea` on
    /// the colour has no inset left to escape. The result is a white band behind the status bar and
    /// another behind the home indicator — invisible on the cream screens, where the window's own
    /// white is close enough to `surfacePrimary` to pass, and obvious the moment a screen paints
    /// `surfaceSecondary` or anything darker.
    ///
    /// A `ZStack` with a bare `Color` underneath has no such problem: a `Color` is greedy, so it
    /// fills the stack and then genuinely does ignore the safe area. Alignment stays centred —
    /// SwiftUI already centres a non-greedy root view in a destination, so content that does not
    /// fill the screen lands exactly where it did before.
    func loopkyScreenBackground(_ color: Color = LoopkyColor.surfacePrimary) -> some View {
        ZStack {
            color.ignoresSafeArea()
            self
        }
    }
}
