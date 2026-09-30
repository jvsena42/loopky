import SwiftUI

/// The frame every signup, backup, restore and unregistered-key screen sits in.
///
/// Mirrors Android's `SignupScaffold`: a back link, a large title, a subtitle, the screen's own
/// content, and — last — the error block, so a failure appears under what the user was doing
/// rather than displacing it.
struct SignupScaffold<Content: View>: View {
    var title: LocalizedStringKey
    var subtitle: String?
    /// Shown beneath the content. A `SignupError` mapped through `SignupErrorCopy`, or nil.
    var errorTitle: String?
    var errorMessage: String?
    var onBack: () -> Void
    @ViewBuilder var content: () -> Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Button("deck_detail_back", action: onBack)
                    .font(.system(size: 14))
                    .foregroundStyle(LoopkyColor.accentSecondary)
                    .accessibilityIdentifier("signup_back")

                Spacer().frame(height: 16)
                Text(title)
                    .font(.system(size: 28, weight: .heavy))
                    .foregroundStyle(LoopkyColor.foregroundPrimary)
                    .fixedSize(horizontal: false, vertical: true)

                if let subtitle, !subtitle.isEmpty {
                    Spacer().frame(height: 12)
                    Text(verbatim: subtitle)
                        .font(.system(size: 15))
                        .foregroundStyle(LoopkyColor.foregroundSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Spacer().frame(height: 28)
                content()

                if let errorTitle {
                    Spacer().frame(height: 20)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(verbatim: errorTitle)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(LoopkyColor.danger)
                        if let errorMessage {
                            Text(verbatim: errorMessage)
                                .font(.system(size: 13))
                                .foregroundStyle(LoopkyColor.foregroundSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }

                Spacer().frame(height: 32)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 24)
            .contentPane(PaneWidth.focused)
        }
        .loopkyScreenBackground()
        .navigationBarHidden(true)
    }
}

/// The permanent warning above a seed phrase field, in both directions.
///
/// Mirrors Android's `SeedPhraseWarning`. **Permanent, not a toast, and deliberately not
/// conditional on detecting anything** — a Bitcoin seed and a Pubky seed are the same twelve
/// words, and a false negative reads as an all-clear.
struct SeedPhraseWarning: View {
    var text: LocalizedStringKey

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Text("⚠️").font(.system(size: 16))
            Text(text)
                .font(.system(size: 13))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12).fill(LoopkyColor.danger.opacity(0.12)))
    }
}

/// A full-width primary button with an inline spinner, as the identity screens use throughout.
struct SignupPrimaryButton: View {
    var title: LocalizedStringKey
    var isLoading: Bool = false
    var isEnabled: Bool = true
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            if isLoading {
                ProgressView().controlSize(.small).tint(LoopkyColor.foregroundOnAccent)
            } else {
                Text(title)
            }
        }
        .buttonStyle(.loopkyFilled)
        .disabled(!isEnabled || isLoading)
    }
}

/// A tappable card offering one route forward — signup methods, backup methods, restore methods.
///
/// A disabled card is greyed and says why rather than disappearing: an unavailable method the user
/// came looking for should still be findable.
struct MethodCard: View {
    var title: LocalizedStringKey
    var detail: LocalizedStringKey?
    /// Shown in accent when enabled; replaced by "unavailable" when not.
    var trailing: String?
    var isEnabled: Bool = true
    var isDone: Bool = false
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline) {
                    Text(title)
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(isEnabled ? LoopkyColor.foregroundPrimary : LoopkyColor.foregroundMuted)
                    Spacer()
                    if isDone {
                        Text("✓ \(NSLocalizedString("backup_done_label", comment: ""))")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(LoopkyColor.accentSecondary)
                    } else if !isEnabled {
                        Text("signup_card_unavailable")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(LoopkyColor.foregroundMuted)
                    } else if let trailing {
                        Text(verbatim: trailing)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(LoopkyColor.accentPrimary)
                    }
                }
                if let detail {
                    Text(detail)
                        .font(.system(size: 12))
                        .foregroundStyle(LoopkyColor.foregroundMuted)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
            .background(RoundedRectangle(cornerRadius: 16).fill(LoopkyColor.surfaceCard))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(LoopkyColor.borderSubtle, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
    }
}
