import SwiftUI
import Shared

struct EditorCardData: Identifiable {
    let id: String
    let front: String
    let back: String
    let hasImage: Bool
    let hasAudio: Bool
}

/// Pure layout — state comes from the shared `DeckEditorViewModel` via `DeckEditorScreen`.
struct DeckEditorView: View {
    @State private var pickingCover = false
    var isNew: Bool = true
    var coverEmoji: String = ""
    /// The deck's remote cover URL — its stored one on open, or one chosen this session.
    var coverImageUrl: String?
    /// A homeserver-blob cover's bytes, fetched by the shared ViewModel. A blob carries no URL,
    /// so without this the one screen that can *replace* a cover could not show it (#166).
    var coverImageBase64: String?
    /// Bytes chosen this session and not yet uploaded. The newest cover, so it wins.
    var coverPendingBytes: Data?
    var title: String = ""
    var description: String = ""
    var tags: [String] = []
    /// The cards paged in so far — a prefix of the deck, not the deck. See `totalCards`.
    var cards: [EditorCardData] = []
    /// Cards in the whole deck, from the manifest. What the header counts.
    var totalCards: Int = 0
    var isLoadingCards: Bool = false
    var hasMoreCards: Bool = false
    var isSaving: Bool = false
    var titleError: String?
    var descriptionError: String?
    var error: String?
    /// Offered beside `error` only when signing in again could clear it — see `offersSignIn`.
    var onSignInAgain: (() -> Void)?

    var onTitleChanged: (String) -> Void = { _ in }
    var onDescriptionChanged: (String) -> Void = { _ in }
    var onAddTag: (String) -> Void = { _ in }
    var onRemoveTag: (String) -> Void = { _ in }
    var onAddCard: () -> Void = {}
    var onCardTap: (String) -> Void = { _ in }
    /// `(from, to)` indices into the loaded prefix — the deck's own order, persisted immediately.
    var onMoveCard: (Int, Int) -> Void = { _, _ in }
    var onLoadMoreCards: () -> Void = {}
    var onClose: () -> Void = {}
    var onSave: () -> Void = {}
    /// The four study opt-ins and the language pair, built by `DeckEditorScreen`.
    var studyOptions: DeckStudyOptions?
    var onCoverSelected: (ImageSelection) -> Void = { _ in }

    @State private var showTagSheet = false

    private var hasCover: Bool { coverImageUrl != nil || coverBytes != nil }

    /// The bytes to draw: a cover picked this session, else a loaded blob cover.
    private var coverBytes: Data? {
        coverPendingBytes ?? coverImageBase64.flatMap { Data(base64Encoded: $0) }
    }

    /// A URL cover has no `MediaRef` here; wrap it so the same view can draw it.
    private var coverRef: MediaRef.Image? {
        coverImageUrl.map {
            MediaRef.Image(path: "", mime: "", sha256: "", width: nil, height: nil, uri: nil, url: $0)
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // Header
                HStack {
                    Button(action: onClose) {
                        ZStack {
                            Circle()
                                .fill(LoopkyColor.surfaceCard)
                                .frame(width: 40, height: 40)
                            Image(systemName: "xmark")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundColor(LoopkyColor.foregroundPrimary)
                        }
                    }
                    Spacer()
                    Text(isNew ? "deck_editor_title_new" : "deck_editor_title_edit")
                        .font(.system(size: 18, weight: .heavy))
                        .foregroundColor(LoopkyColor.foregroundPrimary)
                    Spacer()
                    Button(action: onSave) {
                        HStack(spacing: 6) {
                            if isSaving {
                                ProgressView()
                                    .tint(.white)
                                    .scaleEffect(0.7)
                            }
                            Text(isSaving ? "deck_editor_saving" : "deck_editor_save")
                        }
                    }
                    .buttonStyle(.loopkyCompactFilled)
                    .disabled(isSaving)
                }

                // Metadata card
                VStack(alignment: .leading, spacing: 14) {
                    // Cover + Title
                    HStack(spacing: 12) {
                        Button { pickingCover = true } label: {
                            ZStack {
                                RoundedRectangle(cornerRadius: 14)
                                    .fill(LoopkyColor.accentPrimarySoft)
                                if hasCover {
                                    CardMediaImage(
                                        ref: coverRef,
                                        pendingBytes: coverBytes,
                                        authorPubky: "",
                                        deckId: "",
                                        contentMode: .fill
                                    )
                                } else {
                                    // The emoji is the fallback cover, drawn underneath — a deck
                                    // always has one, a picture is optional.
                                    Text(coverEmoji.isEmpty ? "📚" : coverEmoji)
                                        .font(.system(size: 32))
                                }
                            }
                            // Clip *after* the frame, not around the image inside it. A `.fill`
                            // image reports a size larger than the box in one dimension, so the
                            // ZStack grows with it and the frame only re-centres the overflow —
                            // it does not cut it off. Clipping the inner image rounds that
                            // oversized rect instead of the tile, which is how the cover came to
                            // spill over the title beside it (#166).
                            .frame(width: 64, height: 64)
                            .clipShape(RoundedRectangle(cornerRadius: 14))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(Text("publish_cover_change"))
                        .accessibilityIdentifier("deck_editor_cover")
                        VStack(alignment: .leading, spacing: 6) {
                            Text("deck_editor_label_title")
                                .font(.system(size: 10, weight: .bold))
                                .kerning(0.8)
                                .foregroundColor(LoopkyColor.foregroundMuted)
                            TextField("deck_editor_title_placeholder_untitled", text: binding(title, onTitleChanged))
                                .font(.system(size: 16, weight: .bold))
                                .foregroundColor(LoopkyColor.foregroundPrimary)
                        }
                    }
                    if let titleError {
                        FieldErrorText(message: titleError)
                    }

                    // Description
                    VStack(alignment: .leading, spacing: 6) {
                        Text("deck_editor_label_description")
                            .font(.system(size: 10, weight: .bold))
                            .kerning(0.8)
                            .foregroundColor(LoopkyColor.foregroundMuted)
                        TextField("deck_editor_description_placeholder", text: binding(description, onDescriptionChanged), axis: .vertical)
                            .font(.system(size: 14))
                            .foregroundColor(LoopkyColor.foregroundSecondary)
                    }
                    if let descriptionError {
                        FieldErrorText(message: descriptionError)
                    }

                    // Tags
                    VStack(alignment: .leading, spacing: 8) {
                        Text("deck_editor_label_tags")
                            .font(.system(size: 10, weight: .bold))
                            .kerning(0.8)
                            .foregroundColor(LoopkyColor.foregroundMuted)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 6) {
                                ForEach(tags, id: \.self) { tag in
                                    TagChipView(tag: tag, onRemove: { onRemoveTag(tag) })
                                }
                                Button("deck_editor_add_tag", action: { showTagSheet = true })
                                    .buttonStyle(LoopkyOutlineButtonStyle(
                                        stroke: LoopkyColor.accentSecondary,
                                        foreground: LoopkyColor.accentSecondary,
                                        cornerRadius: 50,
                                        lineWidth: 1,
                                        fontSize: 13,
                                        fillWidth: false
                                    ))
                            }
                        }
                    }
                }
                .padding(16)
                .background(
                    RoundedRectangle(cornerRadius: 20)
                        .fill(LoopkyColor.surfaceCard)
                )
                .shadow(color: LoopkyColor.shadowElevationLow, radius: 14, x: 0, y: 4)

                if let error {
                    FieldErrorText(message: error)
                    // A Button, never a tappable Text: a bare `.onTapGesture` is invisible to
                    // VoiceOver and to `snapshot-ui`, so the one way out of a wedged session
                    // would be reachable by a finger and by nothing else.
                    if let onSignInAgain {
                        Button(NSLocalizedString("error_sign_in_again", comment: ""),
                               action: onSignInAgain)
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(LoopkyColor.accentPrimary)
                            .accessibilityIdentifier("deck_editor_sign_in_again")
                    }
                }

                // The editor is the only place a deck published before the language pair existed
                // can ever gain one — without this block such a deck can never be un-silenced.
                if let studyOptions { studyOptions }

                // Cards header
                Text(String(format: NSLocalizedString("deck_editor_cards_count", comment: ""), totalCards))
                    .font(.system(size: 16, weight: .heavy))
                    .foregroundColor(LoopkyColor.foregroundPrimary)

                // Card list. The handle on each row is a real drag target now — it has been a
                // glyph promising a gesture that did nothing since the screen was written.
                ReorderableVStack(items: cards, onMove: onMoveCard) { card in
                    Button(action: { onCardTap(card.id) }) {
                        HStack(spacing: 12) {
                            Image(systemName: "line.3.horizontal")
                                .font(.system(size: 14))
                                .foregroundColor(LoopkyColor.foregroundMuted)
                            VStack(alignment: .leading, spacing: 2) {
                                Group {
                                    if card.front.isEmpty {
                                        Text("deck_editor_card_new")
                                    } else {
                                        Text(verbatim: card.front)
                                    }
                                }
                                .font(.system(size: 15, weight: .bold))
                                .foregroundColor(LoopkyColor.foregroundPrimary)
                                Text(card.back)
                                    .font(.system(size: 13))
                                    .foregroundColor(LoopkyColor.foregroundMuted)
                            }
                            Spacer()
                            HStack(spacing: 6) {
                                if card.hasImage {
                                    Image(systemName: "photo")
                                        .font(.system(size: 14))
                                        .foregroundColor(LoopkyColor.accentSecondary)
                                }
                                if card.hasAudio {
                                    Image(systemName: "mic")
                                        .font(.system(size: 14))
                                        .foregroundColor(LoopkyColor.accentSecondary)
                                }
                            }
                        }
                        .padding(14)
                        .background(
                            RoundedRectangle(cornerRadius: 14)
                                .fill(LoopkyColor.surfaceCard)
                        )
                        .shadow(color: LoopkyColor.shadowElevationLow, radius: 8, x: 0, y: 2)
                }
                    .buttonStyle(.plain)
                }

                // The tail of the deck arrives a chunk record at a time (#52): a 20k-card deck
                // must not become 20,000 rows the moment this screen opens.
                if isLoadingCards {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("deck_editor_loading_cards")
                            .font(.system(size: 13))
                            .foregroundColor(LoopkyColor.foregroundMuted)
                    }
                    .frame(maxWidth: .infinity)
                } else if hasMoreCards {
                    Button(action: onLoadMoreCards) {
                        Text("deck_editor_load_more_cards")
                    }
                    .buttonStyle(.loopkyOutline)
                }

                // Add card button
                Button(action: onAddCard) {
                    HStack(spacing: 8) {
                        Image(systemName: "plus")
                        Text("deck_editor_add_card")
                    }
                }
                .buttonStyle(.loopkyOutline)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 40)
            // A single column of form fields and prose. After the background, so the cream
            // still reaches both edges of an iPad and only the content inside is bounded.
            .contentPane()
        }
        .loopkyScreenBackground()
        .navigationBarHidden(true)
        .sheet(isPresented: $pickingCover) {
            ImagePickerSheet(
                title: "publish_cover_label",
                subtitle: nil,
                onRemove: nil,
                onSelected: onCoverSelected,
                onClose: { pickingCover = false }
            )
        }
        .sheet(isPresented: $showTagSheet) {
            AddTagSheet(tags: tags, onAdd: onAddTag, onRemove: onRemoveTag)
        }
    }

    private func binding(_ value: String, _ onChange: @escaping (String) -> Void) -> Binding<String> {
        Binding(get: { value }, set: { onChange($0) })
    }
}

/// Small red helper text under a form field.
struct FieldErrorText: View {
    let message: String

    var body: some View {
        Text(message)
            .font(.system(size: 12, weight: .medium))
            .foregroundColor(LoopkyColor.srsAgain)
    }
}

#Preview {
    DeckEditorView(
        coverEmoji: "🇪🇸",
        title: "Spanish Basics",
        description: "Core 500 words.",
        tags: ["spanish", "language"],
        cards: [
            EditorCardData(id: "1", front: "el zorro", back: "the fox", hasImage: false, hasAudio: true),
            EditorCardData(id: "2", front: "la casa", back: "the house", hasImage: true, hasAudio: false),
        ]
    )
}
