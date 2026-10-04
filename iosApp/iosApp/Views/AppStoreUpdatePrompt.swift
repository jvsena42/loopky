import Shared
import SwiftUI

/// iOS has no in-app update API, so this is the equivalent: ask the App Store which version is
/// live, and offer its page when that is newer than the one running. At most once a day — see
/// `UpdatePromptGate`.
///
/// Says nothing while the app has no App Store listing: the lookup answers with zero results.
struct AppStoreUpdateModifier: ViewModifier {
    @Environment(\.openURL) private var openURL
    @State private var storeURL: URL?

    func body(content: Content) -> some View {
        content
            .task { await check() }
            .sheet(
                isPresented: Binding(get: { storeURL != nil }, set: { if !$0 { storeURL = nil } }),
                content: {
                    AppStoreUpdateSheet(
                        onUpdate: {
                            if let storeURL { openURL(storeURL) }
                            storeURL = nil
                        },
                        onDismiss: { storeURL = nil }
                    )
                }
            )
    }

    @MainActor
    private func check() async {
        guard let installed = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
              let listing = await AppStoreListing.fetch(),
              listing.version.compare(installed, options: .numeric) == .orderedDescending,
              let acquired = try? await IosDependencies.shared.updatePromptGate().tryAcquire(),
              acquired.boolValue
        else { return }
        storeURL = listing.trackViewUrl
    }
}

extension View {
    func appStoreUpdatePrompt() -> some View {
        modifier(AppStoreUpdateModifier())
    }
}

/// A sheet rather than an `.alert`, whose buttons take the system's colours and not Loopky's.
private struct AppStoreUpdateSheet: View {
    var onUpdate: () -> Void
    var onDismiss: () -> Void

    /// Measured, because iOS has no "fit the content" detent. The initial value is the first frame's.
    @State private var sheetHeight: CGFloat = 300

    var body: some View {
        VStack(spacing: 12) {
            Text("update_available_title")
                .font(.system(size: 20, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
                .multilineTextAlignment(.center)
            Text("update_available_message")
                .font(.system(size: 15))
                .foregroundStyle(LoopkyColor.foregroundSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Button("update_available_action", action: onUpdate)
                .buttonStyle(LoopkyFilledButtonStyle(fill: LoopkyColor.accentPrimary, fontSize: 16))
                .padding(.top, 8)
                .accessibilityIdentifier("update_available_action")
            Button("profile_avatar_not_now", action: onDismiss)
                .font(.system(size: 15))
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .padding(.top, 4)
                .accessibilityIdentifier("update_available_dismiss")
        }
        .frame(maxWidth: 420)
        .padding(.horizontal, 24)
        .padding(.top, 28)
        .padding(.bottom, 24)
        .background(
            GeometryReader { proxy in
                Color.clear.onAppear { sheetHeight = proxy.size.height }
            }
        )
        .presentationDetents([.height(sheetHeight)])
        .presentationDragIndicator(.visible)
        // Without this the container's identifier replaces both buttons' own.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("update_available_sheet")
    }
}

private struct AppStoreLookup: Decodable {
    let results: [AppStoreListing]
}

private struct AppStoreListing: Decodable {
    let version: String
    let trackViewUrl: URL

    /// `nil` on any failure — offline, unlisted, a response that does not parse. None of them is
    /// worth telling the reader about.
    static func fetch() async -> AppStoreListing? {
        guard let bundleId = Bundle.main.bundleIdentifier,
              var components = URLComponents(string: "https://itunes.apple.com/lookup")
        else { return nil }
        // Without a country the lookup answers for the US storefront only.
        components.queryItems = [
            URLQueryItem(name: "bundleId", value: bundleId),
            URLQueryItem(name: "country", value: Locale.current.region?.identifier ?? "US"),
        ]
        guard let url = components.url,
              let (data, _) = try? await URLSession.shared.data(from: url)
        else { return nil }
        return (try? JSONDecoder().decode(AppStoreLookup.self, from: data))?.results.first
    }
}
