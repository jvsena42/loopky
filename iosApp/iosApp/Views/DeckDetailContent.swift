import SwiftUI

enum DeckDetailViewState {
    case loading
    case content(DeckDetailContent)
    case error(String)
    case blocked
    case hiddenByReport
}

extension DeckDetailViewState {
    var isBlocked: Bool { if case .blocked = self { return true } else { return false } }
}

struct DeckDetailContent {
    let title: String
    let description: String?
    let coverEmoji: String
    let coverImageUrl: String?
    let coverImageBase64: String?
    let author: IdentityData
    let isOwned: Bool
    let tags: [String]
    let totalCards: Int
    /// Pre-formatted by the shared ViewModel — "—" when the review state could not be read.
    let dueLabel: String
    /// Cards never studied. Apart from `dueLabel` because nothing about an unseen card is late.
    let newCards: Int
    let canStudy: Bool
    let masteredPercent: String
    let cards: [CardPreviewData]
    var deckId: String = ""
    /// Whether anyone is signed in. Deck detail reads fine without an account — the manifest and
    /// cards are public — so this gates only Follow and the copy behind Edit, which write.
    var isSignedIn: Bool = true
    /// Claimed by a publish that never finished, so some cards are missing. Surfaced rather than
    /// hidden: the count comes from the manifest, so the deck otherwise looks complete.
    var isIncomplete: Bool = false
    var isFollowing: Bool = false
    var isFollowPending: Bool = false
    var isCloning: Bool = false
    /// The pencil is offered. Wider than `isOwned`: a followed deck carries it too, and there it
    /// offers a copy rather than the editor (#254).
    var canEdit: Bool = false
    /// Anyone else's deck; a guest is asked to sign in first.
    var canReport: Bool = false
    /// The author this deck was cloned from, when it carries clone provenance.
    var clonedFromLabel: String?
    /// Distinct taggers per the indexer — approximate by nature, so display only.
    var followerCount: Int = 0
    var clonedCount: Int = 0
    /// This deck can be *tried* without being kept: flip its cards, grading nothing.
    var canPreview: Bool = false
    /// The deck's study opt-ins, as the reader will actually get them — the shared ViewModel has
    /// already folded Listen and Speak through `Deck.speechReady`.
    var listenEnabled: Bool = false
    var speakEnabled: Bool = false
    var typeEnabled: Bool = false
    var reverseEnabled: Bool = false
    /// The four above are the reader's own choice for a deck they follow, not the author's.
    var isStudyCustomized: Bool = false

    var hasStudyFeatures: Bool { listenEnabled || speakEnabled || typeEnabled || reverseEnabled }
}
