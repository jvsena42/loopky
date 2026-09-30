import SwiftUI
import Shared

/// Paste-to-Import, spec §5: paste a list, watch it parse, advance to publish.
///
/// Pure layout — every number and label comes from `PasteImportViewModel` through `PasteScreen`.
///
/// Laid out in Android's three regions rather than one long scroll, and for the reason Android
/// stopped scrolling: the paste box is **fixed height**, so a paste of any real size scrolls
/// inside it instead of pushing Next off the bottom of the screen and under the keyboard. Only
/// the middle region — the preview or the worked examples — takes the leftover height.
struct PasteView: View {
    var state: PasteViewState = PasteViewState()
    @Binding var text: String
    var onCancel: () -> Void = {}
    var onNext: () -> Void = {}
    var onSeparatorPicked: (Separator) -> Void = { _ in }

    /// Drives hiding the public notice while the keyboard is up, the way Android's `imePadding`
    /// + `WindowInsets.ime` check does: only the Next button should ride above the keyboard.
    @FocusState private var editorFocused: Bool
    @State private var pickingSeparator = false

    var body: some View {
        VStack(spacing: 0) {
            topBar
            content
        }
        .loopkyScreenBackground()
        .navigationBarHidden(true)
        .sheet(isPresented: $pickingSeparator) {
            SeparatorPickerSheet(
                current: state.activeSeparator,
                onPick: {
                    onSeparatorPicked($0)
                    pickingSeparator = false
                }
            )
        }
    }

    /// Cancel leading, title optically centred — a `ZStack`, not an `HStack` with spacers, so a
    /// long localized "Cancel" cannot shove the title off centre.
    private var topBar: some View {
        ZStack {
            Text("paste_title")
                .font(.system(size: 18, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            HStack {
                Button(action: onCancel) {
                    Text("paste_cancel")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(LoopkyColor.accentPrimary)
                }
                .accessibilityIdentifier("paste_cancel")
                Spacer()
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
    }

    private var content: some View {
        VStack(spacing: 0) {
            // Top fixed region: input + detected-separator summary.
            VStack(spacing: 18) {
                editor
                if state.isParsed && state.hasDetectedSeparator { parseSummaryRow }
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)

            // Middle filling region: preview cards, or the worked examples.
            middleRegion
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(.top, 18)

            bottomRegion
        }
        // A paste box and its worked examples are prose-shaped; the format chip on each example
        // row was otherwise ending up a full screen away from the title it labels.
        .contentPane()
    }

    private var editor: some View {
        TextEditor(text: $text)
            .font(.system(size: 14))
            .foregroundStyle(LoopkyColor.foregroundPrimary)
            .scrollContentBackground(.hidden)
            .focused($editorFocused)
            // Fixed, not a minimum: the field used to grow with its content, so a paste of any
            // real size pushed Next off the bottom of the screen. It scrolls inside these bounds.
            .frame(height: Self.editorHeight)
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 16).fill(LoopkyColor.surfaceCard))
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .stroke(
                        editorFocused ? LoopkyColor.accentPrimary : LoopkyColor.borderSubtle,
                        lineWidth: editorFocused ? 2 : 1
                    )
            )
            .overlay(alignment: .topLeading) {
                if text.isEmpty { placeholder }
            }
            .accessibilityIdentifier("paste_input")
    }

    private var placeholder: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("paste_input_placeholder_title")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(LoopkyColor.foregroundMuted)
            Text("paste_input_placeholder_subtitle")
                .font(.system(size: 13))
                .foregroundStyle(LoopkyColor.foregroundMuted.opacity(0.6))
        }
        .padding(.horizontal, 17)
        .padding(.vertical, 20)
        .allowsHitTesting(false)
    }

    /// The chip is the override control — spec §5.2 asks for "tap to change" — and it is also
    /// where both parse warnings live, as on Android. A separate notice row per warning pushed
    /// the preview down the screen to say something the chip was already positioned to say.
    private var parseSummaryRow: some View {
        HStack {
            Button { pickingSeparator = true } label: {
                HStack(spacing: 6) {
                    Image(systemName: chipWarning == nil ? "checkmark" : "exclamationmark.triangle.fill")
                        .font(.system(size: 11, weight: .bold))
                    Text(chipWarning ?? String(
                        format: NSLocalizedString("paste_detected_separator", comment: ""),
                        state.separatorLabel
                    ))
                    .font(.system(size: 12, weight: .semibold))
                }
                .foregroundStyle(chipWarning == nil ? LoopkyColor.accentSecondary : LoopkyColor.danger)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(
                    Capsule().fill(
                        chipWarning == nil ? LoopkyColor.accentSecondarySoft : LoopkyColor.dangerSoft
                    )
                )
            }
            .accessibilityIdentifier("paste_separator_chip")

            Spacer()

            Text(String.localizedStringWithFormat(
                NSLocalizedString("paste_card_count", comment: ""),
                state.cardCount
            ))
            .font(.system(size: 13, weight: .medium))
            .foregroundStyle(LoopkyColor.foregroundMuted)
        }
    }

    /// What the chip says instead of the detected separator, in Android's precedence: nothing
    /// parsed at all outranks some rows parsing short.
    private var chipWarning: String? {
        if state.noPatternDetected { return NSLocalizedString("paste_chip_no_pattern", comment: "") }
        if state.hasIncompleteCards {
            return String.localizedStringWithFormat(
                NSLocalizedString("paste_chip_incomplete", comment: ""),
                state.incompleteCardCount
            )
        }
        return nil
    }

    @ViewBuilder
    private var middleRegion: some View {
        if state.hasPreviewableCard {
            VStack(alignment: .leading, spacing: 12) {
                sectionLabel("paste_preview_label")
                    .padding(.horizontal, 20)
                // Horizontal, and each card fills the region's height: the preview is a row of
                // flashcards to flick through, not a table of two columns.
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 12) {
                        ForEach(Array(state.previewCards.enumerated()), id: \.element.id) { index, card in
                            PreviewCardItem(
                                index: index + 1,
                                total: state.cardCount,
                                front: card.front,
                                back: card.back
                            )
                        }
                    }
                    // Room for the 8pt card shadow to render instead of being clipped at the
                    // scroll bounds, while keeping the 20pt screen margin.
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
                }
            }
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    sectionLabel("paste_try_pasting_label")
                    ExampleCardView(
                        title: "Vocab list",
                        separator: KotlinInterop.separatorLabel(Separator.EmDash.shared),
                        lines: ["hola — hello", "gracias — thank you"]
                    )
                    ExampleCardView(
                        title: "Glossary",
                        separator: KotlinInterop.separatorLabel(Separator.Colon.shared),
                        lines: ["mitosis: cell division", "osmosis: water moves across a membrane"]
                    )
                    ExampleCardView(
                        title: "Notion table",
                        separator: KotlinInterop.separatorLabel(Separator.MarkdownTable.shared),
                        lines: ["| capital | France |", "| currency | euro |"]
                    )
                }
                .padding(.horizontal, 20)
            }
        }
    }

    private var bottomRegion: some View {
        VStack(spacing: 12) {
            // Hidden while the keyboard is open so only Next floats above it, instead of the
            // notice riding up with it.
            if !editorFocused { publicNotice }

            if let errorMessage = state.errorMessage {
                Text(errorMessage)
                    .font(.system(size: 14))
                    .foregroundStyle(LoopkyColor.danger)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            if let validation = state.validationMessage {
                Text(validation)
                    .font(.system(size: 13))
                    .foregroundStyle(LoopkyColor.danger)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("paste_validation")
            }

            // Deliberately never disabled: the ViewModel answers the tap with the reason it
            // cannot advance. A greyed-out button that looks tappable and swallows the tap was
            // the bug the validation message replaced.
            Button("paste_next", action: onNext)
                .buttonStyle(.loopkyFilled)
                .accessibilityIdentifier("paste_next")
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
    }

    private func sectionLabel(_ key: LocalizedStringKey) -> some View {
        Text(key)
            .font(.system(size: 10, weight: .bold))
            .kerning(0.8)
            .foregroundStyle(LoopkyColor.foregroundMuted)
    }

    private var publicNotice: some View {
        HStack(spacing: 6) {
            Text("🔗").font(.system(size: 14))
            Text("paste_public_notice")
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(LoopkyColor.accentSecondary)
        }
        .frame(maxWidth: .infinity)
    }

    /// Matches Android's fixed paste-field height, so Next keeps its place whatever is pasted.
    private static let editorHeight: CGFloat = 176
}

/// One card in the horizontal preview strip — a flashcard in miniature: index, front, the accent
/// rule that stands in for the fold, then the back.
private struct PreviewCardItem: View {
    let index: Int
    let total: Int
    let front: String
    let back: String

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(String(
                format: NSLocalizedString("paste_card_index", comment: ""),
                index, total
            ))
            .font(.system(size: 11))
            .foregroundStyle(LoopkyColor.foregroundMuted)

            Spacer(minLength: 0)

            VStack(alignment: .leading, spacing: 12) {
                Text(front.isEmpty ? NSLocalizedString("paste_blank_placeholder", comment: "") : front)
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(LoopkyColor.foregroundPrimary)
                Rectangle()
                    .fill(LoopkyColor.accentPrimary)
                    .frame(width: 32, height: 2)
                Text(back.isEmpty ? NSLocalizedString("paste_blank_placeholder", comment: "") : back)
                    .font(.system(size: 14))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
            }

            Spacer(minLength: 0)
        }
        .frame(width: 160, alignment: .leading)
        .frame(maxHeight: .infinity)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(LoopkyColor.surfaceCard))
        .shadow(color: LoopkyColor.shadowElevationMedium, radius: 8, y: 4)
    }
}

/// Spec §5.2 "tap to change": the detected-separator chip is an override control, not a label.
///
/// A sheet rather than a `Menu` so it matches Android's `SeparatorOverrideSheet` — nine options
/// is more than a menu wants to be, and the sheet has room to mark the current one.
private struct SeparatorPickerSheet: View {
    let current: Separator?
    let onPick: (Separator) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(Array(KotlinInterop.selectableSeparators.enumerated()), id: \.offset) { _, separator in
                    Button { onPick(separator) } label: {
                        HStack {
                            Text(KotlinInterop.separatorLabel(separator))
                                .font(.system(size: 15, weight: isSelected(separator) ? .bold : .regular))
                                .foregroundStyle(
                                    isSelected(separator)
                                        ? LoopkyColor.accentPrimary
                                        : LoopkyColor.foregroundPrimary
                                )
                            Spacer()
                            if isSelected(separator) {
                                Image(systemName: "checkmark")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundStyle(LoopkyColor.accentPrimary)
                            }
                        }
                    }
                    .accessibilityIdentifier("separator_option")
                }
            }
            .navigationTitle(Text("paste_separator_sheet_title"))
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
    }

    /// Auto is the selected row when nothing has been overridden — the same rule as Android.
    private func isSelected(_ option: Separator) -> Bool {
        KotlinInterop.separatorKey(option) == KotlinInterop.separatorKey(current)
    }
}

private struct ExampleCardView: View {
    let title: String
    let separator: String
    let lines: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(title)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(LoopkyColor.foregroundPrimary)
                Spacer()
                Text(separator)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(LoopkyColor.accentPrimary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 2)
                    .background(Capsule().fill(LoopkyColor.accentPrimarySoft))
            }
            ForEach(lines, id: \.self) { line in
                Text(line)
                    .font(.system(size: 13))
                    .foregroundStyle(LoopkyColor.foregroundSecondary)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(LoopkyColor.surfaceCard))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(LoopkyColor.borderSubtle, lineWidth: 1))
    }
}
