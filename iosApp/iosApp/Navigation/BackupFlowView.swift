import SwiftUI

/// One leg of the backup flow. Its own enum rather than a slice of `IdentityRoute`: backup is not
/// a signed-out flow — it is only ever reached from Settings or the Profile nag, both of which
/// need an account to exist first.
private enum BackupRoute: Hashable {
    case phrase
    case quiz
    case file
    case ring
}

/// The backup flow, reached from Settings or the Profile nag once the account already exists.
///
/// Presented as a sheet rather than pushed. Android pushes it onto the same graph, but the
/// signed-in side here drives a single `navigationDestination(item:)`, and backup is four pushes
/// deep — a self-contained task the user finishes and dismisses is what a sheet is for on iOS.
struct BackupFlowView: View {
    var onClose: () -> Void

    @State private var path: [BackupRoute] = []

    var body: some View {
        NavigationStack(path: $path) {
            BackupStartScreen(
                onBack: onClose,
                onDone: onClose,
                onPhrase: { path.append(.phrase) },
                onFile: { path.append(.file) },
                onRing: { path.append(.ring) }
            )
            .navigationDestination(for: BackupRoute.self) { route in
                destination(route)
            }
        }
    }

    @ViewBuilder
    private func destination(_ route: BackupRoute) -> some View {
        switch route {
        case .phrase:
            BackupPhraseScreen(onBack: pop, onContinue: { path.append(.quiz) })
        case .quiz:
            // Back to the menu, not out: methods accumulate, and the menu now shows this one
            // ticked.
            BackupQuizScreen(onBack: pop, onDone: { path.removeAll() })
        case .file:
            BackupFileScreen(onBack: pop, onDone: { path.removeAll() })
        case .ring:
            BackupRingScreen(onBack: pop, onDone: { path.removeAll() })
        }
    }

    private func pop() {
        if !path.isEmpty { path.removeLast() }
    }
}
