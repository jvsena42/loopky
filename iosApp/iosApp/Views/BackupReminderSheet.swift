import Shared
import SwiftUI

/// The once-a-day backup reminder — see `BackupReminderViewModel`. Hosted by the signed-in shell.
struct BackupReminderModifier: ViewModifier {
    var onBackUpNow: () -> Void

    @Environment(\.scenePhase) private var scenePhase
    @State private var viewModel: BackupReminderViewModel?
    @State private var stateSink: FlowEffectSink?
    @State private var isVisible = false
    /// The backup flow is itself a sheet raised from an ancestor, and iOS refuses to present one
    /// while this is still on screen — so the hand-off waits for `onDismiss`.
    @State private var backUpAfterDismiss = false

    func body(content: Content) -> some View {
        content
            .sheet(
                isPresented: Binding(
                    get: { isVisible },
                    set: { if !$0 { viewModel?.onDismiss() } }
                ),
                onDismiss: {
                    guard backUpAfterDismiss else { return }
                    backUpAfterDismiss = false
                    onBackUpNow()
                },
                content: {
                    BackupReminderSheet(onBackUpNow: {
                        backUpAfterDismiss = true
                        viewModel?.onDismiss()
                    })
                }
            )
            .onAppear { attach() }
            .onDisappear { detach() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { viewModel?.onAppForeground() }
            }
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.backupReminderViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { value in
            isVisible = (value as? BackupReminderUiState)?.isVisible ?? false
        }
    }

    private func detach() {
        viewModel?.release()
        viewModel = nil
        stateSink = nil
    }
}

extension View {
    func backupReminder(onBackUpNow: @escaping () -> Void) -> some View {
        modifier(BackupReminderModifier(onBackUpNow: onBackUpNow))
    }
}

private struct BackupReminderSheet: View {
    var onBackUpNow: () -> Void

    /// Measured, because iOS has no "fit the content" detent. The initial value is the first frame's.
    @State private var sheetHeight: CGFloat = 300

    var body: some View {
        VStack(spacing: 12) {
            Text("backup_reminder_title")
                .font(.system(size: 20, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
                .multilineTextAlignment(.center)
            Text("backup_reminder_body")
                .font(.system(size: 15))
                .foregroundStyle(LoopkyColor.foregroundSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Button("backup_nag_action", action: onBackUpNow)
                .buttonStyle(LoopkyFilledButtonStyle(fill: LoopkyColor.accentPrimary, fontSize: 16))
                .padding(.top, 8)
                .accessibilityIdentifier("backup_reminder_action")
        }
        .frame(maxWidth: 420)
        .padding(.horizontal, 24)
        .padding(.top, 28)
        .padding(.bottom, 32)
        .background(
            GeometryReader { proxy in
                Color.clear.onAppear { sheetHeight = proxy.size.height }
            }
        )
        .presentationDetents([.height(sheetHeight)])
        .presentationDragIndicator(.visible)
        // Without this the container's identifier replaces both buttons' own.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("backup_reminder_sheet")
    }
}
