import PhotosUI
import SwiftUI
import Shared

/// The image a user chose: a web address saved as-is, or compressed gallery bytes.
enum ImageSelection {
    case web(url: String)
    case gallery(bytes: Data, mime: String)
}

/// Pick a picture for a card side or a deck cover: search the web, paste an address, or take one
/// from the photo library. Mirrors Android's `ImagePickerSheet`.
///
/// The gallery leg uses SwiftUI's `PhotosPicker`, which runs out of process — so it needs **no**
/// `NSPhotoLibraryUsageDescription` and never prompts. Bytes come back already scoped to the one
/// image the user picked.
struct ImagePickerSheet: View {
    var title: LocalizedStringKey
    var subtitle: LocalizedStringKey?
    /// Non-nil when the caller already has a picture, so the sheet can offer to drop it.
    var onRemove: (() -> Void)?
    var onSelected: (ImageSelection) -> Void
    var onClose: () -> Void

    @State private var viewModel: ImageSheetViewModel?
    @State private var uiState: ImageSheetUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var query = ""
    @State private var pickerItem: PhotosPickerItem?
    @State private var isCompressing = false
    @State private var localError: LocalizedStringKey?

    private let columns = [
        GridItem(.flexible(), spacing: 8),
        GridItem(.flexible(), spacing: 8),
        GridItem(.flexible(), spacing: 8),
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if let subtitle {
                        Text(subtitle)
                            .font(.system(size: 13))
                            .foregroundStyle(LoopkyColor.foregroundMuted)
                    }
                    searchField
                    sourceButtons
                    if let localError {
                        Text(localError).font(.system(size: 13)).foregroundStyle(LoopkyColor.danger)
                    }
                    if let error = errorKey {
                        Text(error).font(.system(size: 13)).foregroundStyle(LoopkyColor.danger)
                    }
                    content
                }
                .padding(20)
            }
            .loopkyScreenBackground()
            .navigationTitle(Text(title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("image_sheet_done", action: onClose)
                }
            }
        }
        .onAppear { attach() }
        .onDisappear { detach() }
        .onChange(of: pickerItem) { _, item in
            guard let item else { return }
            Task { await loadFromLibrary(item) }
        }
    }

    private var searchField: some View {
        TextField("image_sheet_search_placeholder", text: Binding(
            get: { query },
            set: { query = $0; viewModel?.onQueryChange(query: $0) }
        ))
        .textFieldStyle(.roundedBorder)
        .autocorrectionDisabled()
        .textInputAutocapitalization(.never)
    }

    private var sourceButtons: some View {
        HStack(spacing: 10) {
            PhotosPicker(selection: $pickerItem, matching: .images) {
                label("photo.on.rectangle", "image_sheet_from_gallery")
            }
            .buttonStyle(.plain)

            // `PasteButton`, not a plain button reading `UIPasteboard.general.string`.
            //
            // Reading the pasteboard directly raises the system's "would like to paste" prompt and
            // returns nil *before* the user answers it — so the first tap always reported "nothing
            // to paste" no matter what was on the clipboard. With `PasteButton` the tap is itself
            // the consent: no prompt, and the value arrives.
            PasteButton(payloadType: String.self) { strings in
                guard let pasted = strings.first(where: { !$0.isEmpty }) else {
                    localError = "image_sheet_paste_empty"
                    return
                }
                query = pasted
                viewModel?.onQueryChange(query: pasted)
                localError = nil
            }
            .labelStyle(.iconOnly)
            .buttonBorderShape(.roundedRectangle(radius: 14))
            .tint(LoopkyColor.accentPrimarySoft)
            .foregroundStyle(LoopkyColor.accentPrimary)
            .frame(maxWidth: .infinity)

            if let onRemove {
                Button(role: .destructive) { onRemove(); onClose() } label: {
                    label("trash", "image_sheet_remove")
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func label(_ systemImage: String, _ text: LocalizedStringKey) -> some View {
        VStack(spacing: 4) {
            Image(systemName: systemImage).font(.system(size: 16))
            Text(text).font(.system(size: 11, weight: .semibold))
        }
        .foregroundStyle(LoopkyColor.accentPrimary)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 14).fill(LoopkyColor.accentPrimarySoft))
    }

    @ViewBuilder
    private var content: some View {
        if isCompressing {
            HStack(spacing: 8) { ProgressView().controlSize(.small); Text("image_sheet_photo") }
        } else if let link = uiState?.link {
            linkPreview(link)
        } else if uiState?.isLoading == true {
            ProgressView().frame(maxWidth: .infinity).padding(.top, 20)
        } else if let photos = uiState?.photos, !photos.isEmpty {
            grid(photos)
        } else if !query.isEmpty, uiState?.error == nil {
            Text("image_sheet_no_results")
                .font(.system(size: 13))
                .foregroundStyle(LoopkyColor.foregroundMuted)
        }
    }

    /// A typed or pasted address short-circuits the grid: there is nothing to search for.
    ///
    /// The two `ImageLink` cases are not interchangeable. `Remote` is a URL and is stored as-is,
    /// like an Unsplash pick. `Inline` is the decoded body of a `data:` URI — the URI *is* the
    /// image, so there is nothing to point a stored ref at and it has to be uploaded as a blob.
    @ViewBuilder
    private func linkPreview(_ link: ImageLink) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("image_sheet_link_preview")
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(LoopkyColor.foregroundMuted)

            if let remote = link as? ImageLinkRemote {
                AsyncImage(url: URL(string: remote.url)) { image in
                    image.resizable().scaledToFit()
                } placeholder: {
                    ProgressView()
                }
                .frame(maxHeight: 180)
                .clipShape(RoundedRectangle(cornerRadius: 12))
            }

            HStack {
                Button("image_sheet_link_clear") {
                    query = ""
                    viewModel?.onLinkCleared()
                }
                .font(.system(size: 13))
                Spacer()
                Button("image_sheet_done") { use(link) }
                    .buttonStyle(.loopkyCompactFilled)
            }
        }
    }

    private func use(_ link: ImageLink) {
        if let remote = link as? ImageLinkRemote {
            onSelected(.web(url: remote.url))
        } else if let inline = link as? ImageLinkInline {
            onSelected(.gallery(bytes: inline.bytes.toData(), mime: inline.mime))
        }
        onClose()
    }

    private func grid(_ photos: [UnsplashPhoto]) -> some View {
        LazyVGrid(columns: columns, spacing: 8) {
            ForEach(photos, id: \.id) { photo in
                Button {
                    viewModel?.onPhotoSelected(photo: photo)
                    viewModel?.onPhotoUsed()
                    onSelected(.web(url: photo.fullUrl))
                    onClose()
                } label: {
                    AsyncImage(url: URL(string: photo.thumbUrl)) { image in
                        image.resizable().scaledToFill()
                    } placeholder: {
                        RoundedRectangle(cornerRadius: 10).fill(LoopkyColor.borderSubtle)
                    }
                    .frame(height: 92)
                    .clipped()
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(photo.authorName))
            }
        }
    }

    private var errorKey: LocalizedStringKey? {
        switch uiState?.error {
        case is UnsplashErrorInvalidKey: return "image_sheet_error_invalid_key"
        case is UnsplashErrorMissingKey: return "image_sheet_error_missing_key"
        case is UnsplashErrorRateLimited: return "image_sheet_error_rate_limited"
        case is UnsplashErrorUnavailable: return "image_sheet_error_unavailable"
        default: return nil
        }
    }

    /// Compresses through the shared `MediaProcessor` rather than uploading the original: a photo
    /// off a modern camera is several megabytes against a 1GB homeserver quota.
    private func loadFromLibrary(_ item: PhotosPickerItem) async {
        isCompressing = true
        defer { isCompressing = false; pickerItem = nil }
        guard let raw = try? await item.loadTransferable(type: Data.self) else {
            localError = "image_sheet_image_unreadable"
            return
        }
        onSelected(.gallery(bytes: raw, mime: "image/jpeg"))
        onClose()
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.imageSheetViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? ImageSheetUiState }
        vm.onSheetOpened()
    }

    private func detach() {
        viewModel?.release()
        viewModel = nil
        stateSink = nil
    }
}
