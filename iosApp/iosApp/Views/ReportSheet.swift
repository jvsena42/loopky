import SwiftUI
import Shared

/// The report sheet for a profile (`deck` nil) or a deck. It says before sending that the report
/// also blocks the account, and hands `onSent` whether that block landed (#577).
///
/// A sheet, not an alert: the error and the Send button's state change while it is up, and an
/// alert snapshots its message.
struct ReportSheet: View {
    let accountPubky: String
    let deck: Deck?
    var onSent: (Bool) -> Void
    var onDismiss: () -> Void

    @State private var viewModel: ReportViewModel?
    @State private var uiState: ReportUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?
    /// The field owns its text while typing: binding `get` to state that round-trips through a
    /// ViewModel drops characters.
    @State private var details = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(uiState?.issues ?? [], id: \.wire) { issue in
                        issueRow(issue)
                    }
                } header: {
                    Text("report_issue_label")
                }
                Section {
                    TextField("report_details_placeholder", text: $details, axis: .vertical)
                        .lineLimit(3...6)
                        .accessibilityIdentifier("report_details")
                        .onChange(of: details) { _, new in
                            let capped = String(new.prefix(maxLength))
                            if capped != new { details = capped }
                            viewModel?.onDetailsChange(details: capped)
                        }
                } header: {
                    Text("report_details_label")
                } footer: {
                    Text(verbatim: "\(details.count)/\(maxLength)")
                }
                Section {
                    Text(isDeck ? "report_blocks_deck" : "report_blocks_profile")
                        .font(.system(size: 14))
                        .accessibilityIdentifier("report_blocks_notice")
                } footer: {
                    Text("report_public_note")
                }
                if let reason = uiState?.errorReason {
                    Text(ErrorCopy.message(for: reason))
                        .font(.system(size: 13))
                        .foregroundStyle(LoopkyColor.danger)
                        .accessibilityIdentifier("report_error")
                }
                Section {
                    Button {
                        viewModel?.onSend()
                    } label: {
                        HStack {
                            Spacer()
                            if uiState?.isSending == true { ProgressView() } else { Text("report_send").bold() }
                            Spacer()
                        }
                    }
                    .disabled(uiState?.canSend != true)
                    .accessibilityIdentifier("report_send")
                }
            }
            .navigationTitle(Text(isDeck ? "report_sheet_title_deck" : "report_sheet_title_profile"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("report_cancel", action: onDismiss)
                        .disabled(uiState?.isSending == true)
                        .accessibilityIdentifier("report_cancel")
                }
            }
        }
        .interactiveDismissDisabled(uiState?.isSending == true)
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private var isDeck: Bool { deck != nil }

    private var maxLength: Int { Int(uiState?.detailsMaxLength ?? 700) }

    private func issueRow(_ issue: ReportIssue) -> some View {
        Button {
            viewModel?.onIssueSelected(issue: issue)
        } label: {
            HStack {
                Text(LocalizedStringKey("report_issue_" + issue.wire.replacingOccurrences(of: "-", with: "_")))
                    .foregroundStyle(LoopkyColor.foregroundPrimary)
                Spacer()
                if uiState?.issue?.wire == issue.wire {
                    Image(systemName: "checkmark").foregroundStyle(LoopkyColor.accentPrimary)
                }
            }
        }
        .disabled(uiState?.isSending == true)
        .accessibilityAddTraits(uiState?.issue?.wire == issue.wire ? .isSelected : [])
        .accessibilityIdentifier("report_issue_\(issue.wire)")
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.reportViewModel(accountPubky: accountPubky, deck: deck)
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? ReportUiState }
        effectSink = FlowEffectSink(vm.effects) { effect in
            if let sent = effect as? ReportEffectSent { onSent(sent.blocked) }
        }
    }

    private func detach() {
        viewModel?.release()
        viewModel = nil
        stateSink = nil
        effectSink = nil
    }
}
