import SwiftUI
import Shared

/// One of the landing page's starting ideas for the agent prompt.
enum PromptIdea: String, CaseIterable, Identifiable {
    case anime, series, playlist, interview, trip, notes

    var id: String { rawValue }

    var emoji: String {
        switch self {
        case .anime: return "🍥"
        case .series: return "📺"
        case .playlist: return "🎧"
        case .interview: return "💼"
        case .trip: return "✈️"
        case .notes: return "📝"
        }
    }

    var titleKey: String { "bulk_ai_idea_\(rawValue)_title" }

    /// The idea's own request, which fills the field until the reader edits it.
    var request: String { NSLocalizedString("bulk_ai_idea_\(rawValue)_request", comment: "") }
}

/// "Let your AI build the deck": the landing page's prompt box, on the screen whose premise is that
/// the deck comes from somewhere else. Copy, or an agent's icon, hands over `AgentPrompt.build` —
/// every icon copies too, because Codex and Jules open empty.
///
/// `idea` and `request` are bindings because the screen moves this card between layouts as an iPad
/// rotates, and `@State` here would be reset with the branch it left. `detailed` is for a window
/// with room to spare: the full prompt stays open, and the plugin's install commands are shown,
/// each copied on its own.
struct AgentPromptCard: View {
    @Binding var idea: PromptIdea
    @Binding var request: String
    var detailed: Bool

    @Environment(\.openURL) private var openURL
    @State private var didCopyPrompt = false
    @State private var isShowingFullPrompt = false

    private static let apps: [AgentApp] = [.claudecode, .codex, .jules, .cursor]

    private var prompt: String { AgentPrompt.shared.build(request: request) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("bulk_ai_title")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Text("bulk_ai_body")
                .font(.system(size: 13))
                .foregroundStyle(LoopkyColor.foregroundSecondary)
                .fixedSize(horizontal: false, vertical: true)

            sectionLabel("bulk_ai_ideas_label")
            ChipFlow(spacing: 8) {
                ForEach(PromptIdea.allCases) { entry in
                    ideaChip(entry)
                }
            }

            VStack(alignment: .leading, spacing: 6) {
                sectionLabel("bulk_ai_request_label")
                // No upper line limit: a capped field scrolls inside the page's own scroll.
                TextField("", text: $request, axis: .vertical)
                    .font(.system(size: 15))
                    .foregroundStyle(LoopkyColor.foregroundPrimary)
                    .lineLimit(3...)
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 12).fill(LoopkyColor.surfacePrimary))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(LoopkyColor.borderSubtle, lineWidth: 1))
                    .accessibilityIdentifier("bulk_ai_request")
            }

            Button {
                copyPrompt()
            } label: {
                Label(
                    LocalizedStringKey(didCopyPrompt ? "bulk_cli_copied" : "bulk_cli_copy"),
                    systemImage: didCopyPrompt ? "checkmark" : "doc.on.doc"
                )
            }
            .buttonStyle(LoopkySoftButtonStyle(fontSize: 15, verticalPadding: 14))
            .accessibilityIdentifier("bulk_cli_copy")

            HStack(spacing: 14) {
                sectionLabel("bulk_ai_open_in")
                ForEach(Self.apps, id: \.self) { app in
                    appButton(app)
                }
            }
            Text("bulk_ai_open_hint")
                .font(.system(size: 12))
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .fixedSize(horizontal: false, vertical: true)

            if detailed {
                fullPrompt
                pluginInstalls
            } else {
                Button("bulk_ai_full_prompt") {
                    withAnimation { isShowingFullPrompt.toggle() }
                }
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(LoopkyColor.accentPrimary)
                .accessibilityIdentifier("bulk_ai_full_prompt")
                if isShowingFullPrompt { fullPrompt }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(LoopkyColor.surfaceCard))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(LoopkyColor.borderSubtle, lineWidth: 1))
    }

    private func ideaChip(_ entry: PromptIdea) -> some View {
        let isSelected = entry == idea
        return Button {
            // Picking an idea replaces whatever was typed, as on the landing page.
            idea = entry
            request = entry.request
        } label: {
            HStack(spacing: 4) {
                Text(entry.emoji)
                Text(LocalizedStringKey(entry.titleKey))
            }
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(isSelected ? LoopkyColor.accentPrimary : LoopkyColor.foregroundPrimary)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Capsule().fill(isSelected ? LoopkyColor.accentPrimarySoft : LoopkyColor.surfacePrimary))
            .overlay(Capsule().stroke(isSelected ? LoopkyColor.accentPrimary : LoopkyColor.borderSubtle, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityIdentifier("bulk_ai_idea_\(entry.rawValue)")
    }

    private func appButton(_ app: AgentApp) -> some View {
        Button {
            copyPrompt()
            if let url = URL(string: AgentPrompt.shared.openUrl(app: app, request: request)) {
                openURL(url)
            }
        } label: {
            Image(Self.imageName(app))
                .resizable()
                .scaledToFit()
                // Cursor's mark is a single ink (a template image); the others carry their own colours.
                .foregroundStyle(LoopkyColor.foregroundPrimary)
                .frame(width: 22, height: 22)
                .frame(width: 44, height: 44)
                .background(Circle().fill(LoopkyColor.surfacePrimary))
                .overlay(Circle().stroke(LoopkyColor.borderSubtle, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(app.title)
        .accessibilityIdentifier("bulk_ai_open_\(app.name.lowercased())")
    }

    private var fullPrompt: some View {
        Text(prompt)
            .font(.system(size: 12, design: .monospaced))
            .foregroundStyle(LoopkyColor.foregroundSecondary)
            .textSelection(.enabled)
            .fixedSize(horizontal: false, vertical: true)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 10).fill(LoopkyColor.surfaceSecondary))
            .accessibilityIdentifier("bulk_ai_prompt_text")
    }

    /// One row per command: two pasted together are refused by Add Marketplace (loopky.github.io#19).
    private var pluginInstalls: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("bulk_ai_plugin_title")
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
                .padding(.top, 4)
            Text("bulk_ai_plugin_body")
                .font(.system(size: 13))
                .foregroundStyle(LoopkyColor.foregroundSecondary)
                .fixedSize(horizontal: false, vertical: true)
            ForEach(AgentPrompt.shared.pluginInstalls, id: \.app) { install in
                sectionLabel(verbatim: install.app).padding(.top, 2)
                ForEach(install.commands, id: \.self) { command in
                    commandRow(command)
                }
            }
        }
    }

    private func commandRow(_ command: String) -> some View {
        HStack {
            Text(command)
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
                .textSelection(.enabled)
            Spacer(minLength: 8)
            Button {
                UIPasteboard.general.string = command
            } label: {
                Image(systemName: "doc.on.doc")
                    .font(.system(size: 14))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
                    .frame(width: 44, height: 40)
            }
            .accessibilityLabel(Text("bulk_ai_copy_command"))
            .accessibilityIdentifier("bulk_ai_copy_command")
        }
        .padding(.leading, 12)
        .background(RoundedRectangle(cornerRadius: 10).fill(LoopkyColor.surfaceSecondary))
    }

    private func sectionLabel(_ key: LocalizedStringKey) -> some View {
        styledSectionLabel(Text(key))
    }

    /// The plugin's app names are proper nouns, not catalog keys.
    private func sectionLabel(verbatim text: String) -> some View {
        styledSectionLabel(Text(verbatim: text))
    }

    private func styledSectionLabel(_ text: Text) -> some View {
        text
            .font(.system(size: 10, weight: .bold))
            .kerning(0.8)
            .textCase(.uppercase)
            .foregroundStyle(LoopkyColor.foregroundMuted)
    }

    private func copyPrompt() {
        UIPasteboard.general.string = prompt
        withAnimation { didCopyPrompt = true }
        Task {
            try? await Task.sleep(for: .seconds(2))
            withAnimation { didCopyPrompt = false }
        }
    }

    private static func imageName(_ app: AgentApp) -> String {
        switch app {
        case AgentApp.claudecode: return "AgentClaudeCode"
        case AgentApp.codex: return "AgentCodex"
        case AgentApp.jules: return "AgentJules"
        default: return "AgentCursor"
        }
    }
}

/// Wraps its children onto as many rows as the width needs, like Compose's `FlowRow`.
private struct ChipFlow: Layout {
    var spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(subviews, width: proposal.width ?? .infinity)
        let height = rows.map(\.height).reduce(0, +) + spacing * CGFloat(max(rows.count - 1, 0))
        return CGSize(width: proposal.width ?? rows.map(\.width).max() ?? 0, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in arrange(subviews, width: bounds.width) {
            var x = bounds.minX
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

    private func arrange(_ subviews: Subviews, width: CGFloat) -> [Row] {
        var rows: [Row] = [Row()]
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            let needed = rows[rows.count - 1].indices.isEmpty ? size.width : size.width + spacing
            if rows[rows.count - 1].width + needed > width, !rows[rows.count - 1].indices.isEmpty {
                rows.append(Row())
            }
            let gap = rows[rows.count - 1].indices.isEmpty ? 0 : spacing
            rows[rows.count - 1].indices.append(index)
            rows[rows.count - 1].width += size.width + gap
            rows[rows.count - 1].height = max(rows[rows.count - 1].height, size.height)
        }
        return rows
    }
}
