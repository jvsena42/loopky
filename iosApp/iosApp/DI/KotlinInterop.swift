import Foundation
import Shared

/// Small bridging helpers for values that cross the Kotlin/Native ObjC boundary
/// in awkward shapes (`value class Tag` → opaque `id`).
enum KotlinInterop {
    /// `Tag` is a Kotlin value class, so it crosses the bridge as an opaque `Any`.
    /// Extracts a displayable label without assuming the boxed representation.
    static func tagLabel(_ tag: Any) -> String {
        if let string = tag as? String { return string }
        let described = String(describing: tag)
        if let range = described.range(of: "Tag(value="), described.hasSuffix(")") {
            return String(described[range.upperBound..<described.index(before: described.endIndex)])
        }
        return described
    }

    /// Localized label for the parser's detected separator.
    ///
    /// These read back to the user in "Detected: em-dash", so they go through the string catalog
    /// rather than being spelled in English here — the `paste_separator_*` keys already exist.
    static func separatorLabel(_ separator: Separator?) -> String {
        NSLocalizedString(separatorKey(separator), comment: "Paste parser separator name")
    }

    /// Identity for a bridged separator, used to mark the picker's current row.
    ///
    /// `Separator` is a sealed *class*, so its entries cross as singletons that `==` would in fact
    /// compare correctly — but matching on the key keeps the comparison total in one place, and it
    /// is the same `case is` ladder the label already needs.
    static func separatorKey(_ separator: Separator?) -> String {
        switch separator {
        case is Separator.EmDash: return "paste_separator_em_dash"
        case is Separator.Tab: return "paste_separator_tab"
        case is Separator.Colon: return "paste_separator_colon"
        case is Separator.Semicolon: return "paste_separator_semicolon"
        case is Separator.Comma: return "paste_separator_comma"
        case is Separator.Pipe: return "paste_separator_pipe"
        case is Separator.MarkdownTable: return "paste_separator_markdown"
        case is Separator.BlankLine: return "paste_separator_blank_lines"
        case is Separator.SingleColumn: return "paste_separator_single_column"
        default: return "paste_separator_auto"
        }
    }

    /// The delimiters the user can force, in the order the picker offers them.
    ///
    /// `Custom` is deliberately absent: it carries a character the picker has no way to ask for.
    /// In Android's order (`SeparatorOverrideSheet`), so a user who has seen one platform's
    /// sheet finds the same delimiter in the same place on the other.
    static let selectableSeparators: [Separator] = [
        Separator.Auto.shared,
        Separator.Tab.shared,
        Separator.Comma.shared,
        Separator.Semicolon.shared,
        Separator.Pipe.shared,
        Separator.Colon.shared,
        Separator.EmDash.shared,
        Separator.MarkdownTable.shared,
        Separator.BlankLine.shared,
    ]
}
