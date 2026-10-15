import SwiftUI
import Shared

/// One presentation of a pubky user, mirroring the Android `IdentityLabels` helpers: the display
/// name wins, the pubky is the fallback, and ownership is a badge beside the name rather than a
/// replacement for it.
struct IdentityData {
    let pubky: String
    let displayName: String?
    let avatarUrl: String?

    init(pubky: String, displayName: String? = nil, avatarUrl: String? = nil) {
        self.pubky = pubky
        self.displayName = displayName
        self.avatarUrl = avatarUrl
    }

    /// **Resolved, not raw.** `identity.avatarUrl` is a `pubky://` file URI that `RemoteImage`
    /// cannot fetch and fails silently on, which is why avatars appeared nowhere. Everything on
    /// screen goes through here, so the translation happens once.
    init(_ identity: PubkyIdentity) {
        self.init(
            pubky: identity.pubky,
            displayName: identity.displayName,
            avatarUrl: IosDependencies.shared.avatarDisplayUrl(identity: identity)
        )
    }

    /// What to call this person on screen.
    var label: String {
        if let name = displayName, !name.trimmingCharacters(in: .whitespaces).isEmpty {
            return name
        }
        return shortPubky
    }

    /// Letter behind an avatar that has no picture — the name's, falling back to the pubky's.
    var initial: String {
        let source = displayName?.trimmingCharacters(in: .whitespaces).isEmpty == false
            ? displayName!.trimmingCharacters(in: .whitespaces)
            : pubky
        return source.first.map { String($0).uppercased() } ?? "?"
    }

    var shortPubky: String { String(pubky.prefix(Self.affixLength)) }

    var truncatedPubky: String {
        "\(pubky.prefix(Self.affixLength))…\(pubky.suffix(Self.affixLength))"
    }

    private static let affixLength = 6
}

/// Marks something as the signed-in user's own, without hiding who they are.
struct YouBadge: View {
    var body: some View {
        Text("identity_you_badge")
            .font(.system(size: 10, weight: .bold))
            .foregroundColor(LoopkyColor.accentSecondary)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Capsule().fill(LoopkyColor.accentSecondarySoft))
    }
}
