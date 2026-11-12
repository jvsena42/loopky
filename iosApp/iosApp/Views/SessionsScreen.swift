import SwiftUI
import Shared

/// Settings → Sessions: the apps this account signed in to with Loopky, and the approval of a new
/// one from a scanned or pasted `pubkyauth://` link.
///
/// iOS does not claim the `pubkyauth` scheme. A URL scheme cannot be switched on and off with the
/// key the way Android's activity-alias is, and iOS picks arbitrarily between two apps claiming
/// one, so a static claim would take Ring's links from Ring users. Scan and paste are the way in.
struct SessionsScreen: View {
    @State private var viewModel: SessionsViewModel?
    @State private var uiState: SessionsUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?
    @State private var isScanning = false
    @State private var isShowingRevokeError = false
    @State private var confirming: AuthorizedSession?

    var body: some View {
        List {
            if let state = uiState, !state.canApprove {
                Section {
                    Text("sessions_ring_holds_key")
                        .foregroundStyle(LoopkyColor.foregroundMuted)
                        .accessibilityIdentifier("sessions_ring_holds_key")
                }
            } else {
                Section {
                    Button {
                        isScanning = true
                    } label: {
                        Label("sessions_scan", systemImage: "qrcode.viewfinder")
                    }
                    .tint(LoopkyColor.accentPrimary)
                    .accessibilityIdentifier("sessions_scan")
                    // PasteButton reads the clipboard without iOS's "Allow Paste" prompt.
                    PasteButton(payloadType: String.self) { strings in
                        guard let text = strings.first else { return }
                        Task { @MainActor in viewModel?.onSignInLink(text: text) }
                    }
                    .tint(LoopkyColor.accentPrimary)
                    .accessibilityIdentifier("sessions_paste")
                } footer: {
                    Text("sessions_caption")
                }
                Section {
                    sessionRows
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .contentPane()
        .loopkyScreenBackground()
        .navigationTitle(Text("settings_sessions"))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $isScanning) {
            QrScannerSheet(
                onScanned: { text in
                    isScanning = false
                    viewModel?.onSignInLink(text: text)
                },
                onClose: { isScanning = false }
            )
        }
        .sheet(isPresented: approvalPresented) {
            if let approval = uiState?.approval {
                ApprovalSheetView(
                    approval: approval,
                    onApprove: { viewModel?.onApprove(shownLink: approval.shownLink) },
                    onDismiss: { viewModel?.onDismissApproval() }
                )
                .presentationDetents([.medium, .large])
                .interactiveDismissDisabled(approval.phase == .approving)
            }
        }
        .alert(
            revokeTitle,
            isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } })
        ) {
            Button("sessions_revoke", role: .destructive) {
                if let grantId = confirming?.grantId { viewModel?.onRevoke(grantId: grantId) }
                confirming = nil
            }
            .accessibilityIdentifier("sessions_revoke_confirm")
            Button("sessions_revoke_cancel", role: .cancel) { confirming = nil }
        } message: {
            Text("sessions_revoke_dialog_message")
        }
        .alert(Text("sessions_revoke_failed"), isPresented: $isShowingRevokeError) {
            Button("share_sheet_close", role: .cancel) {}
        }
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    @ViewBuilder
    private var sessionRows: some View {
        if let state = uiState, !state.isLoading {
            if let reason = state.errorReason {
                Text(ErrorCopy.message(for: reason))
                    .font(.system(size: 13))
                    .foregroundStyle(LoopkyColor.danger)
                Button("decks_retry") { viewModel?.onRetry() }
            } else if state.sessions.isEmpty {
                Text("sessions_empty")
                    .font(.system(size: 14))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
                    .accessibilityIdentifier("sessions_empty")
            } else {
                ForEach(Array(state.sessions.enumerated()), id: \.element.grantId) { index, session in
                    row(session, isRevoking: state.revoking.contains(session.grantId), index: index)
                }
            }
        } else {
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text("sessions_loading").foregroundStyle(LoopkyColor.foregroundMuted)
            }
        }
    }

    private func row(_ session: AuthorizedSession, isRevoking: Bool, index: Int) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(SessionCopy.appName(session.clientId))
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(LoopkyColor.foregroundPrimary)
                ForEach(session.access, id: \.path) { access in
                    Text(SessionCopy.access(access))
                        .font(.system(size: 13))
                        .foregroundStyle(LoopkyColor.foregroundMuted)
                }
                Text(SessionCopy.signedIn(session.issuedAt))
                    .font(.system(size: 12))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
            }
            Spacer()
            if session.isThisDevice {
                Text("sessions_this_device")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
            } else {
                Button("sessions_revoke") { confirming = session }
                    .buttonStyle(.borderless)
                    .tint(LoopkyColor.accentPrimary)
                    .disabled(isRevoking)
                    .accessibilityIdentifier("session_revoke_\(index)")
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("session_row_\(index)")
    }

    private var revokeTitle: Text {
        Text(String(
            format: NSLocalizedString("sessions_revoke_dialog_title", comment: ""),
            SessionCopy.appName(confirming?.clientId ?? "")
        ))
    }

    private var approvalPresented: Binding<Bool> {
        Binding(
            get: { uiState?.approval != nil },
            set: { if !$0 { viewModel?.onDismissApproval() } }
        )
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.sessionsViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? SessionsUiState }
        effectSink = FlowEffectSink(vm.effects) { effect in
            if effect is SessionsEffectRevokeFailed { isShowingRevokeError = true }
        }
    }

    private func detach() {
        viewModel?.release()
        viewModel = nil
        stateSink = nil
        effectSink = nil
    }
}

private struct ApprovalSheetView: View {
    let approval: ApprovalUiState
    let onApprove: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                switch approval.phase {
                case .refused: refused
                case .approved: approved
                default: review
                }
            }
            .padding(24)
            .frame(maxWidth: 560, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .background(LoopkyColor.surfaceCard)
        .accessibilityIdentifier("approval_sheet")
    }

    @ViewBuilder
    private var review: some View {
        let approving = approval.phase == .approving
        title("approval_title")
        Text(approval.clientId.map {
            String(format: NSLocalizedString("approval_body", comment: ""), $0)
        } ?? NSLocalizedString("approval_body_unknown", comment: ""))
            .font(.system(size: 15))
            .foregroundStyle(LoopkyColor.foregroundPrimary)
            .accessibilityIdentifier("approval_client")
        Text("approval_access_label")
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(LoopkyColor.foregroundMuted)
        if approval.access.isEmpty {
            Text("approval_access_none").foregroundStyle(LoopkyColor.foregroundPrimary)
        }
        ForEach(approval.access, id: \.path) { access in
            Text("• " + SessionCopy.access(access)).foregroundStyle(LoopkyColor.foregroundPrimary)
        }
        Text(approval.reachesBeyondLoopky ? "approval_beyond_loopky" : "approval_only_if_you_started")
            .font(.system(size: 13))
            .foregroundStyle(approval.reachesBeyondLoopky ? LoopkyColor.danger : LoopkyColor.foregroundMuted)
            .accessibilityIdentifier("approval_warning")
        if approval.phase == .failed {
            Text("approval_failed")
                .font(.system(size: 13))
                .foregroundStyle(LoopkyColor.danger)
                .accessibilityIdentifier("approval_failed")
        }
        Button(action: onApprove) {
            HStack {
                if approving { ProgressView().tint(LoopkyColor.foregroundOnAccent) }
                Text(approval.phase == .failed ? "approval_retry" : "approval_approve")
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(LoopkyFilledButtonStyle())
        .disabled(approving)
        .accessibilityIdentifier("approval_approve")
        Button("approval_deny", action: onDismiss)
            .frame(maxWidth: .infinity)
            .tint(LoopkyColor.foregroundPrimary)
            .disabled(approving)
            .accessibilityIdentifier("approval_deny")
    }

    @ViewBuilder
    private var refused: some View {
        title("approval_refused_title")
        Text(refusalKey)
            .foregroundStyle(LoopkyColor.foregroundPrimary)
            .accessibilityIdentifier("approval_refusal")
        Button("approval_close", action: onDismiss)
            .buttonStyle(LoopkyFilledButtonStyle())
            .accessibilityIdentifier("approval_close")
    }

    @ViewBuilder
    private var approved: some View {
        title("approval_done_title")
        Text(approval.clientId.map {
            String(format: NSLocalizedString("approval_done_body", comment: ""), $0)
        } ?? NSLocalizedString("approval_done_body_unknown", comment: ""))
            .foregroundStyle(LoopkyColor.foregroundPrimary)
            .accessibilityIdentifier("approval_done_body")
        Button("approval_done", action: onDismiss)
            .buttonStyle(LoopkyFilledButtonStyle())
            .accessibilityIdentifier("approval_done")
    }

    private var refusalKey: LocalizedStringKey {
        switch approval.refusal {
        case .createsaccount: return "refusal_creates_account"
        case .wholeaccount: return "refusal_whole_account"
        case .oldformat: return "refusal_old_format"
        case .nokeyhere: return "refusal_no_key"
        default: return "refusal_not_a_link"
        }
    }

    private func title(_ key: LocalizedStringKey) -> some View {
        Text(key)
            .font(.system(size: 22, weight: .heavy))
            .foregroundStyle(LoopkyColor.foregroundPrimary)
    }
}

private enum SessionCopy {
    static func appName(_ clientId: String) -> String {
        clientId.isEmpty ? NSLocalizedString("sessions_unknown_app", comment: "") : clientId
    }

    static func access(_ access: RequestedAccess) -> String {
        if access.isLoopkyOnly {
            let key = access.canWrite ? "sessions_access_loopky" : "sessions_access_loopky_read"
            return NSLocalizedString(key, comment: "")
        }
        let key = access.canWrite ? "sessions_access_write" : "sessions_access_read"
        return String(format: NSLocalizedString(key, comment: ""), access.path)
    }

    static func signedIn(_ epochSeconds: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(epochSeconds))
        let formatted = date.formatted(date: .abbreviated, time: .omitted)
        return String(format: NSLocalizedString("sessions_signed_in_on", comment: ""), formatted)
    }
}
