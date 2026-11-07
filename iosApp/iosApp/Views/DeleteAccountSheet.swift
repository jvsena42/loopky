import SwiftUI

/// What the delete-account sheet shows: the warning with its countdown, or the sweep.
enum DeleteAccountPhase: Equatable {
    case confirming(
        secondsRemaining: Int,
        isConfirmable: Bool,
        footprint: DeleteAccountFootprint?,
        deletePubkyApp: Bool
    )
    case deleting(done: Int, total: Int)
}

/// The shared `PubkyAppFootprint`, as the sheet needs it. Nil while it is still being read.
enum DeleteAccountFootprint: Equatable {
    case empty, loopkyOnly, direct, unknown
}

/// The delete-account warning, and then the sweep.
///
/// A sheet rather than the `.alert` it replaced: an alert snapshots its message when presented,
/// so neither the countdown nor the pubky.app line, which arrives after the sheet opens, could
/// appear in one. Once the sweep starts the sheet cannot be dismissed: closing it would hide a
/// deletion that is still running.
struct DeleteAccountSheet: View {
    let phase: DeleteAccountPhase
    let holdsOwnKey: Bool
    let losesTheOnlyKey: Bool
    var onDeletePubkyAppChange: (Bool) -> Void
    var onConfirm: () -> Void
    var onCancel: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                RoundedRectangle(cornerRadius: 2)
                    .fill(LoopkyColor.borderSubtle)
                    .frame(width: 36, height: 4)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 12)

                switch phase {
                case let .confirming(seconds, isConfirmable, footprint, deletePubkyApp):
                    warning(footprint: footprint, deletePubkyApp: deletePubkyApp)
                    buttons(seconds: seconds, isConfirmable: isConfirmable)
                case let .deleting(done, total):
                    progress(done: done, total: total)
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 32)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.hidden)
        .interactiveDismissDisabled(isDeleting)
    }

    private var isDeleting: Bool {
        if case .deleting = phase { return true }
        return false
    }

    @ViewBuilder
    private func warning(footprint: DeleteAccountFootprint?, deletePubkyApp: Bool) -> some View {
        Text("settings_delete_account_dialog_title")
            .font(.system(size: 20, weight: .heavy))
            .foregroundColor(LoopkyColor.foregroundPrimary)
        Text("settings_delete_account_dialog_body")
            .font(.system(size: 14))
            .foregroundColor(LoopkyColor.foregroundSecondary)
            .fixedSize(horizontal: false, vertical: true)
        Text("settings_delete_account_dialog_irreversible")
            .font(.system(size: 14, weight: .bold))
            .foregroundColor(LoopkyColor.danger)
        pubkyAppSection(footprint: footprint, deletePubkyApp: deletePubkyApp)
        // The line that keeps the sheet honest: Loopky cannot delete the Pubky identity.
        // Wrapped: a ternary of two literals is a `String`, which `Text` renders verbatim.
        Text(LocalizedStringKey(
            holdsOwnKey ? "settings_delete_account_dialog_identity_device" : "settings_delete_account_dialog_identity"
        ))
            .font(.system(size: 13))
            .foregroundColor(LoopkyColor.foregroundMuted)
            .fixedSize(horizontal: false, vertical: true)
        if losesTheOnlyKey {
            Text("settings_delete_account_dialog_only_key")
                .font(.system(size: 13, weight: .bold))
                .foregroundColor(LoopkyColor.danger)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("settings_delete_account_only_key")
        }
    }

    @ViewBuilder
    private func pubkyAppSection(footprint: DeleteAccountFootprint?, deletePubkyApp: Bool) -> some View {
        switch footprint {
        case nil:
            HStack(spacing: 10) {
                ProgressView()
                Text("settings_delete_account_checking")
                    .font(.system(size: 13))
                    .foregroundColor(LoopkyColor.foregroundMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("settings_delete_account_checking")
        case .loopkyOnly:
            Toggle(isOn: Binding(get: { deletePubkyApp }, set: onDeletePubkyAppChange)) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("settings_delete_account_pubky_app_option")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(LoopkyColor.foregroundPrimary)
                    Text("settings_delete_account_pubky_app_option_detail")
                        .font(.system(size: 13))
                        .foregroundColor(LoopkyColor.foregroundMuted)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .tint(LoopkyColor.danger)
            .accessibilityIdentifier("settings_delete_account_pubky_app")
        case .direct, .unknown:
            Text(LocalizedStringKey(footprint == .direct
                 ? "settings_delete_account_pubky_app_kept"
                 : "settings_delete_account_pubky_app_unknown"))
                .font(.system(size: 13))
                .foregroundColor(LoopkyColor.foregroundSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("settings_delete_account_pubky_app_kept")
        case .empty:
            EmptyView()
        }
    }

    private func buttons(seconds: Int, isConfirmable: Bool) -> some View {
        HStack(spacing: 12) {
            Button("settings_cancel", action: onCancel)
                .buttonStyle(.loopkyOutline)
                .accessibilityIdentifier("settings_delete_account_cancel")
            Button(action: onConfirm) {
                // localizedStringWithFormat: the countdown key carries `%1$lld`.
                Text(verbatim: seconds > 0
                     ? String.localizedStringWithFormat(
                        NSLocalizedString("settings_delete_account_confirm_countdown", comment: ""),
                        seconds)
                     : NSLocalizedString("settings_delete_account_confirm", comment: ""))
            }
            .buttonStyle(LoopkyFilledButtonStyle(
                fill: isConfirmable ? LoopkyColor.danger : Color.gray,
                fontSize: 16
            ))
            .disabled(!isConfirmable)
            .accessibilityIdentifier("settings_delete_account_confirm")
        }
        .padding(.top, 4)
    }

    private func progress(done: Int, total: Int) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("settings_delete_account_progress_title")
                .font(.system(size: 20, weight: .heavy))
                .foregroundColor(LoopkyColor.foregroundPrimary)
            HStack(spacing: 12) {
                ProgressView()
                Text(verbatim: total > 0
                     ? String.localizedStringWithFormat(
                        NSLocalizedString("settings_delete_account_progress_count", comment: ""),
                        done, total)
                     : NSLocalizedString("settings_delete_account_progress_starting", comment: ""))
                    .font(.system(size: 14))
                    .foregroundColor(LoopkyColor.foregroundSecondary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("settings_delete_account_progress")
        }
    }
}

#Preview {
    DeleteAccountSheet(
        phase: .confirming(secondsRemaining: 0, isConfirmable: true, footprint: .loopkyOnly, deletePubkyApp: false),
        holdsOwnKey: true,
        losesTheOnlyKey: true,
        onDeletePubkyAppChange: { _ in },
        onConfirm: {},
        onCancel: {}
    )
}
