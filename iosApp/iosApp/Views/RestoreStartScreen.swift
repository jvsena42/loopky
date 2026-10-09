import SwiftUI

/// Which kind of backup are you restoring from? Stateless — no ViewModel, like Android's.
struct RestoreStartScreen: View {
    var onBack: () -> Void
    var onRestoreWithPhrase: () -> Void
    var onRestoreWithFile: () -> Void
    var onRestoreWithPasswordManager: () -> Void

    var body: some View {
        SignupScaffold(
            title: "restore_start_title",
            subtitle: NSLocalizedString("restore_start_subtitle", comment: ""),
            onBack: onBack
        ) {
            VStack(spacing: 12) {
                MethodCard(
                    title: "restore_method_phrase",
                    detail: "restore_method_phrase_detail",
                    action: onRestoreWithPhrase
                )
                .accessibilityIdentifier("restore_method_phrase")

                MethodCard(
                    title: "restore_method_file",
                    detail: "restore_method_file_detail",
                    action: onRestoreWithFile
                )
                .accessibilityIdentifier("restore_method_file")

                MethodCard(
                    title: "restore_method_manager",
                    detail: "restore_method_manager_detail",
                    action: onRestoreWithPasswordManager
                )
                .accessibilityIdentifier("restore_method_manager")
            }
        }
    }
}
