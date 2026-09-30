import SwiftUI

struct TagChipView: View {
    let tag: String
    var onTap: (() -> Void)?
    var onRemove: (() -> Void)?
    /// Fills the chip, matching Android's `InputChip` selected colours.
    ///
    /// Discover used to signal the choice by fading every *other* chip to 50% instead, which left
    /// the chosen chip looking exactly like an unpicked one and made the rest read as disabled
    /// rather than unselected. Marking the selection is also the only form VoiceOver can report.
    var isSelected: Bool = false

    var body: some View {
        // A `Button` when it does something, not an `onTapGesture`: a tap gesture on a shape is
        // not a control, so VoiceOver never announces it and UI automation cannot find it — the
        // chips on deck detail were tappable by a finger and by nothing else.
        if let onTap {
            Button(action: onTap) { chip }
                .buttonStyle(.plain)
                // Without this the selection is carried by colour alone, so VoiceOver announces a
                // row of identical buttons and `snapshot-ui` cannot tell which one is chosen.
                .accessibilityAddTraits(isSelected ? [.isSelected] : [])
                .accessibilityIdentifier("tag_chip_\(tag)")
        } else {
            chip
        }
    }

    private var chip: some View {
        HStack(spacing: 4) {
            Text(String(format: NSLocalizedString("component_tag_chip_label", comment: ""), tag))
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(isSelected ? LoopkyColor.foregroundOnAccent : LoopkyColor.accentSecondary)
            if let onRemove {
                Button(action: onRemove) {
                    Image(systemName: "xmark")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(isSelected ? LoopkyColor.foregroundOnAccent : LoopkyColor.accentSecondary)
                }
                .accessibilityLabel("component_tag_chip_remove")
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(Capsule().fill(isSelected ? LoopkyColor.accentSecondary : LoopkyColor.accentSecondarySoft))
    }
}
