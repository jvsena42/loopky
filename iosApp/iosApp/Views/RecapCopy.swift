import SwiftUI
import Shared

/// Everything the share image says, already localized — the drawing code holds no words.
struct RecapCardText: Hashable {
    let title: String
    let headline: String
    let stats: [String]
    let persona: String
    let tagline: String
}

/// The recap's sentences.
///
/// Two kinds of number, formatted two ways. A **count** goes through
/// `localizedStringWithFormat`, because those keys are plural entries and `String(format:)`
/// renders the "other" form at every count. The **year** goes in as text (`%@`): the same call
/// groups an integer by locale, and the year came out as "2,026".
enum RecapCopy {
    static func title(_ year: Int32) -> String { format("recap_title", String(year)) }
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

    /// The year's stats that have something to say, each with the emoji its line wears in a
    /// caption. Zeroes are left out here, once, for the picture and every caption alike:
    /// "0 cards mastered" is not a line anyone wants posted under their name, and a year of only
    /// creating decks has no cards studied to lead with. Never empty for a recap that is not empty.
    static func stats(_ recap: YearRecap) -> [(emoji: String, line: String)] {
        var lines: [(emoji: String, line: String)] = []
        if recap.cardsStudied > 0 { lines.append(("📚", cardsStudied(recap))) }
        if recap.cardsMastered > 0 { lines.append(("🏆", cardsMastered(recap))) }
        if recap.decksStudied > 0 { lines.append(("🗂️", decksStudied(recap))) }
        if let made = created(recap) { lines.append(("✨", made)) }
        return lines
    }

    static func cardText(_ recap: YearRecap) -> RecapCardText {
        let lines = stats(recap).map(\.line)
        return RecapCardText(
            title: format("recap_image_title", String(recap.year)),
            headline: lines.first ?? "",
            stats: Array(lines.dropFirst()),
            persona: "\(personaEmoji(recap.persona)) \(NSLocalizedString(personaTitleKey(recap.persona), comment: ""))",
            tagline: NSLocalizedString("brand_tagline", comment: "").replacingOccurrences(of: "\n", with: " ")
        )
    }

    /// One caption per network, each written for how that network reads: short, a list, or a chat
    /// line. Instagram discards a caption; the image carries everything.
    static func caption(for target: RecapShareTarget, recap: YearRecap) -> String {
        let year = String(recap.year)
        let lines = stats(recap)
        if target == RecapShareTarget.x || target == RecapShareTarget.instagram {
            return format("recap_share_text_short", year, lines.first?.line ?? "")
        }
        if target == RecapShareTarget.pubky || target == RecapShareTarget.threads {
            return [
                format("recap_share_header", year),
                lines.map { "\($0.emoji) \($0.line)" }.joined(separator: "\n"),
                NSLocalizedString("recap_share_footer", comment: ""),
            ].joined(separator: "\n\n")
        }
        return format("recap_share_text_chat", year, lines.prefix(2).map(\.line).joined(separator: " · "))
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
    /// themed: the pill under it is white in both themes. Pubky is not here: its lime needs the
    /// black plate `RecapTargetMark` draws. Instagram, a gradient in the wild, takes its magenta.
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
}
