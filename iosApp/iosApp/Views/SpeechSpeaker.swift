import AVFoundation

/// Minimal native TTS used by the study session's `Speak` effect.
final class SpeechSpeaker: NSObject, AVSpeechSynthesizerDelegate {
    static let shared = SpeechSpeaker()

    private let synthesizer = AVSpeechSynthesizer()

    /// The utterance whose end is still owed to its caller. Matched by identity, so the cancel an
    /// interrupted utterance reports is not mistaken for the new one finishing.
    private var current: AVSpeechUtterance?
    private var onDone: (() -> Void)?

    override private init() {
        super.init()
        synthesizer.delegate = self
    }

    /// Reads `text` in `languageTag` (BCP-47, e.g. `"es-ES"`), interrupting anything in progress.
    /// `rate` multiplies the normal speed, so repeat Listen taps can slow the same word down.
    /// `onDone` runs once on the main queue when this utterance ends — finished or interrupted —
    /// and only when the call returned true.
    ///
    /// The voice is set explicitly rather than left to default: without it AVSpeechSynthesizer
    /// uses the *reader's* device language, which reads a Spanish card with an English voice.
    /// Returns false when iOS has no voice installed for the tag, so the caller can say so instead
    /// of letting the wrong accent pass for the right one.
    @discardableResult
    func speak(_ text: String, languageTag: String, rate: Float = 1, onDone: (() -> Void)? = nil) -> Bool {
        guard let voice = AVSpeechSynthesisVoice(language: languageTag) else { return false }
        synthesizer.stopSpeaking(at: .immediate)
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = voice
        utterance.rate = Self.utteranceRate(multiplier: rate)
        current = utterance
        self.onDone = onDone
        synthesizer.speak(utterance)
        return true
    }

    /// `AVSpeechUtterance.rate` is a 0...1 scale, not a multiplier: the default is normal speed and
    /// the minimum is about half of it, so scaling the default by 0.5 slows speech by a quarter.
    private static func utteranceRate(multiplier: Float) -> Float {
        let slowest = AVSpeechUtteranceMinimumSpeechRate
        let normal = AVSpeechUtteranceDefaultSpeechRate
        let position = (min(max(multiplier, 0.5), 1) - 0.5) / 0.5
        return slowest + (normal - slowest) * position
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        finish(utterance)
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        finish(utterance)
    }

    private func finish(_ utterance: AVSpeechUtterance) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.current === utterance else { return }
            let done = self.onDone
            self.current = nil
            self.onDone = nil
            done?()
        }
    }

    /// BCP-47 tags iOS has voices for, for the deck language picker.
    func availableLanguages() -> [String] {
        Array(Set(AVSpeechSynthesisVoice.speechVoices().map(\.language))).sorted()
    }
}
