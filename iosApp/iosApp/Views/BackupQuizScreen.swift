import SwiftUI
import Shared

/// Confirm three of the twelve words back. **Passing this is what marks the phrase backed up.**
struct BackupQuizScreen: View {
    var onBack: () -> Void
    var onDone: () -> Void

    @State private var viewModel: BackupQuizViewModel?
    @State private var uiState: BackupQuizUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?

    /// `List<Int>` arrives as boxed numbers; unboxed one at a time rather than by casting the
    /// whole array, which fails wholesale and silently leaves the screen blank.
    private var positions: [Int] {
        (uiState?.positions ?? []).compactMap { ($0 as? NSNumber)?.intValue }
    }

    private var options: [[String]] {
        (uiState?.options ?? []).map { row in (row as? [String]) ?? [] }
    }

    var body: some View {
        SignupScaffold(
            title: "backup_quiz_title",
            subtitle: NSLocalizedString("backup_quiz_subtitle", comment: ""),
            errorTitle: uiState?.failed ?? false
                ? NSLocalizedString("restore_error_unreadable_title", comment: "")
                : nil,
            errorMessage: nil,
            onBack: onBack
        ) {
            VStack(alignment: .leading, spacing: 24) {
                if uiState?.isLoading ?? true {
                    ProgressView().controlSize(.regular).tint(LoopkyColor.accentPrimary)
                } else {
                    ForEach(Array(positions.enumerated()), id: \.offset) { index, position in
                        question(index: index, position: position)
                    }

                    // Wrong answers clear rather than lock — this checks the words were written
                    // down, not that the user gets one attempt.
                    if uiState?.wrong ?? false {
                        Text("backup_quiz_wrong")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(LoopkyColor.danger)
                            .accessibilityIdentifier("backup_quiz_wrong")
                    }

                    SignupPrimaryButton(
                        title: "backup_quiz_submit",
                        isEnabled: uiState?.canSubmit ?? false
                    ) {
                        viewModel?.onSubmit()
                    }
                    .accessibilityIdentifier("backup_quiz_submit")
                }
            }
        }
        .modifier(SecureScreenModifier())
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private func question(index: Int, position: Int) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(verbatim: String(
                format: NSLocalizedString("backup_quiz_position", comment: ""), position
            ))
            .font(.system(size: 11, weight: .bold))
            .kerning(0.6)
            .foregroundStyle(LoopkyColor.foregroundMuted)
            let choices = index < options.count ? options[index] : []
            HStack(spacing: 8) {
                ForEach(choices, id: \.self) { word in
                    optionChip(word: word, index: index)
                }
            }
        }
    }

    private func optionChip(word: String, index: Int) -> some View {
        let isSelected = selected(index) == word
        return Button {
            viewModel?.onAnswer(questionIndex: Int32(index), word: word)
        } label: {
            Text(verbatim: word)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(isSelected ? LoopkyColor.foregroundOnAccent : LoopkyColor.foregroundPrimary)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .frame(maxWidth: .infinity)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .fill(isSelected ? LoopkyColor.accentPrimary : LoopkyColor.surfaceCard)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(LoopkyColor.borderSubtle, lineWidth: isSelected ? 0 : 1)
                )
        }
        .buttonStyle(.plain)
        // The fill says "chosen" to the eye; this says it to everything else. Without it VoiceOver
        // read four identical buttons on the screen that gates account backup. No check mark here —
        // the filled chip is unambiguous on its own, and Android needs its glyph only because its
        // selected state had to stop relying on a warm outline that read as an error.
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
        .accessibilityIdentifier("backup_quiz_\(index)_\(word)")
    }

    /// `Map<Int, String>` crosses as a dictionary keyed by boxed integers, so the key is matched
    /// by value rather than rebuilt.
    private func selected(_ index: Int) -> String? {
        uiState?.answers.first { ($0.key as? NSNumber)?.intValue == index }?.value as? String
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.backupQuizViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? BackupQuizUiState }
        effectSink = FlowEffectSink(vm.effects) { effect in
            if effect is BackupEffectDone { onDone() }
        }
    }

    private func detach() {
        viewModel?.release()
        viewModel = nil
        stateSink = nil
        effectSink = nil
    }
}
