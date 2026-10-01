import SwiftUI

/// Free text — a deck description, say — clamped to `collapsedLineLimit` behind a Read more toggle.
///
/// The toggle appears only once the text has actually overflowed, measured against an unclamped
/// copy rather than guessed from the character count: a length cap says nothing about how many
/// lines the text takes at this width and Dynamic Type size.
struct ExpandableText: View {
    let text: String
    var collapsedLineLimit: Int = 4

    @State private var isExpanded = false
    @State private var clampedHeight: CGFloat = 0
    @State private var fullHeight: CGFloat = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            styled(text)
                .lineLimit(isExpanded ? nil : collapsedLineLimit)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { clampedHeight = $0 }
                .background(alignment: .topLeading) {
                    styled(text)
                        .fixedSize(horizontal: false, vertical: true)
                        .hidden()
                        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { fullHeight = $0 }
                }
            if isExpanded || fullHeight > clampedHeight + 1 {
                Button(LocalizedStringKey(isExpanded ? "read_less" : "read_more")) {
                    withAnimation(.easeInOut(duration: 0.2)) { isExpanded.toggle() }
                }
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(LoopkyColor.accentPrimary)
                .buttonStyle(.plain)
                .accessibilityIdentifier("expandable_text_toggle")
            }
        }
        .onChange(of: text) { _, _ in isExpanded = false }
    }

    private func styled(_ text: String) -> some View {
        Text(verbatim: text)
            .font(.system(size: 14))
            .foregroundColor(LoopkyColor.foregroundSecondary)
            .lineSpacing(4)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    ExpandableText(text: String(repeating: "A long description that wraps over many lines. ", count: 12))
        .padding()
}
