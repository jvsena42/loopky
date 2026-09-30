import SwiftUI

/// What this deck can be studied *with* — Listen, Speak, Type the answer and both directions.
///
/// The four opt-ins were settable at publish and in the editor and shown nowhere else, so the one
/// person who could not find out what a deck offered was the reader deciding whether to keep it.
/// Labels are the editor's own (`DeckStudyOptions`), so the row a reader sees names the switches
/// its author actually flipped. Mirrors Android's `ui/components/DeckFeatureChips.kt`.
///
/// Read-only: these are the author's decisions, not the reader's, and a control here would be
/// editing someone else's deck.
struct DeckFeatureBadges: View {
    var listenEnabled: Bool
    var speakEnabled: Bool
    var typeEnabled: Bool
    var reverseEnabled: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("deck_detail_study_modes_label")
                .font(.system(size: 10, weight: .bold))
                .kerning(0.8)
                .foregroundStyle(LoopkyColor.foregroundMuted)

            // Scrolls sideways, like the tag row above it: four labels do not fit a phone's width,
            // and this is the pattern the screen already uses for a row of pills.
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    if listenEnabled {
                        badge("publish_listen_title", "speaker.wave.2.fill", "listen")
                    }
                    if speakEnabled {
                        badge("publish_speak_title", "mic.fill", "speak")
                    }
                    if typeEnabled {
                        badge("publish_type_title", "keyboard", "type")
                    }
                    if reverseEnabled {
                        badge("publish_reverse_title", "arrow.left.arrow.right", "reverse")
                    }
                }
            }
        }
    }

    /// All four take the *primary* accent, rather than the editor's alternating pair: the
    /// secondary one is what `TagChipView` is painted in, and a purple pill under the tags reads
    /// as one more tag.
    private func badge(
        _ label: LocalizedStringKey,
        _ symbol: String,
        _ identifier: String
    ) -> some View {
        Label(label, systemImage: symbol)
            .font(.system(size: 12, weight: .bold))
            .labelStyle(.titleAndIcon)
            .foregroundStyle(LoopkyColor.accentPrimary)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(Capsule().fill(LoopkyColor.accentPrimarySoft))
            .accessibilityIdentifier("deck_feature_\(identifier)")
    }
}

#Preview {
    VStack(alignment: .leading, spacing: 24) {
        DeckFeatureBadges(listenEnabled: true, speakEnabled: true, typeEnabled: true, reverseEnabled: true)
        DeckFeatureBadges(listenEnabled: true, speakEnabled: false, typeEnabled: false, reverseEnabled: true)
    }
    .padding(20)
}
