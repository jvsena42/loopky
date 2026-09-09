import SwiftUI

/// "Make your own copy?" — raised by Edit on a deck you follow, the only route to a copy (#254).
///
/// A sheet rather than an alert, and that is the whole reason this file exists: an alert's `title`
/// and `message` are snapshotted when it is presented, so the "pick a different name" line could
/// never appear as the reader typed — only the confirm button's disabled state updated, leaving a
/// greyed button with nothing saying why.
///
/// Two jobs. It states the two things that make a copy different from the follow the reader already
/// has — no author updates, progress starts over — in one sentence, because a paragraph here is a
/// paragraph nobody reads. And it takes the copy's own name, which is **required** and may not be
/// the source's: the copy lands in a library that already holds the deck it forked, and two rows
/// with one title are indistinguishable.
struct CopyDeckSheet: View {
    let sourceTitle: String
    let cardCount: Int
    /// The shared rule (`DeckDetailUiState.Content.isSourceName`), not a comparison reinvented here.
    let isSourceName: (String) -> Bool
    var onConfirm: (String) -> Void
    var onCancel: () -> Void

    /// The field owns its text while typing: binding `get` to state that round-trips through a
    /// ViewModel drops characters.
    @State private var title = ""
    @FocusState private var isFocused: Bool

    private var trimmed: String {
        title.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Reported as you type rather than on tapping Copy: the field is right there, and a rejection
    /// that arrives after the tap reads as the button being broken.
    private var clashes: Bool {
        !trimmed.isEmpty && isSourceName(trimmed)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            RoundedRectangle(cornerRadius: 2)
                .fill(LoopkyColor.borderSubtle)
                .frame(width: 36, height: 4)
                .frame(maxWidth: .infinity)
                .padding(.top, 12)

            Text("deck_detail_clone_dialog_title")
                .font(.system(size: 20, weight: .heavy))
                .foregroundColor(LoopkyColor.foregroundPrimary)

            // localizedStringWithFormat, not String(format:): the message is a plural entry,
            // and only this one resolves the variation (#267).
            Text(verbatim: String.localizedStringWithFormat(
                NSLocalizedString("deck_detail_clone_dialog_message", comment: ""),
                cardCount
            ))
            .font(.system(size: 14))
            .foregroundColor(LoopkyColor.foregroundSecondary)
            .fixedSize(horizontal: false, vertical: true)

            VStack(alignment: .leading, spacing: 8) {
                // The source title is the placeholder, never the initial value: prefilled, everyone
                // would tap straight past it, which is the whole of what the rename is for.
                TextField(sourceTitle, text: $title)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(LoopkyColor.foregroundPrimary)
                    // Autocorrect off: a deck name is a name, and on a Portuguese keyboard it
                    // turned "Diag copy two" into "Diga copa tão".
                    .autocorrectionDisabled()
                    .focused($isFocused)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                    .background(
                        RoundedRectangle(cornerRadius: 14).fill(LoopkyColor.surfacePrimary)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 14)
                            .stroke(
                                clashes ? LoopkyColor.danger : LoopkyColor.borderSubtle,
                                lineWidth: 1.5
                            )
                    )
                    .accessibilityIdentifier("deck_clone_title")

                if clashes {
                    Text("deck_detail_clone_name_same")
                        .font(.system(size: 13))
                        .foregroundColor(LoopkyColor.danger)
                        .accessibilityIdentifier("deck_clone_name_error")
                }
            }

            HStack(spacing: 12) {
                Button("deck_detail_clone_cancel", action: onCancel)
                    .buttonStyle(.loopkyOutline)
                Button("deck_detail_clone_confirm") { onConfirm(trimmed) }
                    .buttonStyle(LoopkyFilledButtonStyle(
                        fill: trimmed.isEmpty || clashes ? Color.gray : LoopkyColor.accentPrimary,
                        fontSize: 16
                    ))
                    .disabled(trimmed.isEmpty || clashes)
                    .accessibilityIdentifier("deck_clone_confirm")
            }

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 32)
        .presentationDetents([.medium])
        .presentationDragIndicator(.hidden)
        .onAppear { isFocused = true }
    }
}

#Preview {
    CopyDeckSheet(
        sourceTitle: "Spanish Basics",
        cardCount: 42,
        isSourceName: { $0.caseInsensitiveCompare("Spanish Basics") == .orderedSame },
        onConfirm: { _ in },
        onCancel: {}
    )
}
