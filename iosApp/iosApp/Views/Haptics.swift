import UIKit
import Shared

/// The study loop's haptics.
///
/// *When* to buzz is the shared ViewModel's decision — it is the only place that knows whether a
/// tap changed anything — so this only maps its vocabulary onto UIKit's generators. The system
/// honours the user's haptics setting, so nothing here needs to ask.
enum Haptics {
    private static let impact = UIImpactFeedbackGenerator(style: .light)
    private static let notification = UINotificationFeedbackGenerator()

    /// The celebration's rise, as offsets from the tap. UIKit has no waveform to hand a pattern to,
    /// so a "bigger" haptic can only be built out of separate taps placed in time.
    private static let celebrationBeats: [(TimeInterval, UIImpactFeedbackGenerator.FeedbackStyle)] = [
        (0, .light),
        (0.07, .medium),
        (0.14, .heavy),
    ]

    /// Compared rather than pattern-matched: a Kotlin enum crosses as an object with class
    /// properties, not as a Swift enum, so `case .tick` is not a case to match on.
    ///
    /// Main-thread only, like every UIKit generator — which is what the caller has: `IosFlowWatcher`
    /// delivers on the main dispatcher.
    static func play(_ pattern: StudyHaptic) {
        if pattern == StudyHaptic.success {
            notification.notificationOccurred(.success)
        } else if pattern == StudyHaptic.celebration {
            playCelebration()
        } else if pattern == StudyHaptic.warning {
            notification.notificationOccurred(.warning)
        } else if pattern == StudyHaptic.failure {
            notification.notificationOccurred(.error)
        } else if pattern == StudyHaptic.tick {
            impact.impactOccurred()
        }
    }

    /// Three rising taps into the system's own success flourish. Each generator is made on the spot
    /// and captured by its own block: the shared `impact` above is one style, and a celebration that
    /// cannot get louder is just a `Success` played four times.
    private static func playCelebration() {
        for (delay, style) in celebrationBeats {
            let generator = UIImpactFeedbackGenerator(style: style)
            generator.prepare()
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                generator.impactOccurred()
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.24) {
            notification.notificationOccurred(.success)
        }
    }

    /// Warms the Taptic Engine so the first buzz of a session lands with the tap rather than a
    /// beat after it. Cheap, and it lapses on its own.
    static func prepare() {
        impact.prepare()
        notification.prepare()
    }
}
