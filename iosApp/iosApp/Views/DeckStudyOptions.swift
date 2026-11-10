import SwiftUI
import Shared

/// The deck's study opt-ins, plus the language pair Listen and Speak need.
///
/// Shared by the publish flow and the deck editor, mirroring Android's
/// `ui/components/DeckStudyOptions.kt`, so the two screens cannot drift into offering different
/// options or validating them differently.
///
/// All four default **off**. Listen and Speak because turning either on obliges the author to say
/// what language each side is in — without it the OS engines fall back to the *reader's* locale,
/// so an undeclared Spanish deck is read aloud in an English accent and graded by an English
/// model. Type and Both directions default off for their own reasons, and neither is gated on the
/// language pair: a string comparison and a side swap have no engine to substitute a locale into.
struct DeckStudyOptions: View {
    var listenEnabled: Bool
    var speakEnabled: Bool
    var typeEnabled: Bool
    var reverseEnabled: Bool
    var speechBothSides: Bool = false
    var frontLang: String?
    var backLang: String?
    /// Set when an audio opt-in is on and the pair is still missing.
    var languagesRequired: Bool = false

    var onToggleListen: () -> Void = {}
    var onToggleSpeak: () -> Void = {}
    var onToggleType: () -> Void = {}
    var onToggleReverse: () -> Void = {}
    var onToggleSpeechBothSides: () -> Void = {}
    var onFrontLangSelected: (String) -> Void = { _ in }
    var onBackLangSelected: (String) -> Void = { _ in }

    /// The language pickers appear only once an audio option asks for them — they are meaningless
    /// to a deck using only Type or Both directions.
    private var showsLanguages: Bool { listenEnabled || speakEnabled }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("publish_card_options_label")
                .font(.system(size: 10, weight: .bold))
                .kerning(0.8)
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .padding(.bottom, 10)

            VStack(spacing: 0) {
                optionRow("publish_listen_title", "publish_listen_subtitle",
                          isOn: listenEnabled, action: onToggleListen)
                divider
                optionRow("publish_speak_title", "publish_speak_subtitle",
                          isOn: speakEnabled, action: onToggleSpeak)
                divider
                optionRow("publish_type_title", "publish_type_subtitle",
                          isOn: typeEnabled, action: onToggleType)
                divider
                optionRow("publish_reverse_title", "publish_reverse_subtitle",
                          isOn: reverseEnabled, action: onToggleReverse)
                if showsLanguages {
                    divider
                    optionRow("publish_both_sides_title", "publish_both_sides_subtitle",
                              isOn: speechBothSides, action: onToggleSpeechBothSides)
                        .accessibilityIdentifier("publish_both_sides_toggle")
                }
            }
            .padding(.vertical, 4)
            .background(RoundedRectangle(cornerRadius: 16).fill(LoopkyColor.surfaceCard))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(LoopkyColor.borderSubtle, lineWidth: 1))

            if showsLanguages { languageSection }
        }
    }

    private var divider: some View {
        Rectangle().fill(LoopkyColor.borderSubtle).frame(height: 1).padding(.leading, 16)
    }

    private func optionRow(
        _ title: LocalizedStringKey,
        _ subtitle: LocalizedStringKey,
        isOn: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Toggle(isOn: Binding(get: { isOn }, set: { _ in action() })) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(LoopkyColor.foregroundPrimary)
                Text(subtitle)
                    .font(.system(size: 12))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .tint(LoopkyColor.accentPrimary)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    private var languageSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("deck_languages_hint")
                .font(.system(size: 12))
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .fixedSize(horizontal: false, vertical: true)

            languagePicker("deck_front_language_label", selected: frontLang, onSelect: onFrontLangSelected)
            languagePicker("deck_back_language_label", selected: backLang, onSelect: onBackLangSelected)

            if languagesRequired {
                Text("deck_languages_required")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(LoopkyColor.danger)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.top, 14)
    }

    private func languagePicker(
        _ label: LocalizedStringKey,
        selected: String?,
        onSelect: @escaping (String) -> Void
    ) -> some View {
        HStack {
            Text(label)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(LoopkyColor.foregroundSecondary)
            Spacer()
            Menu {
                ForEach(SpeechLanguages.shared.COMMON, id: \.self) { tag in
                    Button(Self.displayName(for: tag)) { onSelect(tag) }
                }
            } label: {
                HStack(spacing: 4) {
                    Text(selected.map(Self.displayName(for:)) ?? NSLocalizedString("deck_language_unset", comment: ""))
                        .font(.system(size: 14, weight: .semibold))
                    Image(systemName: "chevron.up.chevron.down").font(.system(size: 10, weight: .bold))
                }
                .foregroundStyle(selected == nil ? LoopkyColor.foregroundMuted : LoopkyColor.accentPrimary)
            }
        }
    }

    /// "es-ES" reads as "Spanish (Spain)" in the viewer's own language. The raw BCP-47 tag is
    /// not something an author should have to decode, and `Locale` already knows the names.
    static func displayName(for tag: String) -> String {
        Locale.current.localizedString(forIdentifier: tag.replacingOccurrences(of: "-", with: "_"))
            ?? Locale.current.localizedString(forIdentifier: tag)
            ?? tag
    }
}
