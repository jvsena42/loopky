import SwiftUI

/// Pick a file and see what it holds before committing. Pure layout.
struct BulkImportView: View {
    var state: BulkImportViewState = BulkImportViewState()
    var onPickFile: () -> Void = {}
    var onPickAnother: () -> Void = {}
    var onChooseFields: () -> Void = {}
    var onConfirm: () -> Void = {}
    var onCancel: () -> Void = {}
    var onCopyCliPrompt: () -> Void = {}

    @State private var didCopyPrompt = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header
                switch state.phase {
                case .idle: formats
                case .reading, .parsing: progress
                case .failed: failure
                case .ready: ready
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 40)
            // A single column of form fields and prose. After the background, so the cream
            // still reaches both edges of an iPad and only the content inside is bounded.
            .contentPane()
        }
        .loopkyScreenBackground()
        .navigationBarHidden(true)
    }

    private var importLabel: String {
        String.localizedStringWithFormat(
            NSLocalizedString("bulk_import_cards", comment: ""),
            state.cardCount
        )
    }

    private var header: some View {
        HStack {
            Button("bulk_cancel", action: onCancel)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(LoopkyColor.accentPrimary)
            Spacer()
            Text("bulk_title")
                .font(.system(size: 18, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Spacer()
            // Absent rather than disabled before a file is parsed: "Import 0 cards" over an
            // empty screen describes nothing the user has done yet.
            if state.phase == .ready {
                Button(importLabel, action: onConfirm)
                    .buttonStyle(LoopkyCompactFilledButtonStyle(
                        fill: state.canImport ? LoopkyColor.accentPrimary : LoopkyColor.borderSubtle,
                        foreground: state.canImport ? .white : LoopkyColor.foregroundMuted
                    ))
                    .disabled(!state.canImport)
                    .accessibilityIdentifier("bulk_import")
            }
        }
    }

    /// The empty state, which is where most of this screen's job gets done.
    ///
    /// Mirrors Android's: a hero, and the two formats that work with what each brings over.
    /// Prose over blank space says nothing about what a "file" means here.
    private var formats: some View {
        VStack(alignment: .leading, spacing: 12) {
            illustration

            Text("bulk_formats_label")
                .font(.system(size: 10, weight: .bold))
                .kerning(0.8)
                .foregroundStyle(LoopkyColor.foregroundMuted)

            formatCard("bulk_format_apkg_title", "bulk_format_apkg_detail", "bulk_format_apkg_ext")
            formatCard("bulk_format_text_title", "bulk_format_text_detail", "bulk_format_text_ext")

            Button("bulk_pick_file", action: onPickFile)
                .buttonStyle(.loopkyFilled)
                .accessibilityIdentifier("bulk_pick_file")

            // Formatted from the same ceiling the picker enforces, so the copy cannot drift.
            Text(String(
                format: NSLocalizedString("bulk_idle_limit", comment: ""),
                maxImportFileBytes / bytesPerMegabyte
            ))
            .font(.system(size: 12))
            .foregroundStyle(LoopkyColor.foregroundMuted)
            .frame(maxWidth: .infinity)

            cliPromptCard
        }
    }

    /// A pitch for `loopky`, the headless client, on the screen whose whole premise is that the
    /// deck already exists somewhere else. The prompt is copied rather than shown, because the
    /// reader's agent lives on another machine and nobody retypes an install line from a phone.
    private var cliPromptCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("bulk_cli_title")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Text("bulk_cli_body")
                .font(.system(size: 13))
                .foregroundStyle(LoopkyColor.foregroundSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Button(didCopyPrompt ? "bulk_cli_copied" : "bulk_cli_copy") {
                onCopyCliPrompt()
                withAnimation { didCopyPrompt = true }
                Task {
                    try? await Task.sleep(for: .seconds(2))
                    withAnimation { didCopyPrompt = false }
                }
            }
            .buttonStyle(.loopkySoft)
            .accessibilityIdentifier("bulk_cli_copy")
            .padding(.top, 2)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(LoopkyColor.surfaceCard))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(LoopkyColor.borderSubtle, lineWidth: 1))
        .padding(.top, 10)
    }

    /// The same idiom as the paste screen's fox and Home's book stack: an emoji on the brand
    /// plate rather than a bitmap, so it needs no asset and scales with Dynamic Type.
    private var illustration: some View {
        VStack(spacing: 12) {
            Text("📦")
                .font(.system(size: 44))
                .frame(width: 96, height: 96)
                .background(Circle().fill(LoopkyColor.accentPrimarySoft))
                .accessibilityHidden(true)
            Text("bulk_idle_title")
                .font(.system(size: 22, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
                .multilineTextAlignment(.center)
            Text("bulk_idle_subtitle")
                .font(.system(size: 14))
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 20)
        .padding(.bottom, 8)
    }

    private func formatCard(
        _ title: LocalizedStringKey,
        _ detail: LocalizedStringKey,
        _ ext: LocalizedStringKey
    ) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title).font(.system(size: 15, weight: .bold))
                Spacer()
                Text(ext)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(LoopkyColor.accentPrimary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 2)
                    .background(Capsule().fill(LoopkyColor.accentPrimarySoft))
            }
            Text(detail)
                .font(.system(size: 13))
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(LoopkyColor.surfaceCard))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(LoopkyColor.borderSubtle, lineWidth: 1))
    }

    private var progress: some View {
        HStack(spacing: 10) {
            ProgressView()
            Text(state.phase == .reading ? "bulk_reading" : "bulk_parsing")
                .font(.system(size: 14))
                .foregroundStyle(LoopkyColor.foregroundMuted)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 40)
    }

    private var failure: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let errorTitle = state.errorTitle {
                Text(errorTitle)
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(LoopkyColor.danger)
            }
            if let errorMessage = state.errorMessage {
                Text(verbatim: errorMessage)
                    .font(.system(size: 14))
                    .foregroundStyle(LoopkyColor.foregroundSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Button("bulk_pick_another", action: onPickAnother).buttonStyle(.loopkySoft)
        }
    }

    private var ready: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(state.fileName)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(LoopkyColor.foregroundPrimary)

            HStack(spacing: 8) {
                chip(String.localizedStringWithFormat(
                    NSLocalizedString("bulk_cards_parsed", comment: ""),
                    state.cardCount
                ))
                if !state.separatorLabel.isEmpty {
                    chip(String(
                        format: NSLocalizedString("bulk_detected_separator", comment: ""),
                        state.separatorLabel
                    ))
                }
            }

            // Every count the reader dropped is reported. Notes that never became cards used to be
            // invisible, so an import of 1,458 notes yielding 1,338 cards looked like a bug.
            notes

            if let fieldsLabel = state.fieldsLabel {
                Button(action: onChooseFields) { Text(verbatim: fieldsLabel) }
                    .buttonStyle(.loopkyOutline)
                    .accessibilityIdentifier("bulk_fields")
            }

            if !state.sample.isEmpty { samples }
            Button("bulk_pick_another", action: onPickAnother)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(LoopkyColor.foregroundMuted)
        }
    }

    @ViewBuilder
    private var notes: some View {
        VStack(alignment: .leading, spacing: 4) {
            note(state.skippedCount, "bulk_skipped")
            note(state.duplicatesCollapsed, "bulk_duplicates")
            note(state.truncatedCount, "bulk_truncated")
            note(state.droppedNoteCount, "bulk_dropped_notes")
            note(state.imagesSkippedCount, "bulk_images_skipped")
        }
    }

    @ViewBuilder
    private func note(_ count: Int, _ key: String) -> some View {
        if count > 0 {
            // localizedStringWithFormat, not String(format:): every key this is called with
            // is a plural entry, and only this one resolves the variation (#267).
            Text(String.localizedStringWithFormat(NSLocalizedString(key, comment: ""), count))
                .font(.system(size: 12))
                .foregroundStyle(LoopkyColor.foregroundMuted)
        }
    }

    private var samples: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("bulk_sample_label")
                .font(.system(size: 10, weight: .bold))
                .kerning(0.8)
                .foregroundStyle(LoopkyColor.foregroundMuted)
            ForEach(state.sample) { card in
                HStack(alignment: .top, spacing: 10) {
                    sampleSide(card.front, hasImage: card.hasFrontImage)
                    sampleSide(card.back, hasImage: card.hasBackImage)
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 12).fill(LoopkyColor.surfaceCard))
            }
        }
    }

    private func sampleSide(_ text: String, hasImage: Bool) -> some View {
        HStack(spacing: 4) {
            if hasImage {
                Image(systemName: "photo").font(.system(size: 11))
                    .foregroundStyle(LoopkyColor.accentSecondary)
            }
            Text(text).font(.system(size: 13)).foregroundStyle(LoopkyColor.foregroundSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func chip(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 12, weight: .semibold))
            .foregroundStyle(LoopkyColor.accentSecondary)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Capsule().fill(LoopkyColor.accentSecondarySoft))
    }
}
