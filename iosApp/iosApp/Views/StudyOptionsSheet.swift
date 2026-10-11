import SwiftUI
import Shared

/// What Edit raises on a deck you follow: your own study opt-ins, and under them the way to a copy.
///
/// In that order on purpose. The switches cost nothing and are what most readers came to change; a
/// copy gives up the author's updates and the reader's progress, so it is the second step and never
/// the first thing asked. Mirrors Android's `ui/decks/StudyOptionsSheet.kt`.
struct StudyOptionsSheet: View {
    let options: StudyOptionsModel
    var onChange: (StudyFeature, Bool) -> Void = { _, _ in }
    var onReset: () -> Void = {}
    var onSave: () -> Void = {}
    var onCopy: () -> Void = {}

    private var chosen: StudyFeatures { options.chosen }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("study_options_title")
                    .font(.system(size: 20, weight: .heavy))
                    .foregroundStyle(LoopkyColor.foregroundPrimary)

                Text("study_options_subtitle")
                    .font(.system(size: 14))
                    .foregroundStyle(LoopkyColor.foregroundSecondary)
                    .fixedSize(horizontal: false, vertical: true)

                toggles

                if !options.speechReady {
                    Text("study_options_speech_unavailable")
                        .font(.system(size: 12))
                        .foregroundStyle(LoopkyColor.foregroundMuted)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("study_options_speech_unavailable")
                }

                if options.differsFromAuthor {
                    Button("study_options_reset", action: onReset)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(LoopkyColor.accentPrimary)
                        .frame(maxWidth: .infinity)
                        .disabled(options.isSaving)
                        .accessibilityIdentifier("study_options_reset")
                }

                Button("deck_editor_save", action: onSave)
                    .buttonStyle(LoopkyFilledButtonStyle(fill: LoopkyColor.accentPrimary, fontSize: 16))
                    .disabled(options.isSaving)
                    .accessibilityIdentifier("study_options_save")

                copyRow
            }
            .padding(.horizontal, 20)
            .padding(.top, 28)
            .padding(.bottom, 32)
            // A sheet is full-width on a phone and a fixed card on an iPad; the cap only bites on
            // a phone held sideways.
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
        }
        .background(LoopkyColor.surfaceSecondary)
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .interactiveDismissDisabled(options.isSaving)
    }

    private var toggles: some View {
        VStack(spacing: 0) {
            row("publish_listen_title", "publish_listen_subtitle", "listen",
                isOn: chosen.listen && options.speechReady, enabled: options.speechReady) {
                onChange(.listen, !chosen.listen)
            }
            divider
            row("publish_speak_title", "publish_speak_subtitle", "speak",
                isOn: chosen.speak && options.speechReady, enabled: options.speechReady) {
                onChange(.speak, !chosen.speak)
            }
            divider
            row("publish_type_title", "publish_type_subtitle", "type", isOn: chosen.type) {
                onChange(.typing, !chosen.type)
            }
            divider
            row("publish_reverse_title", "publish_reverse_subtitle", "reverse", isOn: chosen.reverse) {
                onChange(.reverse, !chosen.reverse)
            }
        }
        .padding(.vertical, 4)
        .background(RoundedRectangle(cornerRadius: 16).fill(LoopkyColor.surfaceCard))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(LoopkyColor.borderSubtle, lineWidth: 1))
    }

    private var divider: some View {
        Rectangle().fill(LoopkyColor.borderSubtle).frame(height: 1).padding(.leading, 16)
    }

    private func row(
        _ title: LocalizedStringKey,
        _ subtitle: LocalizedStringKey,
        _ identifier: String,
        isOn: Bool,
        enabled: Bool = true,
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
        .disabled(!enabled || options.isSaving)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .accessibilityIdentifier("study_options_\(identifier)")
    }

    /// A `Button`, not a tap gesture: VoiceOver and `snapshot-ui` find nothing else.
    private var copyRow: some View {
        Button(action: onCopy) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("study_options_copy_title")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(LoopkyColor.foregroundPrimary)
                    Text("study_options_copy_subtitle")
                        .font(.system(size: 12))
                        .foregroundStyle(LoopkyColor.foregroundMuted)
                        .multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
            }
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 16).fill(LoopkyColor.surfaceCard))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(LoopkyColor.borderSubtle, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(options.isSaving)
        .padding(.top, 10)
        .accessibilityIdentifier("study_options_copy")
    }
}
