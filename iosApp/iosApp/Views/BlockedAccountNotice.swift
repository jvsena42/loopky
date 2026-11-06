import SwiftUI

/// What a blocked account's profile or deck shows instead of itself, when a link reaches one. No
/// name, no content and no Unblock: unblocking lives in Settings → Blocked accounts (#577).
struct BlockedAccountNotice: View {
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "nosign")
                .font(.system(size: 28))
                .foregroundStyle(LoopkyColor.foregroundMuted)
            Text("blocked_account_title")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
                .multilineTextAlignment(.center)
            Text("blocked_account_body")
                .font(.system(size: 14))
                .foregroundStyle(LoopkyColor.foregroundSecondary)
                .multilineTextAlignment(.center)
        }
        .padding(20)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 20).fill(LoopkyColor.surfaceCard))
        .accessibilityElement(children: .combine)
    }
}

/// What a profile or deck shows when someone the reader follows reported its account (#577).
/// Unlike a block, the reader can look anyway.
struct ReportedAccountNotice: View {
    var onShowAnyway: () -> Void = {}

    var body: some View {
        VStack(spacing: 12) {
            VStack(spacing: 12) {
                Image(systemName: "flag")
                    .font(.system(size: 28))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
                Text("report_hidden_title")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(LoopkyColor.foregroundPrimary)
                    .multilineTextAlignment(.center)
                Text("report_hidden_body")
                    .font(.system(size: 14))
                    .foregroundStyle(LoopkyColor.foregroundSecondary)
                    .multilineTextAlignment(.center)
            }
            .accessibilityElement(children: .combine)
            Button("report_show_anyway", action: onShowAnyway)
                .foregroundStyle(LoopkyColor.accentPrimary)
                .accessibilityIdentifier("report_show_anyway")
        }
        .padding(20)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 20).fill(LoopkyColor.surfaceCard))
    }
}

/// A deck by a blocked account, or by one a followee reported: none of it, and a way back.
struct HiddenDeckView: View {
    let isBlocked: Bool
    var onBack: () -> Void = {}
    var onShowAnyway: () -> Void = {}

    var body: some View {
        VStack(spacing: 12) {
            HStack {
                Button(action: onBack) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(LoopkyColor.foregroundPrimary)
                        .frame(width: 40, height: 40)
                        .loopkyGlass(in: .circle)
                }
                .accessibilityLabel(Text("deck_detail_back"))
                Spacer()
            }
            Spacer()
            if isBlocked {
                BlockedAccountNotice().accessibilityIdentifier("deck_detail_blocked")
            } else {
                ReportedAccountNotice(onShowAnyway: onShowAnyway)
                    .accessibilityIdentifier("deck_detail_reported")
            }
            Spacer()
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
        .contentPane(PaneWidth.reading)
    }
}
