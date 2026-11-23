import Shared
import SwiftUI

/// Covers the app once the indexer says this build is too old (`ClientCompatibility`, a 410 or a
/// 426 from Nexus). There is no way past it but the App Store: every global read would fail behind
/// it, and a screen that half works reads as a broken app rather than an old one.
struct ForceUpdateModifier: ViewModifier {
    @StateObject private var outdated = FlowObserver<KotlinBoolean>(
        IosDependencies.shared.clientCompatibility().outdated
    )

    func body(content: Content) -> some View {
        content.overlay {
            if outdated.value?.boolValue == true {
                ForceUpdateScreen()
            }
        }
    }
}

extension View {
    func forceUpdateGate() -> some View {
        modifier(ForceUpdateModifier())
    }
}

struct ForceUpdateScreen: View {
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            Text("force_update_title")
                .font(.system(size: 22, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
                .multilineTextAlignment(.center)
            Text("force_update_message")
                .font(.system(size: 15))
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Button("force_update_action") {
                Task { @MainActor in
                    // The listing's own page when the App Store knows Loopky, else the website,
                    // which links to it.
                    let listing = await AppStoreListing.fetch()
                    if let url = listing?.trackViewUrl ?? URL(string: "https://loopky.app") {
                        openURL(url)
                    }
                }
            }
            .buttonStyle(LoopkyFilledButtonStyle(fill: LoopkyColor.accentPrimary, fontSize: 16))
            .padding(.top, 8)
            .accessibilityIdentifier("force_update_action")
            Spacer()
        }
        .frame(maxWidth: 420)
        .padding(.horizontal, 32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(LoopkyColor.surfacePrimary.ignoresSafeArea())
        // Covers the whole window and takes every touch, so nothing underneath can be reached.
        .contentShape(Rectangle())
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("force_update_screen")
    }
}
