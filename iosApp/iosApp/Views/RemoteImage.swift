import SwiftUI
import UIKit

/// The app's URL session for everything Swift fetches over HTTP.
///
/// **Ephemeral on purpose, and nothing may use `URLSession.shared` instead.** The shared session
/// keeps its cache and its cookie/HSTS store in two WAL-mode SQLite files, and CFNetwork holds a
/// lock on each for the life of the process. On a Mac running the iPad build, RunningBoard kills
/// a suspended app holding a lock outside the directories it allows with `0xdead10cc`, so
/// this session keeps all of it in memory instead. `AsyncImage` cannot be pointed at another
/// session, which is why `RemoteImage` exists.
enum AppURLSession {
    static let shared: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = URLCache(memoryCapacity: memoryCacheBytes, diskCapacity: 0)
        return URLSession(configuration: configuration)
    }()

    private static let memoryCacheBytes = 32 * 1024 * 1024
}

/// `AsyncImage` over `AppURLSession`, with the same `content`/`placeholder` shape.
struct RemoteImage<Content: View, Placeholder: View>: View {
    let url: URL?
    @ViewBuilder let content: (Image) -> Content
    @ViewBuilder let placeholder: () -> Placeholder

    /// Keyed by the URL it came from. `.task(id:)` re-runs on every appear, so an unchanged URL
    /// must not reset or refetch; and an image is drawn only for the URL it belongs to, so a load
    /// that finishes after the URL changed can never show the previous picture.
    @State private var loaded: (url: URL, image: UIImage)?

    var body: some View {
        Group {
            if let loaded, loaded.url == url {
                content(Image(uiImage: loaded.image))
            } else {
                placeholder()
            }
        }
        .task(id: url) { await load() }
    }

    private func load() async {
        guard let url, loaded?.url != url,
              let (data, _) = try? await AppURLSession.shared.data(from: url),
              let decoded = UIImage(data: data)
        else { return }
        loaded = (url, decoded)
    }
}
