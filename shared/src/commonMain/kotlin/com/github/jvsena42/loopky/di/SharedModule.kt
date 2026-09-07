package com.github.jvsena42.loopky.di

import com.github.jvsena42.loopky.data.price.PriceClient
import com.github.jvsena42.loopky.data.price.PriceSource
import com.github.jvsena42.loopky.data.pubky.MutableSessionProvider
import com.github.jvsena42.loopky.data.pubky.SessionProvider
import com.github.jvsena42.loopky.data.pubky.SessionRevalidator
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.data.repository.KeyBackupRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.data.repository.SettingsRepository
import com.github.jvsena42.loopky.data.repository.SignupRepository
import com.github.jvsena42.loopky.data.repository.SrsRepository
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.data.repository.impl.AccountEraser
import com.github.jvsena42.loopky.data.repository.impl.CardRepositoryImpl
import com.github.jvsena42.loopky.data.repository.impl.DeckRepositoryImpl
import com.github.jvsena42.loopky.data.repository.impl.DiscoveryRepositoryImpl
import com.github.jvsena42.loopky.data.repository.impl.IdentityRepositoryImpl
import com.github.jvsena42.loopky.data.repository.impl.ImportRepositoryImpl
import com.github.jvsena42.loopky.data.repository.impl.KeyBackupRepositoryImpl
import com.github.jvsena42.loopky.data.repository.impl.MediaRepositoryImpl
import com.github.jvsena42.loopky.data.repository.impl.SessionRevalidatorImpl
import com.github.jvsena42.loopky.data.repository.impl.SettingsRepositoryImpl
import com.github.jvsena42.loopky.data.repository.impl.SignupRepositoryImpl
import com.github.jvsena42.loopky.data.repository.impl.SrsRepositoryImpl
import com.github.jvsena42.loopky.data.repository.impl.TagRepositoryImpl
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.presentation.backup.BackupFileViewModel
import com.github.jvsena42.loopky.presentation.backup.BackupPhraseViewModel
import com.github.jvsena42.loopky.presentation.backup.BackupQuizViewModel
import com.github.jvsena42.loopky.presentation.backup.BackupRingViewModel
import com.github.jvsena42.loopky.presentation.backup.BackupStartViewModel
import com.github.jvsena42.loopky.presentation.decks.DeckDetailViewModel
import com.github.jvsena42.loopky.presentation.decks.DeckEditorViewModel
import com.github.jvsena42.loopky.presentation.decks.DecksLibraryViewModel
import com.github.jvsena42.loopky.presentation.decks.EditCardViewModel
import com.github.jvsena42.loopky.presentation.discover.DiscoverViewModel
import com.github.jvsena42.loopky.presentation.discover.SearchViewModel
import com.github.jvsena42.loopky.presentation.discover.TagBrowseViewModel
import com.github.jvsena42.loopky.presentation.home.HomeViewModel
import com.github.jvsena42.loopky.presentation.identity.UnregisteredKeyViewModel
import com.github.jvsena42.loopky.presentation.importflow.BulkImportViewModel
import com.github.jvsena42.loopky.presentation.importflow.PasteImportViewModel
import com.github.jvsena42.loopky.presentation.importflow.PublishDeckViewModel
import com.github.jvsena42.loopky.presentation.importflow.TriageViewModel
import com.github.jvsena42.loopky.presentation.media.ImageSheetViewModel
import com.github.jvsena42.loopky.presentation.onboarding.OnboardingViewModel
import com.github.jvsena42.loopky.presentation.profile.FollowListViewModel
import com.github.jvsena42.loopky.presentation.profile.FriendProfileViewModel
import com.github.jvsena42.loopky.presentation.profile.ProfileViewModel
import com.github.jvsena42.loopky.presentation.restore.RestoreFileViewModel
import com.github.jvsena42.loopky.presentation.restore.RestorePhraseViewModel
import com.github.jvsena42.loopky.presentation.settings.SettingsViewModel
import com.github.jvsena42.loopky.presentation.signup.InviteCodeViewModel
import com.github.jvsena42.loopky.presentation.signup.LightningVerificationViewModel
import com.github.jvsena42.loopky.presentation.signup.LocalSignupViewModel
import com.github.jvsena42.loopky.presentation.signup.PhoneVerificationViewModel
import com.github.jvsena42.loopky.presentation.signup.SignupStartViewModel
import com.github.jvsena42.loopky.presentation.study.StudySessionViewModel
import com.github.jvsena42.loopky.util.epochMillis
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Shared commonMain Koin module. Platform modules must additionally provide bindings for
 * [com.github.jvsena42.loopky.data.pubky.PubkyClient],
 * [com.github.jvsena42.loopky.data.storage.SecureSessionStore] and
 * [com.github.jvsena42.loopky.data.nexus.NexusClient] — the last because its base URL is
 * build-type dependent (staging for debug, production for release; #42).
 */
val sharedModule = module {
    single { MutableSessionProvider() }
    single<SessionProvider> { get<MutableSessionProvider>() }

    single {
        AccountEraser(
            pubky = get(),
            session = get(),
            revalidator = get(),
            decks = get(),
            tags = get(),
            pendingReviews = get(),
            studyProgress = get(),
            preferences = get(),
            unsplashKeyStore = get(),
        )
    }

    single<IdentityRepository> {
        IdentityRepositoryImpl(
            pubky = get(),
            sessionStore = get(),
            sessionProvider = get(),
            tagRepository = get(),
            eraser = get(),
            localKeyStore = get(),
        )
    }
    single<KeyBackupRepository> { KeyBackupRepositoryImpl(pubky = get(), keyStore = get()) }
    single<PriceSource> { PriceClient(http = get(), nowMillis = ::epochMillis) }

    single<SessionRevalidator> { SessionRevalidatorImpl(get(), get(), get()) }

    single<CardRepository> { CardRepositoryImpl(get(), get(), get()) }
    single<DeckRepository> { DeckRepositoryImpl(get(), get(), get(), get(), get(), get(), get(), get()) }
    single<MediaRepository> { MediaRepositoryImpl(get(), get(), get()) }
    single<ImportRepository> { ImportRepositoryImpl() }
    single<SignupRepository> {
        SignupRepositoryImpl(
            homegate = get(),
            tokenStore = get(),
            environment = get(),
            nowMillis = ::epochMillis,
        )
    }
    single<SettingsRepository> {
        SettingsRepositoryImpl(
            pubky = get(),
            session = get(),
            revalidator = get(),
            preferences = get(),
        )
    }
    single<SrsRepository> {
        SrsRepositoryImpl(
            pubky = get(),
            session = get(),
            revalidator = get(),
            deckRepository = get(),
            cardRepository = get(),
            pendingReviews = get(),
            settingsRepository = get(),
            studyProgress = get(),
        )
    }
    single<DiscoveryRepository> {
        DiscoveryRepositoryImpl(get(), get(), get(), get(), get(), get(), get(), get())
    }

    single<TagRepository> {
        TagRepositoryImpl(pubky = get(), session = get(), revalidator = get(), nexus = get())
    }

    viewModel { OnboardingViewModel(identityRepository = get(), ringPresence = get()) }
    viewModel { RestorePhraseViewModel(identityRepository = get()) }
    viewModel { RestoreFileViewModel(identityRepository = get()) }
    viewModel { params ->
        UnregisteredKeyViewModel(
            pubky = params.get(),
            custody = params.get(),
            signupRepository = get(),
            identityRepository = get(),
        )
    }
    viewModel { params ->
        LocalSignupViewModel(
            signupRepository = get(),
            identityRepository = get(),
            registerHeldKey = params.getOrNull() ?: false,
        )
    }
    viewModel { BackupStartViewModel(keyBackup = get(), ringPresence = get(), passwordManager = get()) }
    viewModel { BackupPhraseViewModel(keyBackup = get(), passwordManager = get()) }
    viewModel { BackupQuizViewModel(keyBackup = get()) }
    viewModel { BackupFileViewModel(keyBackup = get()) }
    viewModel { BackupRingViewModel(keyBackup = get(), ringPresence = get()) }
    viewModel { SignupStartViewModel(signupRepository = get(), priceSource = get()) }
    viewModel { InviteCodeViewModel(signupRepository = get()) }
    viewModel { PhoneVerificationViewModel(signupRepository = get()) }
    viewModel { LightningVerificationViewModel(signupRepository = get(), priceSource = get()) }
    viewModel {
        HomeViewModel(
            identityRepository = get(),
            deckRepository = get(),
            srsRepository = get(),
            settingsRepository = get(),
        )
    }
    viewModel { DecksLibraryViewModel(deckRepository = get(), identityRepository = get()) }
    viewModel { params ->
        DeckDetailViewModel(
            deckId = params.get(0),
            authorPubky = params.values.getOrNull(1) as? String,
            deckRepository = get(),
            cardRepository = get(),
            identityRepository = get(),
            srsRepository = get(),
            mediaRepository = get(),
            tagRepository = get(),
            discoveryRepository = get(),
            appPreferences = get(),
        )
    }
    viewModel { params ->
        StudySessionViewModel(
            deckId = params.values.getOrNull(0) as? String,
            srsRepository = get(),
            deckRepository = get(),
            cardRepository = get(),
            settingsRepository = get(),
            identityRepository = get(),
            // A preview samples a deck nobody has kept — no grading, no session. See the VM.
            isPreview = params.values.getOrNull(1) as? Boolean == true,
            previewAuthorPubky = params.values.getOrNull(2) as? String,
        )
    }
    viewModel { params ->
        DeckEditorViewModel(
            deckId = params.getOrNull(),
            deckRepository = get(),
            cardRepository = get(),
            identityRepository = get(),
            mediaRepository = get(),
            discoveryRepository = get(),
            appPreferences = get(),
        )
    }
    viewModel { params ->
        EditCardViewModel(
            deckId = params.get(0),
            // Blank creates a card rather than editing one — see EditCardViewModel.
            providedCardId = params.get(1),
            cardRepository = get(),
            deckRepository = get(),
            mediaRepository = get(),
        )
    }
    viewModel { PasteImportViewModel(importRepository = get()) }
    viewModel { BulkImportViewModel(importRepository = get(), mediaProcessor = get()) }
    viewModel { TriageViewModel(importRepository = get()) }
    viewModel { ImageSheetViewModel(unsplashClient = get()) }
    viewModel {
        PublishDeckViewModel(
            importRepository = get(),
            deckRepository = get(),
            identityRepository = get(),
            mediaRepository = get(),
            discoveryRepository = get(),
            appPreferences = get(),
        )
    }
    viewModel {
        ProfileViewModel(
            identityRepository = get(),
            deckRepository = get(),
            srsRepository = get(),
            discoveryRepository = get(),
            appPreferences = get(),
            pubkyEnvironment = get(),
        )
    }
    viewModel { params ->
        SettingsViewModel(
            identityRepository = get(),
            pubkyClient = get(),
            appPreferences = get(),
            unsplashKeyStore = get(),
            unsplashClient = get(),
            settingsRepository = get(),
            appVersion = params.getOrNull() ?: "",
        )
    }
    viewModel {
        DiscoverViewModel(discoveryRepository = get(), tagRepository = get(), identityRepository = get())
    }
    viewModel {
        SearchViewModel(discoveryRepository = get(), identityRepository = get())
    }
    viewModel { params ->
        TagBrowseViewModel(
            tag = Tag(params.get<String>()),
            discoveryRepository = get(),
            identityRepository = get(),
        )
    }
    viewModel { params ->
        FriendProfileViewModel(
            targetPubky = params.get(),
            identityRepository = get(),
            discoveryRepository = get(),
            deckRepository = get(),
            pubkyEnvironment = get(),
        )
    }
    viewModel { params ->
        FollowListViewModel(
            targetPubky = params.get(),
            source = params.get(),
            discoveryRepository = get(),
            identityRepository = get(),
        )
    }
}
