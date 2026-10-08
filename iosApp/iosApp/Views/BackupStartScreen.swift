import SwiftUI
import Shared

/// The backup menu, and the one screen in signup that is deliberately skippable.
struct BackupStartScreen: View {
    var onBack: () -> Void
    var onDone: () -> Void
    var onPhrase: () -> Void
    var onFile: () -> Void
    var onRing: () -> Void

    @State private var viewModel: BackupStartViewModel?
    @State private var uiState: BackupStartUiState?
    @State private var stateSink: FlowEffectSink?

    var body: some View {
        SignupScaffold(
            title: "backup_start_title",
            // A restored key is already backed up, so the default copy — "your key lives only on
            // this device" — would describe a risk this user does not have.
            subtitle: NSLocalizedString(
                uiState?.isBackedUp ?? false
                    ? "backup_start_subtitle_backed_up"
                    : "backup_start_subtitle",
                comment: ""
            ),
            errorTitle: nil,
            errorMessage: nil,
            onBack: onBack
        ) {
            VStack(alignment: .leading, spacing: 12) {
                // A key restored from a recovery file has no words, so offering the phrase screen
                // would open one with nothing on it.
                if uiState?.hasPhrase ?? false {
                    MethodCard(
                        title: "backup_method_phrase",
                        detail: "backup_method_phrase_detail",
                        isDone: isDone(.recoveryphrase),
                        action: onPhrase
                    )
                    .accessibilityIdentifier("backup_method_phrase")
                }

                MethodCard(
                    title: "backup_method_file",
                    detail: "backup_method_file_detail",
                    isDone: isDone(.encryptedfile),
                    action: onFile
                )
                .accessibilityIdentifier("backup_method_file")

                // Opens the phrase screen, where the save is: the words are shown before anything
                // offers to store them. Its own row so a save made there has a tick to come back to.
                if uiState?.showPasswordManager ?? false {
                    MethodCard(
                        title: "backup_method_password_manager",
                        detail: "backup_method_password_manager_detail",
                        isDone: isDone(.passwordmanager),
                        action: onPhrase
                    )
                    .accessibilityIdentifier("backup_method_password_manager")
                }

                // Still offered when Ring is absent — the screen behind it installs — but it says
                // so, rather than looking identical and explaining itself one tap later.
                MethodCard(
                    title: "backup_method_ring",
                    detail: uiState?.ringInstalled ?? false
                        ? "backup_method_ring_detail"
                        : "backup_method_ring_missing",
                    isDone: isDone(.pubkyring),
                    action: onRing
                )
                .accessibilityIdentifier("backup_method_ring")

                Button("backup_later", action: onDone)
                    .font(.system(size: 14))
                    .foregroundStyle(LoopkyColor.foregroundSecondary)
                    .padding(.top, 8)
                    .accessibilityIdentifier("backup_later")
            }
        }
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private func isDone(_ method: BackupMethod) -> Bool {
        uiState?.done.contains { ($0 as? BackupMethod) == method } ?? false
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.backupStartViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? BackupStartUiState }
    }

    private func detach() {
        viewModel?.release()
        viewModel = nil
        stateSink = nil
    }
}
