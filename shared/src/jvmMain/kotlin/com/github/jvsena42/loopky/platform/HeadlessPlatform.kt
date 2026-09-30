package com.github.jvsena42.loopky.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The platform collaborators a headless client answers for by *not* doing them.
 *
 * All five exist because `sharedModule` resolves them, not because the CLI has any use for them —
 * `:shared` is one Koin graph and a missing binding is a start-up failure, so the JVM target has
 * to answer for the whole surface. Each returns the honest "no" rather than a stub that pretends:
 * a `Speaker` that silently succeeds would let a study command claim it read a card aloud.
 */

/** No TTS. `Deck.speechReady` is about the deck; this is about the host, and a server has no voice. */
class NoSpeaker : Speaker {
    override fun speak(text: String, languageTag: String): SpeakOutcome = SpeakOutcome.EngineUnavailable
    override fun availableLanguages(): List<String> = emptyList()
}

/** No microphone. [listen] completes immediately with the unavailable error rather than hanging. */
class NoSpeechRecognizer : SpeechRecognizer {
    override fun isAvailable(): Boolean = false
    override fun listen(languageTag: String?): Flow<SpeechEvent> =
        flowOf(SpeechEvent.Error(SpeechError.Unavailable))
}

/**
 * No Pubky Ring on this machine, which is the *correct* answer even when the user has it on their
 * phone: [isInstalled] asks whether a `pubkyauth://` deeplink has somewhere to go **here**, and on
 * a headless box it does not. Answering false is what makes the desktop sign-in a printed QR code
 * — the same fallback a tablet with no Ring gets — rather than a deeplink into nothing.
 */
class NoPubkyRingPresence : PubkyRingPresence {
    override fun isInstalled(): Boolean = false
    override fun canImportKey(): Boolean = false
    override val installUrl: String = PUBKY_RING_INSTALL_URL
}

/** No OS password manager to save a recovery phrase into. */
class NoPasswordManagerPresence : PasswordManagerPresence {
    override fun canSave(): Boolean = false
}

/**
 * No image decoder at all: bytes in, the same bytes out.
 *
 * This is the one no-op here that exists for the *packaging* rather than for the host, and it is
 * what makes `loopky` a single file (#210). [JvmMediaProcessor] reaches `javax.imageio`, which
 * reaches `java.awt` — and `native-image` cannot fold AWT into the executable on Linux. It ships
 * it *beside* the binary instead: `libawt.so`, `libawt_headless.so`, `libawt_xawt.so`,
 * `libjavajpeg.so`, `liblcms.so`. Seven files where the install story is one `curl` into
 * `~/.local/bin`, and one of them an X11 library, in a sandbox with no display.
 *
 * Nothing is given up for a card whose picture is a **URL** (#167) — no bytes cross the wire and
 * no media quota is spent — which is every path but one. The exception is `.apkg` import (#211),
 * which uploads the archive's blobs, and there this **is** given up: those pictures go to the
 * homeserver at full resolution where a phone would send 1024px JPEG. The CLI says so rather than
 * hiding it — `import --dry-run` reports the byte total and a real run warns on stderr — because
 * the alternative is not "compress them anyway", it is "there is no single-file binary".
 *
 * That path does not route through here at all: `ApkgBlobs` in `:cli` takes the reader's
 * `compressImage` parameter directly. Reaching through the Koin graph for a call that must never
 * do anything is an invitation to bind something that does, and this binding is the one thing
 * standing between the build and five `libawt*.so` files. Pass-through rather than a throw for the
 * reason [JvmMediaProcessor] gives for its own decode failure: a caller that reaches here should
 * upload a picture that is merely larger than intended, not lose it.
 *
 * The result is byte-for-byte what `JvmMediaProcessor.passThrough` returns, deliberately — two
 * degrade paths that answer differently for the same input are a bug waiting to be found by
 * whichever one a card happened to go through. Zero width and height for the reason it gives: a
 * fabricated aspect ratio would be stored on the card and shown by both apps.
 */
class PassThroughMediaProcessor : MediaProcessor {
    override suspend fun compressImage(bytes: ByteArray, maxDimension: Int, quality: Int): ProcessedImage =
        ProcessedImage(bytes = bytes, mime = "image/jpeg", width = 0, height = 0)
}

/**
 * Where to get Pubky Ring, for a message printed on a terminal rather than a button.
 *
 * The web page rather than a store deeplink, because the reader is at a keyboard and the phone
 * that needs the app is a different device.
 */
private const val PUBKY_RING_INSTALL_URL = "https://pubkyring.app"
