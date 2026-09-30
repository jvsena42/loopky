import SwiftUI

/// The four primary destinations. Drives the native `TabView` in `MainView`.
enum LoopkyTab: String, CaseIterable {
    case study
    case decks
    case discover
    case profile

    /// SF Symbol for the native tab item.
    var iconName: String {
        switch self {
        case .study: return "flame.fill"
        case .decks: return "square.stack.3d.up.fill"
        case .discover: return "safari.fill"
        case .profile: return "person.fill"
        }
    }

    /// Label shown under the native tab item. A catalog key, not a literal: these four were the
    /// last English strings on screen once pt-BR landed.
    var title: LocalizedStringKey {
        switch self {
        // Renders Home (the daily queue), not a study screen — matches Android.
        case .study: return "nav_tab_study"
        case .decks: return "nav_tab_decks"
        case .discover: return "nav_tab_discover"
        case .profile: return "nav_tab_profile"
        }
    }
}
