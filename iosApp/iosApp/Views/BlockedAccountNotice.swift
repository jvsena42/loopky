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
