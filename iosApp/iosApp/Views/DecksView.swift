import SwiftUI
import Shared

enum DecksViewState {
    case loading
    case empty
    case content(count: Int, decks: [DeckTileData])
    case error(String)
}

struct DeckTileData: Identifiable {
    let id: String
    let title: String
    let cardCount: Int
    let coverEmoji: String
    let authorLabel: String
    var isOwned: Bool = false
    var coverImage: MediaRef.Image?
    var authorPubky: String = ""
}

/// Pure layout — state comes from the shared `DecksLibraryViewModel` via `DecksScreen`.
struct DecksView: View {
    var state: DecksViewState = .loading
    var onDeckTap: (String) -> Void = { _ in }
    var onImportTap: () -> Void = {}
    var onImportFileTap: () -> Void = {}
    var onCreateDeckTap: () -> Void = {}
    /// Filtering and sorting run over the already-loaded list in the shared ViewModel — the
    /// library is small and Pubky has no query API, so there is nothing to gain from a round trip.
    var query: String = ""
    var onQueryChanged: (String) -> Void = { _ in }
    var sort: DeckSort = .recent
    var onSortChanged: (DeckSort) -> Void = { _ in }

    @Environment(\.loopkyWidthClass) private var widthClass

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // Header
                HStack {
                    Text("decks_title")
                        .font(.system(size: 28, weight: .heavy))
                        .foregroundColor(LoopkyColor.foregroundPrimary)
                    Spacer()
                    sortMenu
                }
                searchField

                // Paste CTA
                Button(action: onImportTap) {
                    HStack(spacing: 12) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 14)
                                .fill(Color.white.opacity(0.2))
                                .frame(width: 44, height: 44)
                            Image(systemName: "doc.on.clipboard")
                                .font(.system(size: 20))
                                .foregroundColor(.white)
                        }
                        VStack(alignment: .leading, spacing: 4) {
                            Text("decks_paste_cta_title")
                                .font(.system(size: 18, weight: .bold))
                                .foregroundColor(.white)
                            Text("decks_paste_cta_subtitle")
                                .font(.system(size: 13))
                                .foregroundColor(.white.opacity(0.8))
                        }
                        Spacer()
                        Image(systemName: "arrow.right")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundColor(.white)
                    }
                    .padding(22)
                    .background(
                        RoundedRectangle(cornerRadius: 28)
                            .fill(LoopkyColor.accentPrimary)
                    )
                    .shadow(color: LoopkyColor.shadowAccent, radius: 32, x: 0, y: 12)
                }
                .buttonStyle(.plain)

                // Deliberately quieter than the paste CTA: pasting is the v1 primary flow, and a
                // file import is the path for people arriving from Anki.
                Button(action: onImportFileTap) {
                    HStack(spacing: 8) {
                        Image(systemName: "doc.badge.plus").font(.system(size: 14))
                        Text("decks_import_file_cta").font(.system(size: 14, weight: .semibold))
                        Spacer()
                        Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold))
                    }
                    .foregroundStyle(LoopkyColor.accentPrimary)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 14)
                    .background(RoundedRectangle(cornerRadius: 18).fill(LoopkyColor.accentPrimarySoft))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("decks_import_file")

                switch state {
                case .loading:
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .padding(.top, 40)
                case .empty:
                    emptyBlock
                case .content(let count, let decks):
                    // Section header
                    HStack {
                        Text(String(format: NSLocalizedString("decks_library_count", comment: ""), count))
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(LoopkyColor.foregroundPrimary)
                        Spacer()
                        Text(sortLabel)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(LoopkyColor.accentPrimary)
                    }

                    // Says so, rather than leaving a blank where the grid was: a library that
                    // has decks but none matching reads as a library that lost them.
                    if decks.isEmpty && !query.isEmpty {
                        Text(verbatim: String(
                            format: NSLocalizedString("decks_search_no_results", comment: ""), query
                        ))
                            .font(.system(size: 14))
                            .foregroundColor(LoopkyColor.foregroundMuted)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    // Deck grid — column count from the window, never a fixed two. Two across on
                    // a landscape iPad gives 550pt-wide tiles with a cover the size of a paperback.
                    LazyVGrid(columns: deckGridItems(widthClass), spacing: 14) {
                        ForEach(decks) { deck in
                            DeckTileView(
                                title: deck.title,
                                cardCount: deck.cardCount,
                                coverEmoji: deck.coverEmoji,
                                authorLabel: deck.authorLabel,
                                showYouBadge: deck.isOwned,
                                coverImage: deck.coverImage,
                                authorPubky: deck.authorPubky,
                                deckId: deck.id,
                                onTap: { onDeckTap(deck.id) }
                            )
                        }
                    }
                case .error(let message):
                    VStack(spacing: 8) {
                        Text("decks_error_title")
                            .font(.system(size: 20, weight: .heavy))
                            .foregroundColor(LoopkyColor.foregroundPrimary)
                        Text(message)
                            .font(.system(size: 14))
                            .foregroundColor(LoopkyColor.foregroundMuted)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.top, 40)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 100)
            // Wide rather than Reading: this column is mostly a tile grid, which is the one thing
            // that genuinely improves with more room — it answers with more columns, not wider
            // tiles. After the background, so the cream still reaches both edges.
            .contentPane(PaneWidth.wide)
        }
        .loopkyScreenBackground()
    }

    /// A plain field rather than `.searchable`: the library is a scrolling column inside a tab,
    /// and `.searchable` would put the field in a navigation bar this screen deliberately hides.
    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 14))
                .foregroundColor(LoopkyColor.foregroundMuted)
            TextField(
                "decks_search_placeholder",
                text: Binding(get: { query }, set: onQueryChanged)
            )
            .font(.system(size: 15))
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .accessibilityIdentifier("decks_search")
            if !query.isEmpty {
                Button { onQueryChanged("") } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 15))
                        .foregroundColor(LoopkyColor.foregroundMuted)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 12).fill(LoopkyColor.surfaceCard))
    }

    /// The header echoes the chosen sort. It read a hardcoded "Recent" before, which became
    /// wrong the moment the menu below it did anything.
    private var sortLabel: LocalizedStringKey {
        switch sort {
        case .alphabetical: return "decks_sort_alphabetical"
        case .cardcount: return "decks_sort_cards"
        default: return "decks_sort_recent"
        }
    }

    private var sortMenu: some View {
        Menu {
            Picker("decks_sort_recent", selection: Binding(get: { sort }, set: onSortChanged)) {
                Text("decks_sort_recent").tag(DeckSort.recent)
                Text("decks_sort_alphabetical").tag(DeckSort.alphabetical)
                Text("decks_sort_cards").tag(DeckSort.cardcount)
            }
        } label: {
            Image(systemName: "arrow.up.arrow.down")
                .font(.system(size: 18))
                .foregroundColor(LoopkyColor.foregroundPrimary)
        }
        .accessibilityIdentifier("decks_sort")
    }

    private var emptyBlock: some View {
        VStack(spacing: 14) {
            Text("📚").font(.system(size: 48))
            Text("decks_empty_title")
                .font(.system(size: 20, weight: .heavy))
                .foregroundColor(LoopkyColor.foregroundPrimary)
            Text("decks_empty_subtitle")
                .font(.system(size: 14))
                .foregroundColor(LoopkyColor.foregroundMuted)
                .multilineTextAlignment(.center)
            Button("decks_empty_create", action: onCreateDeckTap)
                .buttonStyle(.loopkyCompactFilled)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 32)
    }
}

#Preview {
    DecksView(
        state: .content(
            count: 2,
            decks: [
                DeckTileData(
                    id: "1",
                    title: "Spanish Basics",
                    cardCount: 42,
                    coverEmoji: "🇪🇸",
                    authorLabel: "Cosmic-Crystal-Panda",
                    isOwned: true
                ),
                DeckTileData(
                    id: "2",
                    title: "Anatomy 101",
                    cardCount: 128,
                    coverEmoji: "🧠",
                    authorLabel: "Cosmic-Crystal-Panda",
                    isOwned: true
                ),
            ]
        )
    )
}
