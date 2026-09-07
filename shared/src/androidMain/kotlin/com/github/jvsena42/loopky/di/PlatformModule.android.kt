package com.github.jvsena42.loopky.di

import com.github.jvsena42.loopky.data.homegate.HomegateClient
import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.data.nexus.HttpFetcher
import com.github.jvsena42.loopky.data.nexus.JvmHttpFetcher
import com.github.jvsena42.loopky.data.nexus.NexusClient
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.UniffiPubkyClient
import com.github.jvsena42.loopky.data.storage.AndroidAppPreferences
import com.github.jvsena42.loopky.data.storage.AndroidDeckCacheStore
import com.github.jvsena42.loopky.data.storage.AndroidLocalKeyStore
import com.github.jvsena42.loopky.data.storage.AndroidPendingReviewStore
import com.github.jvsena42.loopky.data.storage.AndroidSecureSessionStore
import com.github.jvsena42.loopky.data.storage.AndroidSignupTokenStore
import com.github.jvsena42.loopky.data.storage.AndroidStudyProgressStore
import com.github.jvsena42.loopky.data.storage.AndroidUnsplashKeyStore
import com.github.jvsena42.loopky.data.storage.AppPreferences
import com.github.jvsena42.loopky.data.storage.DeckCacheStore
import com.github.jvsena42.loopky.data.storage.LocalKeyStore
import com.github.jvsena42.loopky.data.storage.PendingReviewStore
import com.github.jvsena42.loopky.data.storage.SecureSessionStore
import com.github.jvsena42.loopky.data.storage.SignupTokenStore
import com.github.jvsena42.loopky.data.storage.StudyProgressStore
import com.github.jvsena42.loopky.data.storage.UnsplashKeyStore
import com.github.jvsena42.loopky.data.unsplash.UnsplashClient
import com.github.jvsena42.loopky.platform.AndroidBackgroundTasks
import com.github.jvsena42.loopky.platform.AndroidMediaProcessor
import com.github.jvsena42.loopky.platform.AndroidPasswordManagerPresence
import com.github.jvsena42.loopky.platform.AndroidPubkyRingPresence
import com.github.jvsena42.loopky.platform.AndroidSpeaker
import com.github.jvsena42.loopky.platform.AndroidSpeechRecognizer
import com.github.jvsena42.loopky.platform.BackgroundTasks
import com.github.jvsena42.loopky.platform.MediaProcessor
import com.github.jvsena42.loopky.platform.PasswordManagerPresence
import com.github.jvsena42.loopky.platform.PubkyRingPresence
import com.github.jvsena42.loopky.platform.Speaker
import com.github.jvsena42.loopky.platform.SpeechRecognizer
import com.github.jvsena42.loopky.presentation.onboarding.OnboardingViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

/** Direct Play Store listing — opens the Play app on-device rather than a web redirect. */
private const val PUBKY_RING_PLAY_STORE_URL =
    "https://play.google.com/store/apps/details?id=to.pubky.ring"

fun androidPlatformModule(
    unsplashFallbackKey: String,
    pubkyEnvironment: PubkyEnvironment,
    localNexusBaseUrl: String = "",
): Module = module {
    single<PubkyClient> { UniffiPubkyClient() }
    single<HttpFetcher> { JvmHttpFetcher() }
    single<SecureSessionStore> { AndroidSecureSessionStore(androidContext()) }
    single<AppPreferences> { AndroidAppPreferences(androidContext()) }
    single<PendingReviewStore> { AndroidPendingReviewStore(androidContext()) }
    single<StudyProgressStore> { AndroidStudyProgressStore(androidContext()) }
    single<DeckCacheStore> { AndroidDeckCacheStore(androidContext()) }
    single<UnsplashKeyStore> { AndroidUnsplashKeyStore(androidContext()) }
    single<SignupTokenStore> { AndroidSignupTokenStore(androidContext()) }
    single<LocalKeyStore> { AndroidLocalKeyStore(androidContext()) }
    single<Speaker> { AndroidSpeaker(androidContext()) }
    single<MediaProcessor> { AndroidMediaProcessor() }
    single<SpeechRecognizer> { AndroidSpeechRecognizer(androidContext()) }
    single<BackgroundTasks> { AndroidBackgroundTasks(androidContext()) }
    single<PasswordManagerPresence> { AndroidPasswordManagerPresence() }

    single<PubkyRingPresence> {
        AndroidPubkyRingPresence(androidContext(), installUrl = PUBKY_RING_PLAY_STORE_URL)
    }
    single {
        NexusClient(http = get(), baseUrl = localNexusBaseUrl.ifBlank { pubkyEnvironment.nexusBaseUrl })
    }
    single { pubkyEnvironment }
    single { HomegateClient(http = get(), baseUrl = pubkyEnvironment.homegateBaseUrl) }
    single { UnsplashClient(http = get(), keyStore = get(), fallbackKey = unsplashFallbackKey) }
    factory {
        OnboardingViewModel(
            identityRepository = get(),
            ringPresence = get(),
            pubkyRingInstallUrl = PUBKY_RING_PLAY_STORE_URL,
        )
    }
}

/**
 * [unsplashFallbackKey] is the build-time Unsplash key. It is only a fallback: a key the user
 * saves in Settings takes precedence, and this one is never shown to them.
 *
 * [pubkyEnvironment] has no default on purpose, and for a sharp reason: it decides which Homegate
 * mints signup tokens and which homeserver those tokens are valid on. A token is single-use, so one
 * minted against the wrong environment is rejected and gone. It carries the Nexus indexer too, so
 * there is no second wire the indexer can drift on (#205). Resolved once here rather than read
 * live, because [HomegateClient] and [NexusClient] are singletons that capture their base URL when
 * constructed — a debug build's Settings override therefore applies on the next launch, which that
 * screen states.
 *
 * [localNexusBaseUrl] is the **one** sanctioned way to point the indexer somewhere the environment
 * did not choose: a Nexus running on your own machine (#58). It is blank on a release build, which
 * `composeApp/build.gradle.kts` pins and `LoopkyApp` guards again — a shipped build reads the
 * network its users publish to (#42). Anything else that wants a different indexer wants a
 * different [pubkyEnvironment].
 */
fun initKoinAndroid(
    unsplashFallbackKey: String = "",
    pubkyEnvironment: PubkyEnvironment,
    localNexusBaseUrl: String = "",
    appDeclaration: KoinAppDeclaration = {},
) {
    startKoin {
        appDeclaration()
        modules(sharedModule, androidPlatformModule(unsplashFallbackKey, pubkyEnvironment, localNexusBaseUrl))
    }
}
