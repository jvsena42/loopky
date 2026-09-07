package com.github.jvsena42.loopky.di

import com.github.jvsena42.loopky.data.homegate.HomegateClient
import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.data.nexus.HttpFetcher
import com.github.jvsena42.loopky.data.nexus.JvmHttpFetcher
import com.github.jvsena42.loopky.data.nexus.NexusClient
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.UniffiPubkyClient
import com.github.jvsena42.loopky.data.storage.AppPreferences
import com.github.jvsena42.loopky.data.storage.ConfigHome
import com.github.jvsena42.loopky.data.storage.DeckCacheStore
import com.github.jvsena42.loopky.data.storage.FileAppPreferences
import com.github.jvsena42.loopky.data.storage.FileDeckCacheStore
import com.github.jvsena42.loopky.data.storage.FileLocalKeyStore
import com.github.jvsena42.loopky.data.storage.FilePendingReviewStore
import com.github.jvsena42.loopky.data.storage.FileSignupTokenStore
import com.github.jvsena42.loopky.data.storage.FileStudyProgressStore
import com.github.jvsena42.loopky.data.storage.FileUnsplashKeyStore
import com.github.jvsena42.loopky.data.storage.JsonFileStore
import com.github.jvsena42.loopky.data.storage.LocalKeyStore
import com.github.jvsena42.loopky.data.storage.PendingReviewStore
import com.github.jvsena42.loopky.data.storage.SecureSessionStore
import com.github.jvsena42.loopky.data.storage.SignupTokenStore
import com.github.jvsena42.loopky.data.storage.StudyProgressStore
import com.github.jvsena42.loopky.data.storage.UnsplashKeyStore
import com.github.jvsena42.loopky.data.storage.desktopSecureSessionStore
import com.github.jvsena42.loopky.data.storage.preferencesStore
import com.github.jvsena42.loopky.data.storage.secretsStore
import com.github.jvsena42.loopky.data.unsplash.UnsplashClient
import com.github.jvsena42.loopky.platform.BackgroundTasks
import com.github.jvsena42.loopky.platform.InlineBackgroundTasks
import com.github.jvsena42.loopky.platform.JvmMediaProcessor
import com.github.jvsena42.loopky.platform.MediaProcessor
import com.github.jvsena42.loopky.platform.NoPasswordManagerPresence
import com.github.jvsena42.loopky.platform.NoPubkyRingPresence
import com.github.jvsena42.loopky.platform.NoSpeaker
import com.github.jvsena42.loopky.platform.NoSpeechRecognizer
import com.github.jvsena42.loopky.platform.PassThroughMediaProcessor
import com.github.jvsena42.loopky.platform.PasswordManagerPresence
import com.github.jvsena42.loopky.platform.PubkyRingPresence
import com.github.jvsena42.loopky.platform.Speaker
import com.github.jvsena42.loopky.platform.SpeechRecognizer
import com.github.jvsena42.loopky.platform.requireSupportedDesktopHost
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import java.nio.file.Path

/** Koin qualifiers for the two [JsonFileStore]s — see [preferencesStore] and [secretsStore]. */
private val PREFERENCES = named("preferences")
private val SECRETS = named("secrets")

/**
 * The desktop JVM half of the Koin graph (#54).
 *
 * [pubkyEnvironment] carries the same weight it does on the two apps — which Homegate mints
 * signup tokens, which homeserver they are valid on, and which Nexus indexer is read — with one
 * difference that matters for a binary: **there is no build type to infer it from.** The apps pin
 * it (debug → staging, release → production, #42) and a shipped build cannot be talked out of it;
 * a CLI has neither a build variant nor a Settings screen, so the caller has to say. The CLI reads
 * `LOOPKY_ENV` / `--env` and defaults to production, for the same reason
 * [PubkyEnvironment.fromNameOrProduction] falls back that way.
 *
 * [configHome] is where state lives. Injected rather than resolved here so a test — and a
 * container — can point somewhere disposable; see [ConfigHome] for the default and for why it is
 * a directory of 0600 files rather than an OS keyring. The **session** is the one exception, and
 * only on macOS: [desktopSecureSessionStore] puts it in the Keychain there, unless [configHome]
 * has been pointed somewhere explicit (#213).
 *
 * [mediaProcessor] is the one binding a caller has to think about, and it decides how many files
 * the client is (#210). [JvmMediaProcessor] reaches `javax.imageio` and therefore `java.awt`,
 * which `native-image` cannot fold into an executable on Linux — it ships five JDK `.so`s beside
 * the binary instead, one of them X11. [PassThroughMediaProcessor] is what a headless binary
 * passes, and gives up nothing it uses.
 *
 * **No default, here or in [initKoinJvm].** A default lives in the synthetic Kotlin generates for
 * the function, which any caller using *any* other default reaches — so it would make
 * [JvmMediaProcessor] reachable again whatever the call site passes. Enforcing that in one of the
 * two entry points and arguing for it in the other is how it comes back.
 */
fun jvmPlatformModule(
    pubkyEnvironment: PubkyEnvironment,
    configHome: Path = ConfigHome.resolve(),
    unsplashFallbackKey: String = "",
    mediaProcessor: MediaProcessor,
): Module = module {
    // The host check runs before the FFI is constructed rather than at the first homeserver
    // call: an Intel Mac has no `libpubkycore` row, and JNA's miss reads as a 404 to the shared
    // classifier — a machine that can never talk to a homeserver reporting that the record does
    // not exist (#213). `:cli` refuses earlier still, with an exit code.
    single<PubkyClient> {
        requireSupportedDesktopHost()
        UniffiPubkyClient()
    }
    single<HttpFetcher> { JvmHttpFetcher() }

    single(PREFERENCES) { preferencesStore(configHome) }
    single(SECRETS) { secretsStore(configHome) }

    single<SecureSessionStore> { desktopSecureSessionStore(configHome, get(SECRETS)) }
    single<LocalKeyStore> { FileLocalKeyStore(get(SECRETS)) }
    single<SignupTokenStore> { FileSignupTokenStore(get(SECRETS)) }
    single<UnsplashKeyStore> { FileUnsplashKeyStore(get(SECRETS)) }
    single<AppPreferences> { FileAppPreferences(get(PREFERENCES)) }
    single<PendingReviewStore> { FilePendingReviewStore(get(PREFERENCES)) }
    single<StudyProgressStore> { FileStudyProgressStore(get(PREFERENCES)) }
    single<DeckCacheStore> { FileDeckCacheStore(get(PREFERENCES)) }

    single<MediaProcessor> { mediaProcessor }
    single<BackgroundTasks> { InlineBackgroundTasks() }
    single<Speaker> { NoSpeaker() }
    single<SpeechRecognizer> { NoSpeechRecognizer() }
    single<PubkyRingPresence> { NoPubkyRingPresence() }
    single<PasswordManagerPresence> { NoPasswordManagerPresence() }

    single { pubkyEnvironment }
    single { NexusClient(http = get(), baseUrl = pubkyEnvironment.nexusBaseUrl) }
    single { HomegateClient(http = get(), baseUrl = pubkyEnvironment.homegateBaseUrl) }
    single { UnsplashClient(http = get(), keyStore = get(), fallbackKey = unsplashFallbackKey) }
}

/**
 * Start Koin for a desktop JVM process.
 *
 * `sharedModule` comes along whole, ViewModels included. They are dead weight for a headless
 * client — nothing here collects a `StateFlow` — but they cost nothing unresolved, and taking the
 * module as it is keeps the CLI on the *same* graph the apps run rather than a second one that
 * can drift. Splitting `shared` into `core` + `presentation` is the eventual answer and it is
 * triggered by an out-of-tree consumer, not by this one (#54, open question 5).
 *
 * [mediaProcessor] has **no default**, unlike every other parameter here, and neither does
 * [jvmPlatformModule] — see the note there. `:cli` passes [PassThroughMediaProcessor]; a desktop
 * UI would pass [JvmMediaProcessor].
 */
fun initKoinJvm(
    pubkyEnvironment: PubkyEnvironment,
    mediaProcessor: MediaProcessor,
    configHome: Path = ConfigHome.resolve(),
    unsplashFallbackKey: String = "",
    appDeclaration: KoinAppDeclaration = {},
) {
    startKoin {
        appDeclaration()
        modules(
            sharedModule,
            jvmPlatformModule(pubkyEnvironment, configHome, unsplashFallbackKey, mediaProcessor),
        )
    }
}
