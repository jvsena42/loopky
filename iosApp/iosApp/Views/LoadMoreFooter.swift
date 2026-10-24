import SwiftUI

/// The sentinel at the foot of a paged grid: asks for the next page while it is on screen, and shows
/// the spinner while that page is on its way.
///
/// Visibility comes from `onScrollVisibilityChange`, not `onAppear`. The footer sits beside the lazy
/// grid rather than inside it, so `onAppear` fires when the footer is created, wherever the reader
/// has scrolled to, and never again while `hasMore` holds: browse loaded page two on its own and
/// stopped there (#551). It asks when it scrolls into view and again when a page lands with it still
/// in view; the ViewModel guards the rest — see `SectionState.canLoadMore`.
struct LoadMoreFooter: View {
    let isLoading: Bool
    let onLoadMore: () -> Void

    @State private var isVisible = false

    var body: some View {
        HStack {
            Spacer()
            if isLoading { ProgressView().tint(LoopkyColor.accentPrimary) }
            Spacer()
        }
        .frame(height: 44)
        .onScrollVisibilityChange { visible in
            isVisible = visible
            if visible { onLoadMore() }
        }
        .onChange(of: isLoading) { _, loading in
            if !loading && isVisible { onLoadMore() }
        }
        .accessibilityIdentifier("load_more_footer")
    }
}
