import SwiftUI

/// Cards / Due / New / Mastered, under a deck's metadata. Lives beside `DeckDetailView` rather
/// than in it only because that file is at SwiftLint's per-file line ceiling.
struct StatsBarView: View {
    let totalCards: Int
    let dueLabel: String
    let newCards: Int
    let masteredPercent: String
    /// False for a deck you have neither published nor followed. Due and Mastered are facts about
    /// *your* study of a deck, and on a stranger's deck they are necessarily zero and necessarily
    /// meaningless — "442 Due" beside a Follow button promises study you cannot start. The room
    /// they leave goes to the two counts that are facts about the *deck*.
    var showProgress: Bool = true
    var followerCount: Int = 0
    var clonedCount: Int = 0

    var body: some View {
        HStack {
            StatColumn(value: "\(totalCards)", label: "component_stats_bar_cards", valueColor: LoopkyColor.foregroundPrimary)
            if showProgress {
                Divider().frame(height: 32).overlay(LoopkyColor.borderSubtle)
                StatColumn(value: dueLabel, label: "component_stats_bar_due", valueColor: LoopkyColor.accentPrimary)
                Divider().frame(height: 32).overlay(LoopkyColor.borderSubtle)
                StatColumn(value: "\(newCards)", label: "component_stats_bar_new", valueColor: LoopkyColor.foregroundPrimary)
                Divider().frame(height: 32).overlay(LoopkyColor.borderSubtle)
                StatColumn(value: masteredPercent, label: "component_stats_bar_mastered", valueColor: LoopkyColor.srsGood)
            } else {
                // Both hidden at zero rather than shown as "0": the indexer answers with nothing
                // when it is behind or unreachable, so a zero here would be a lie in both cases.
                if followerCount > 0 {
                    Divider().frame(height: 32).overlay(LoopkyColor.borderSubtle)
                    StatColumn(
                        value: "\(followerCount)",
                        label: "component_stats_bar_followers",
                        valueColor: LoopkyColor.foregroundPrimary
                    )
                }
                if clonedCount > 0 {
                    Divider().frame(height: 32).overlay(LoopkyColor.borderSubtle)
                    StatColumn(
                        value: "\(clonedCount)",
                        label: "component_stats_bar_copies",
                        valueColor: LoopkyColor.foregroundPrimary
                    )
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 20)
                .fill(LoopkyColor.surfaceSecondary)
        )
    }
}

struct StatColumn: View {
    let value: String
    let label: LocalizedStringKey
    let valueColor: Color

    var body: some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.system(size: 22, weight: .heavy))
                .foregroundColor(valueColor)
            Text(label)
                .font(.system(size: 11, weight: .medium))
                .foregroundColor(LoopkyColor.foregroundMuted)
        }
        .frame(maxWidth: .infinity)
    }
}
