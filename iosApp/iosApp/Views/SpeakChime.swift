import AVFoundation

/// The chime for a correct Speak attempt.
///
/// `.ambient`, set on every play: `SpeechListener` leaves the session in `.record`, which has no
/// output at all. Ambient also honours the silent switch and mixes with other audio — the chime is
/// feedback the reader did not ask for, unlike Listen, which plays through silent mode on purpose.
/// The listener has already stopped the microphone before it reports the result that chimes.
final class SpeakChime: NSObject, AVAudioPlayerDelegate {
    static let shared = SpeakChime()

    private let player: AVAudioPlayer? = Bundle.main
        .url(forResource: "speak_correct", withExtension: "wav")
        .flatMap { try? AVAudioPlayer(contentsOf: $0) }

    override private init() {
        super.init()
        player?.delegate = self
        player?.prepareToPlay()
    }

    func play() {
        guard let player else { return }
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.ambient)
        try? session.setActive(true)
        player.currentTime = 0
        player.play()
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
