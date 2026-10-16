import SwiftUI

/// A person's picture, with their initial drawn underneath as the fallback.
///
/// Mirrors Android's `PubkyAvatar`. iOS had no such component: the two profile heroes each drew
/// their own, and every *list* — Discover's people strip, Search results, the follow lists — drew
/// an initial and dropped `avatarUrl` entirely, so nobody's photo appeared anywhere but their own
/// profile.
///
/// The initial sits behind the image rather than beside it, so a slow or missing avatar degrades
/// to something legible instead of a hole.
struct PubkyAvatarView: View {
    let initial: String
    var avatarUrl: String?
    var size: CGFloat = 40
    var background: Color = LoopkyColor.accentSecondarySoft
    var foreground: Color = LoopkyColor.accentSecondary

    var body: some View {
        ZStack {
            Circle().fill(background)
            Text(initial)
                .font(.system(size: size * 0.4, weight: .heavy))
                .foregroundStyle(foreground)
            if let avatarUrl, let url = URL(string: avatarUrl) {
                RemoteImage(url: url) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    // Nothing: the initial underneath is already the placeholder.
                    Color.clear
                }
                .clipShape(Circle())
            }
        }
        .frame(width: size, height: size)
    }
}
