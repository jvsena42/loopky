import AVFoundation
import Speech

/// Native speech recognition for the study session's Speak practice.
///
/// The counterpart to `SpeechSpeaker`, and written the same way: **no Kotlin binding**. The shared
/// ViewModel emits `StartSpeechRecognition(expected:languageTag:)` and takes the answer back
/// through `onSpeechResult` / `onSpeechError`, so the platform half can live entirely in Swift —
/// which is why iOS needs no `IosSpeechRecognizer` for Speak to work.
final class SpeechListener {
    static let shared = SpeechListener()

    private let audioEngine = AVAudioEngine()
    private var recognizer: SFSpeechRecognizer?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?

    private init() {}

    /// Why a listen produced no answer, so the sheet can say which of them it was rather than
    /// closing on all of them alike. Mirrors the shared `SpeechError` the ViewModel takes.
    enum Failure {
        /// The user said no to the microphone or to speech recognition.
        case permission
        /// iOS has no recognition service available at all right now.
        case unavailable
        /// Recognition exists, but not for the deck's declared language.
        case languageUnavailable
        /// Recognition ran but matched nothing.
        case noMatch
    }

    /// Whether iOS can recognise speech in `languageTag` at all.
    ///
    /// **Keyed on the deck's language, never the device's.** Given no locale `SFSpeechRecognizer`
    /// falls back to the reader's own, which transcribes a Spanish answer with an English model —
    /// a wrong answer that looks like a working feature.
    func isAvailable(languageTag: String) -> Bool {
        SFSpeechRecognizer(locale: Locale(identifier: languageTag))?.isAvailable == true
    }

    /// Start listening. `onResult` fires once with the final transcription; `onFailure` once with
    /// why it did not. Exactly one of the two is called.
    func listen(
        languageTag: String,
        onResult: @escaping (String) -> Void,
        onFailure: @escaping (Failure) -> Void
    ) {
        stop()
        authorize { [weak self] granted in
            guard let self else { return }
            guard granted else {
                onFailure(.permission)
                return
            }
            do {
                try self.start(languageTag: languageTag, onResult: onResult, onFailure: onFailure)
            } catch {
                onFailure(.unavailable)
            }
        }
    }

    /// Tear the session down. Safe to call when nothing is running, which is how the study screen
    /// uses it — the sheet leaving the listening phase stops the microphone either way.
    func stop() {
        task?.cancel()
        task = nil
        request?.endAudio()
        request = nil
        if audioEngine.isRunning {
            audioEngine.stop()
            audioEngine.inputNode.removeTap(onBus: 0)
        }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    // MARK: - Private

    /// Both permissions, in order: speech recognition, then the microphone. Asking for the mic
    /// first would put a recording prompt in front of someone who has not yet agreed to the thing
    /// the recording is *for*.
    private func authorize(_ completion: @escaping (Bool) -> Void) {
        SFSpeechRecognizer.requestAuthorization { status in
            guard status == .authorized else {
                DispatchQueue.main.async { completion(false) }
                return
            }
            Self.requestMicrophone { granted in
                DispatchQueue.main.async { completion(granted) }
            }
        }
    }

    private static func requestMicrophone(_ completion: @escaping (Bool) -> Void) {
        if #available(iOS 17.0, *) {
            AVAudioApplication.requestRecordPermission(completionHandler: completion)
        } else {
            AVAudioSession.sharedInstance().requestRecordPermission(completion)
        }
    }

    private func start(
        languageTag: String,
        onResult: @escaping (String) -> Void,
        onFailure: @escaping (Failure) -> Void
    ) throws {
        // A nil recogniser and an unavailable one are different failures: the first says iOS has
        // no model for the deck's language — which the reader can install — and the second that
        // recognition is off right now.
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: languageTag)) else {
            onFailure(.languageUnavailable)
            return
        }
        guard recognizer.isAvailable else {
            onFailure(.unavailable)
            return
        }
        self.recognizer = recognizer

        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .measurement, options: .duckOthers)
        try session.setActive(true, options: .notifyOthersOnDeactivation)

        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        self.request = request

        let input = audioEngine.inputNode
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0)) { buffer, _ in
            request.append(buffer)
        }
        audioEngine.prepare()
        try audioEngine.start()

        var delivered = false
        task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            // A final result and an error can both arrive; whichever lands first wins, and the
            // other is dropped rather than double-reporting one attempt.
            guard !delivered else { return }
            if let result, result.isFinal {
                delivered = true
                self?.stop()
                onResult(result.bestTranscription.formattedString)
                return
            }
            if error != nil {
                delivered = true
                self?.stop()
                onFailure(.noMatch)
            }
        }
    }
}
