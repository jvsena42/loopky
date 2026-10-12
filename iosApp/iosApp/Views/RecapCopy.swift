import SwiftUI
import Shared

/// Everything the share image says, already localized — the drawing code holds no words.
struct RecapCardText {
    let title: String
    let headline: String
    let stats: [String]
    let persona: String
    let tagline: String
}

/// The recap's sentences. Every count goes through `localizedStringWithFormat`: these keys are
/// plural entries, and `String(format:)` renders the "other" form at every count.
enum RecapCopy {
    static func title(_ year: Int32) -> String { format("recap_title", Int(year)) }
    static func cardsStudied(_ recap: YearRecap) -> String { format("recap_stat_cards_studied", Int(recap.cardsStudied)) }
    static func cardsMastered(_ recap: YearRecap) -> String { format("recap_stat_cards_mastered", Int(recap.cardsMastered)) }
    static func decksStudied(_ recap: YearRecap) -> String { format("recap_stat_decks_studied", Int(recap.decksStudied)) }

    static func created(_ recap: YearRecap) -> String? {
        guard recap.decksCreated > 0 else { return nil }
        let decks = format("recap_stat_decks_created", Int(recap.decksCreated))
        let cards = format("recap_stat_cards_created", Int(recap.cardsCreated))
        return "\(decks) · \(cards)"
    }

    static func topDeck(_ recap: YearRecap) -> String? {
        guard let deck = recap.topDeck else { return nil }
        return format("recap_top_deck", deck.title)
    }

    static func cardText(_ recap: YearRecap) -> RecapCardText {
        RecapCardText(
            title: format("recap_image_title", Int(recap.year)),
            headline: cardsStudied(recap),
            stats: [recap.cardsMastered > 0 ? cardsMastered(recap) : nil, decksStudied(recap), created(recap)]
                .compactMap { $0 },
            persona: "\(personaEmoji(recap.persona)) \(NSLocalizedString(personaTitleKey(recap.persona), comment: ""))",
            tagline: NSLocalizedString("brand_tagline", comment: "").replacingOccurrences(of: "\n", with: " ")
        )
    }

    /// One caption per network, each written for how that network reads: short, a list, or a chat
    /// line. Instagram discards a caption; the story image carries everything.
    static func caption(for target: RecapShareTarget, recap: YearRecap) -> String {
        let year = Int(recap.year)
        let studied = cardsStudied(recap)
        let mastered = cardsMastered(recap)
        if target == RecapShareTarget.x || target == RecapShareTarget.instagram {
            return format("recap_share_text_short", year, studied)
        }
        if target == RecapShareTarget.pubky || target == RecapShareTarget.threads {
            return format("recap_share_text_long", year, studied, mastered, decksStudied(recap))
        }
        return format("recap_share_text_chat", year, studied, mastered)
    }

    static func languageName(_ code: String) -> String {
        Locale.current.localizedString(forLanguageCode: code)?.localizedCapitalized ?? code
    }

    // Kotlin enum entries are compared with ==, not switched over: they cross as class
    // properties, and a switch over them needs a default the compiler cannot check.
    static func personaEmoji(_ persona: RecapPersona) -> String {
        if persona == RecapPersona.polyglot { return "🌍" }
        if persona == RecapPersona.creator { return "✍️" }
        if persona == RecapPersona.master { return "🏆" }
        return "🧭"
    }

    static func personaTitleKey(_ persona: RecapPersona) -> String {
        if persona == RecapPersona.polyglot { return "recap_persona_polyglot" }
        if persona == RecapPersona.creator { return "recap_persona_creator" }
        if persona == RecapPersona.master { return "recap_persona_master" }
        return "recap_persona_explorer"
    }

    static func targetLabelKey(_ target: RecapShareTarget) -> String {
        if target == RecapShareTarget.pubky { return "recap_share_pubky" }
        if target == RecapShareTarget.x { return "recap_share_x" }
        if target == RecapShareTarget.threads { return "recap_share_threads" }
        if target == RecapShareTarget.instagram { return "recap_share_instagram" }
        if target == RecapShareTarget.whatsapp { return "recap_share_whatsapp" }
        return "recap_share_more"
    }

    /// Each network's own mark, as a template image so the button's colour tints it.
    static func targetIcon(_ target: RecapShareTarget) -> Image {
        if target == RecapShareTarget.pubky { return Image("PubkyMark") }
        if target == RecapShareTarget.x { return Image("ShareX") }
        if target == RecapShareTarget.threads { return Image("ShareThreads") }
        if target == RecapShareTarget.instagram { return Image("ShareInstagram") }
        if target == RecapShareTarget.whatsapp { return Image("ShareWhatsApp") }
        return Image(systemName: "square.and.arrow.up")
    }

    /// The network's own colour for its mark, or nil to take the button's. Fixed rather than
    /// themed: the pill under it is white in both themes. Pubky is nil on purpose — its brand lime
    /// on white is unreadable — and Instagram, a gradient in the wild, takes that gradient's magenta.
    static func targetBrandColor(_ target: RecapShareTarget) -> Color? {
        if target == RecapShareTarget.x || target == RecapShareTarget.threads { return .black }
        if target == RecapShareTarget.instagram { return Color(red: 0xE4 / 255, green: 0x40 / 255, blue: 0x5F / 255) }
        if target == RecapShareTarget.whatsapp { return Color(red: 0x25 / 255, green: 0xD3 / 255, blue: 0x66 / 255) }
        return nil
    }

    // One overload per arity rather than a variadic helper: Swift cannot forward `CVarArg...`,
    // and `localizedStringWithFormat` is the call that resolves a plural entry.
    private static func format(_ key: String, _ first: CVarArg) -> String {
        String.localizedStringWithFormat(NSLocalizedString(key, comment: ""), first)
    }

    private static func format(_ key: String, _ first: CVarArg, _ second: CVarArg) -> String {
        String.localizedStringWithFormat(NSLocalizedString(key, comment: ""), first, second)
    }

    private static func format(_ key: String, _ first: CVarArg, _ second: CVarArg, _ third: CVarArg) -> String {
        String.localizedStringWithFormat(NSLocalizedString(key, comment: ""), first, second, third)
    }

    private static func format(
        _ key: String, _ first: CVarArg, _ second: CVarArg, _ third: CVarArg, _ fourth: CVarArg
    ) -> String {
        String.localizedStringWithFormat(NSLocalizedString(key, comment: ""), first, second, third, fourth)
    }
}
