import SwiftUI
import Shared

/// Fix a card while triaging it, rather than discarding it for a typo the parser made.
///
/// Deliberately not VM-driven, mirroring Android's `TriageEditCardRoute`: the draft is already in
/// memory and an edit is a field write on `ImportRepository`, so there is no async work for a
/// ViewModel to own. `IosDependencies.triageRow(rowIndex:)` is the read, and the two setters are
/// the writes.
struct TriageEditCardScreen: View {
    let rowIndex: Int
    var onBack: () -> Void = {}

    @State private var front = ""
    @State private var back = ""
    @State private var frontImage: DraftCardImage?
    @State private var backImage: DraftCardImage?
    @State private var didLoad = false
    @State private var pickingFront = false
    @State private var pickingBack = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header
                side(
                    "triage_front_label",
                    placeholder: "edit_card_front_placeholder",
                    text: $front,
                    image: frontImage,
                    onPick: { pickingFront = true },
                    fieldIdentifier: "triage_edit_front"
                )
                side(
                    "triage_back_label",
                    placeholder: "edit_card_back_placeholder",
                    text: $back,
                    image: backImage,
                    onPick: { pickingBack = true },
                    fieldIdentifier: "triage_edit_back"
                )
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 40)
            // A single column of form fields. After the background, so the cream still reaches
            // both edges of an iPad and only the content inside is bounded.
            .contentPane()
        }
        .loopkyScreenBackground()
        .navigationBarHidden(true)
        .sheet(isPresented: $pickingFront) {
            ImagePickerSheet(
                title: "image_sheet_front_title",
                subtitle: "image_sheet_front_subtitle",
                onRemove: frontImage != nil ? { apply(nil, toFront: true) } : nil,
                onSelected: { apply(draftImage($0), toFront: true) },
                onClose: { pickingFront = false }
            )
        }
        .sheet(isPresented: $pickingBack) {
            ImagePickerSheet(
                title: "image_sheet_back_title",
                subtitle: "image_sheet_back_subtitle",
                onRemove: backImage != nil ? { apply(nil, toFront: false) } : nil,
                onSelected: { apply(draftImage($0), toFront: false) },
                onClose: { pickingBack = false }
            )
        }
        .onAppear(perform: load)
    }

    private var header: some View {
        HStack {
            Button("edit_card_cancel", action: onBack)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(LoopkyColor.accentPrimary)
            Spacer()
            Text("edit_card_title")
                .font(.system(size: 18, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Spacer()
            Button("edit_card_save", action: save)
                .buttonStyle(LoopkyCompactFilledButtonStyle(
                    fill: LoopkyColor.accentPrimary,
                    foreground: .white
                ))
                .accessibilityIdentifier("triage_edit_save")
        }
    }

    // swiftlint:disable:next function_parameter_count
    private func side(
        _ label: LocalizedStringKey,
        placeholder: LocalizedStringKey,
        text: Binding<String>,
        image: DraftCardImage?,
        onPick: @escaping () -> Void,
        fieldIdentifier: String
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.system(size: 10, weight: .bold))
                .kerning(0.8)
                .foregroundStyle(LoopkyColor.foregroundMuted)
            if let image {
                DraftImagePreview(image: image)
                    .frame(maxHeight: 140)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }
            // Removing is inside the picker sheet, as it is on the card editor — one place to
            // change a picture, not two.
            Button(action: onPick) {
                HStack(spacing: 6) {
                    Image(systemName: "photo").font(.system(size: 12))
                    Text(image != nil ? "edit_card_image" : "edit_card_add_image")
                        .font(.system(size: 12, weight: .semibold))
                }
                .foregroundStyle(LoopkyColor.accentPrimary)
            }
            TextField(placeholder, text: text, axis: .vertical)
                .font(.system(size: 16))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
                .lineLimit(2...6)
                .padding(14)
                .background(RoundedRectangle(cornerRadius: 14).fill(LoopkyColor.surfaceCard))
                .overlay(
                    RoundedRectangle(cornerRadius: 14).stroke(LoopkyColor.borderSubtle, lineWidth: 1)
                )
                .accessibilityIdentifier(fieldIdentifier)
        }
    }

    /// Read once. A second read would overwrite what is being typed with what the draft still says.
    private func load() {
        guard !didLoad else { return }
        didLoad = true
        // The draft is cleared by the publish step, so a row that is gone means the flow moved on
        // underneath this screen. Leaving is the only honest answer; editing nothing is not.
        guard let row = IosDependencies.shared.triageRow(rowIndex: Int32(rowIndex)) else {
            onBack()
            return
        }
        front = row.front
        back = row.back
        frontImage = row.frontImage
        backImage = row.backImage
    }

    /// Pictures are written as they are chosen, matching Android: the picker has already committed
    /// the user to the image, and Cancel is about the text.
    private func apply(_ image: DraftCardImage?, toFront: Bool) {
        if toFront { frontImage = image } else { backImage = image }
        IosDependencies.shared.setTriageRowImage(
            rowIndex: Int32(rowIndex),
            isFront: toFront,
            image: image
        )
    }

    private func save() {
        IosDependencies.shared.updateTriageRow(rowIndex: Int32(rowIndex), front: front, back: back)
        onBack()
    }

    private func draftImage(_ selection: ImageSelection) -> DraftCardImage {
        switch selection {
        case .web(let url):
            return DraftCardImage(url: url, bytes: nil, mime: nil)
        case .gallery(let bytes, let mime):
            return DraftCardImage(url: nil, bytes: bytes.toKotlinByteArray(), mime: mime)
        }
    }
}

/// A picture that is not on a homeserver yet, so `CardMediaImage` — which resolves a `MediaRef`
/// under a deck — has nothing to resolve. A draft image is a web URL or raw bytes, and nothing else.
private struct DraftImagePreview: View {
    let image: DraftCardImage

    var body: some View {
        if let data = image.bytes?.toData(), let picture = UIImage(data: data) {
            Image(uiImage: picture).resizable().aspectRatio(contentMode: .fit)
        } else if let url = image.url, let link = URL(string: url) {
            AsyncImage(url: link) { loaded in
                loaded.resizable().aspectRatio(contentMode: .fit)
            } placeholder: {
                RoundedRectangle(cornerRadius: 12).fill(LoopkyColor.surfaceCard)
            }
        } else {
            RoundedRectangle(cornerRadius: 12).fill(LoopkyColor.surfaceCard)
        }
    }
}
