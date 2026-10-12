import SwiftUI
import Shared

/// The recap's five pages. Everything here sits on the accent ground, so every colour is an
/// on-accent token: the surface family is cream in one theme and near-black in the other.
struct RecapView: View {
    let recap: YearRecap
    var notice: LocalizedStringKey?
    var onShare: (RecapShareTarget) -> Void = { _ in }
    var onOpenWeb: () -> Void = {}

    @State private var page = 0
    @Environment(\.loopkyWidthClass) private var widthClass

    private static let pageCount = 5
    /// Spelled out rather than read from the enum: the order is the row's design, not the enum's.
    private static let targets: [RecapShareTarget] = [
        .pubky, .x, .threads, .instagram, .whatsapp, .more,
    ]

    var body: some View {
        VStack(spacing: 0) {
            TabView(selection: $page) {
                RecapMessagePage(
                    showsFox: true,
                    title: RecapCopy.title(recap.year),
                    message: NSLocalizedString("recap_intro_body", comment: "")
                ).tag(0)
                RecapMessagePage(
                    emoji: "📚",
                    title: RecapCopy.cardsStudied(recap),
                    message: recap.cardsMastered > 0 ? RecapCopy.cardsMastered(recap) : nil
                ).tag(1)
                RecapMessagePage(
                    emoji: recap.topDeck?.coverEmoji ?? "🗂️",
                    title: RecapCopy.decksStudied(recap),
                    message: decksMessage
                ).tag(2)
                personaPage.tag(3)
                summaryPage.tag(4)
            }
            .tabViewStyle(.page(indexDisplayMode: .always))
            if page < Self.pageCount - 1 {
                // A Button, not a tap gesture on the page: VoiceOver and snapshot-ui find only
                // real controls.
                Button {
                    withAnimation { page += 1 }
                } label: {
                    Text("recap_next")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(LoopkyColor.accentPrimary)
                        .frame(minWidth: 200, minHeight: 52)
                        .background(LoopkyColor.foregroundOnAccent, in: Capsule())
                }
                .accessibilityIdentifier("recap_next")
                .padding(.bottom, 24)
            }
        }
        .overlay {
            if page == Self.pageCount - 1 { ConfettiView() }
        }
        .recapGround()
    }

    private var decksMessage: String? {
        let lines = [RecapCopy.topDeck(recap), RecapCopy.created(recap)].compactMap { $0 }
        return lines.isEmpty ? nil : lines.joined(separator: "\n")
    }

    private var personaPage: some View {
        ScrollView {
            VStack(spacing: 16) {
                Text(verbatim: RecapCopy.personaEmoji(recap.persona)).font(.system(size: 64))
                Text(LocalizedStringKey(RecapCopy.personaTitleKey(recap.persona)))
                    .font(.system(size: 38, weight: .heavy))
                    .foregroundStyle(LoopkyColor.foregroundOnAccent)
                    .multilineTextAlignment(.center)
                Text(LocalizedStringKey(RecapCopy.personaTitleKey(recap.persona) + "_body"))
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(LoopkyColor.foregroundOnAccentMuted)
                    .multilineTextAlignment(.center)
                if !recap.languages.isEmpty {
                    RecapPillGroup(title: "recap_languages_title", labels: recap.languages.map(RecapCopy.languageName))
                }
                if !recap.topics.isEmpty {
                    RecapPillGroup(title: "recap_topics_title", labels: recap.topics)
                }
            }
            .padding(.horizontal, 28)
            .padding(.vertical, 24)
            .contentPane(PaneWidth.focused)
        }
    }

    /// The picture that will be shared, and where it can go. Side by side at expanded width:
    /// stacked on a landscape iPad the picture alone takes the height and the targets fall below
    /// the fold.
    @ViewBuilder
    private var summaryPage: some View {
        let card = RecapCardPreview(text: RecapCopy.cardText(recap))
        if widthClass.isExpanded {
            HStack(spacing: 40) {
                card.frame(maxWidth: 460)
                shareColumn.frame(maxWidth: 420)
            }
            .padding(.horizontal, 32)
            .contentPane(PaneWidth.wide)
        } else {
            ScrollView {
                VStack(spacing: 20) {
                    card.frame(maxWidth: 360)
                    shareColumn
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 16)
                .contentPane(PaneWidth.focused)
            }
        }
    }

    private var shareColumn: some View {
        VStack(spacing: 12) {
            Text("recap_share_title")
                .font(.system(size: 20, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundOnAccent)
            RecapFlowLayout(spacing: 8) {
                ForEach(Self.targets, id: \.name) { target in
                    Button { onShare(target) } label: {
                        Text(LocalizedStringKey(RecapCopy.targetLabelKey(target)))
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(LoopkyColor.accentPrimary)
                            .padding(.horizontal, 18)
                            .padding(.vertical, 10)
                            .background(LoopkyColor.foregroundOnAccent, in: Capsule())
                    }
                    .accessibilityIdentifier("recap_share_\(target.name.lowercased())")
                }
            }
            Button(action: onOpenWeb) {
                Text("recap_web_link")
                    .font(.system(size: 15, weight: .semibold))
                    .underline()
                    .foregroundStyle(LoopkyColor.foregroundOnAccent)
            }
            .accessibilityIdentifier("recap_web_link")
            if let notice {
                Text(notice)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(LoopkyColor.foregroundOnAccentMuted)
            }
        }
    }
}

/// One centred statement: a mark, a headline, and a line under it.
struct RecapMessagePage: View {
    var emoji: String?
    var showsFox = false
    let title: String
    var message: String?

    var body: some View {
        VStack(spacing: 16) {
            if showsFox {
                FoxPlate(size: 96, glyphSize: 52, containerColor: LoopkyColor.foregroundOnAccent, cornerRadius: 28)
            }
            if let emoji { Text(verbatim: emoji).font(.system(size: 64)) }
            // verbatim: these are finished sentences, and one holding a % must never be a key.
            Text(verbatim: title)
                .font(.system(size: 38, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundOnAccent)
                .multilineTextAlignment(.center)
            if let message {
                Text(verbatim: message)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(LoopkyColor.foregroundOnAccentMuted)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(.horizontal, 28)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .contentPane(PaneWidth.focused)
    }
}

private struct RecapPillGroup: View {
    let title: LocalizedStringKey
    let labels: [String]

    var body: some View {
        VStack(spacing: 8) {
            Text(title)
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(LoopkyColor.foregroundOnAccentMuted)
            RecapFlowLayout(spacing: 8) {
                ForEach(labels, id: \.self) { label in
                    Text(verbatim: label)
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(LoopkyColor.accentPrimary)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(LoopkyColor.foregroundOnAccent, in: Capsule())
                }
            }
        }
    }
}

/// The share picture, shown at whatever width the page gives it. Rendered once per text rather
/// than drawn in SwiftUI, so what is on screen is the picture that gets sent.
private struct RecapCardPreview: View {
    let text: RecapCardText

    var body: some View {
        Group {
            if let image = RecapShareImage.render(text, format: RecapImageFormat.square) {
                Image(uiImage: image).resizable().aspectRatio(1, contentMode: .fit)
            } else {
                Color.clear.aspectRatio(1, contentMode: .fit)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 26))
        .accessibilityLabel(Text(verbatim: text.headline))
        .accessibilityIdentifier("recap_card")
    }
}

/// Centred wrapping rows, for pills whose count and width are not known ahead of time.
private struct RecapFlowLayout: Layout {
    var spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = rows(for: subviews, width: proposal.width ?? .infinity)
        let height = rows.map(\.height).reduce(0, +) + spacing * CGFloat(max(rows.count - 1, 0))
        return CGSize(width: rows.map(\.width).max() ?? 0, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in rows(for: subviews, width: bounds.width) {
            var x = bounds.midX - row.width / 2
            for index in row.indices {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += row.height + spacing
        }
    }

    private struct Row {
        var indices: [Int] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func rows(for subviews: Subviews, width: CGFloat) -> [Row] {
        var rows: [Row] = []
        var current = Row()
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            let needed = current.indices.isEmpty ? size.width : current.width + spacing + size.width
            if needed > width, !current.indices.isEmpty {
                rows.append(current)
                current = Row(indices: [index], width: size.width, height: size.height)
            } else {
                current.indices.append(index)
                current.width = needed
                current.height = max(current.height, size.height)
            }
        }
        if !current.indices.isEmpty { rows.append(current) }
        return rows
    }
}

extension View {
    /// The recap's ground: the brand's two accents, corner to corner.
    func recapGround() -> some View {
        background(
            LinearGradient(
                colors: [LoopkyColor.accentPrimary, LoopkyColor.accentSecondary],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()
        )
    }
}
