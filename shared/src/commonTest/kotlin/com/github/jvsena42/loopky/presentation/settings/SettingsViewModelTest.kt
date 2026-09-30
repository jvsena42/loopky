package com.github.jvsena42.loopky.presentation.settings

import com.github.jvsena42.loopky.data.nexus.HttpError
import com.github.jvsena42.loopky.data.unsplash.UnsplashClient
import com.github.jvsena42.loopky.data.unsplash.UnsplashError
import com.github.jvsena42.loopky.domain.model.AppTheme
import com.github.jvsena42.loopky.domain.model.BackupMethod
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeHttpFetcher
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.FakeSettingsRepository
import com.github.jvsena42.loopky.testing.FakeUnsplashKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()
    private val identityRepo = FakeIdentityRepository()
    private val preferences = FakeAppPreferences()
    private val http = FakeHttpFetcher()
    private val keyStore = FakeUnsplashKeyStore()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val settingsRepo = FakeSettingsRepository()

    private fun viewModel(fallbackKey: String = "") = SettingsViewModel(
        identityRepository = identityRepo,
        pubkyClient = FakePubkyClient(),
        appPreferences = preferences,
        unsplashKeyStore = keyStore,
        unsplashClient = UnsplashClient(
            http = http,
            keyStore = keyStore,
            fallbackKey = fallbackKey,
            baseUrl = UNSPLASH_BASE,
        ),
        settingsRepository = settingsRepo,
    )

    @Test
    fun `share on Pubky starts on`() = runTest(mainDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value.shareOnPubky)
    }

    @Test
    fun `toggling the switch persists the choice`() = runTest(mainDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onShareOnPubkyChange(false)
        advanceUntilIdle()

        assertFalse(vm.state.value.shareOnPubky)
        assertFalse(preferences.shareOnPubkyValue)
    }

    @Test
    fun `a stored opt-out survives into a new session`() = runTest(mainDispatcher) {
        preferences.setShareOnPubky(false)

        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.shareOnPubky)
    }

    @Test
    fun `the switch follows a Don't ask again taken elsewhere`() = runTest(mainDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        // What a prompt's "Don't ask again" does — the two controls are one setting.
        preferences.setShareOnPubky(false)
        advanceUntilIdle()

        assertFalse(vm.state.value.shareOnPubky)
    }

    // --- Appearance ---

    @Test
    fun `the theme starts on System`() = runTest(mainDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(AppTheme.System, vm.state.value.themeMode)
    }

    @Test
    fun `picking a theme persists the choice`() = runTest(mainDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onThemeModeChange(AppTheme.Dark)
        advanceUntilIdle()

        assertEquals(AppTheme.Dark, vm.state.value.themeMode)
        assertEquals(AppTheme.Dark, preferences.themeMode.value)
    }

    @Test
    fun `a stored theme survives into a new session`() = runTest(mainDispatcher) {
        preferences.setThemeMode(AppTheme.Light)

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(AppTheme.Light, vm.state.value.themeMode)
    }

    // --- Unsplash access key ---

    @Test
    fun `with neither key the row says web image search is off`() = runTest(mainDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(UnsplashKeyStatus.NotSet, vm.state.value.unsplashKeyStatus)
    }

    @Test
    fun `a build-time key is admitted to exist but never rendered`() = runTest(mainDispatcher) {
        val vm = viewModel(fallbackKey = SHIPPED_KEY)
        advanceUntilIdle()

        assertEquals(UnsplashKeyStatus.UsingBuiltIn, vm.state.value.unsplashKeyStatus)
        // The shared key is the one the user cannot rotate; showing it would leak it to every
        // screenshot of this screen.
        assertFalse(vm.state.value.toString().contains(SHIPPED_KEY))
    }

    @Test
    fun `a verified key is stored and shown as four characters`() = runTest(mainDispatcher) {
        http.respond(VERIFY_URL, "[]")
        val vm = viewModel(fallbackKey = SHIPPED_KEY)
        advanceUntilIdle()

        vm.onSaveUnsplashKey("  user-key-abcd1234  ")
        advanceUntilIdle()

        assertEquals("user-key-abcd1234", keyStore.storedKey)
        assertEquals(UnsplashKeyStatus.UserSet("1234"), vm.state.value.unsplashKeyStatus)
        assertFalse(vm.state.value.toString().contains("user-key-abcd"))
        assertNull(vm.state.value.unsplashKeyError)
    }

    @Test
    fun `a rejected key is reported and never stored`() = runTest(mainDispatcher) {
        http.fail(VERIFY_URL, HttpError(statusCode = 401, message = "nope"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onSaveUnsplashKey("typo")
        advanceUntilIdle()

        assertEquals(UnsplashError.InvalidKey, vm.state.value.unsplashKeyError)
        assertEquals("", keyStore.storedKey)
        assertEquals(UnsplashKeyStatus.NotSet, vm.state.value.unsplashKeyStatus)
    }

    @Test
    fun `an unreachable Unsplash still saves because that is not the key's fault`() =
        runTest(mainDispatcher) {
            http.fail(VERIFY_URL, IllegalStateException("offline"))
            val vm = viewModel()
            advanceUntilIdle()

            vm.onSaveUnsplashKey("probably-fine")
            advanceUntilIdle()

            // Refusing here would make the field unusable on a train.
            assertEquals("probably-fine", keyStore.storedKey)
            assertNull(vm.state.value.unsplashKeyError)
        }

    @Test
    fun `a blank key is not even sent`() = runTest(mainDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onSaveUnsplashKey("   ")
        advanceUntilIdle()

        assertEquals("", keyStore.storedKey)
        assertEquals(emptyList(), http.requestedUrls)
    }

    @Test
    fun `removing the user key falls back to the built-in one`() = runTest(mainDispatcher) {
        http.respond(VERIFY_URL, "[]")
        val vm = viewModel(fallbackKey = SHIPPED_KEY)
        advanceUntilIdle()
        vm.onSaveUnsplashKey("mine-wxyz")
        advanceUntilIdle()

        vm.onRemoveUnsplashKey()
        advanceUntilIdle()

        assertEquals("", keyStore.storedKey)
        assertEquals(UnsplashKeyStatus.UsingBuiltIn, vm.state.value.unsplashKeyStatus)
    }

    private companion object {
        const val UNSPLASH_BASE = "https://unsplash.test"
        const val VERIFY_URL = "$UNSPLASH_BASE/photos/random?count=1"
        const val SHIPPED_KEY = "shipped-secret-key"
    }

    // --- Delete account -----------------------------------------------------------------------

    @Test
    fun theConfirmButtonIsDeadForTenSecondsAndCountsDownToIt() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onDeleteAccountClick()
        val opened = vm.state.value.deletion as DeletionState.Confirming
        assertEquals(SettingsViewModel.COUNTDOWN_SECONDS, opened.secondsRemaining)
        assertFalse(opened.isConfirmable, "the whole point of the delay is that it starts dead")

        advanceTimeBy(9_100)
        assertFalse((vm.state.value.deletion as DeletionState.Confirming).isConfirmable)

        advanceTimeBy(1_000)
        val ready = vm.state.value.deletion as DeletionState.Confirming
        assertEquals(0, ready.secondsRemaining)
        assertTrue(ready.isConfirmable)
    }

    @Test
    fun confirmingBeforeTheCountdownEndsDoesNothing() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onDeleteAccountClick()

        advanceTimeBy(3_000)
        vm.onConfirmDeleteAccount()
        advanceUntilIdle()

        assertEquals(0, identityRepo.deleteAccountCount, "an early tap must not delete an account")
    }

    @Test
    fun dismissingStopsTheCountdownAndClosesTheDialog() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onDeleteAccountClick()

        vm.onDeleteAccountDismissed()
        advanceUntilIdle()

        assertNull(vm.state.value.deletion)
        // A live timer against a closed dialog would re-arm it the moment it reopened.
        advanceTimeBy(11_000)
        assertNull(vm.state.value.deletion)
    }

    @Test
    fun aConfirmedDeleteSweepsAndSignsOut() = runTest {
        identityRepo.deleteAccountProgress = listOf(1 to 3, 2 to 3, 3 to 3)
        val vm = viewModel()
        advanceUntilIdle()
        vm.onDeleteAccountClick()
        advanceTimeBy(10_500)

        val effects = mutableListOf<SettingsEffect>()
        val job = launch { vm.effects.toList(effects) }

        vm.onConfirmDeleteAccount()
        advanceUntilIdle()

        assertEquals(1, identityRepo.deleteAccountCount)
        assertNull(vm.state.value.deletion)
        assertTrue(SettingsEffect.SignedOut in effects)
        job.cancel()
    }

    @Test
    fun aFailedSweepLeavesTheUserSignedInAndImmediatelyRetryable() = runTest {
        // The state that matters: half-deleted and signed out is unrecoverable, because signing
        // back in is what a retry needs.
        identityRepo.deleteAccountResult = Result.failure(IllegalStateException("homeserver down"))
        val vm = viewModel()
        advanceUntilIdle()
        vm.onDeleteAccountClick()
        advanceTimeBy(10_500)

        val effects = mutableListOf<SettingsEffect>()
        val job = launch { vm.effects.toList(effects) }

        vm.onConfirmDeleteAccount()
        advanceUntilIdle()

        val back = vm.state.value.deletion as DeletionState.Confirming
        assertTrue(back.isConfirmable, "a second ten-second wait would only punish a flaky network")
        assertFalse(SettingsEffect.SignedOut in effects, "a failed sweep must not sign anyone out")
        assertTrue(effects.any { it == SettingsEffect.ShowError(SettingsErrorMessage.AccountNotDeleted) })
        job.cancel()
    }

    @Test
    fun progressFromTheSweepReachesTheDialog() = runTest {
        identityRepo.deleteAccountProgress = listOf(1 to 4, 2 to 4)
        // Held open so the in-flight state can be read. Without it the sweep reports and returns
        // inside one dispatch, and StateFlow conflates every intermediate away.
        identityRepo.deleteAccountDelayMs = 5_000
        val vm = viewModel()
        advanceUntilIdle()
        vm.onDeleteAccountClick()
        advanceTimeBy(10_500)

        vm.onConfirmDeleteAccount()
        advanceTimeBy(1)

        val inFlight = vm.state.value.deletion as DeletionState.Deleting
        assertEquals(2, inFlight.done)
        assertEquals(4, inFlight.total)

        advanceUntilIdle()
        assertNull(vm.state.value.deletion, "the dialog closes once the sweep finishes")
    }

    @Test
    fun `the backup door opens only for the signed-in account's own key`() = runTest(mainDispatcher) {
        // An abandoned local signup leaves a minted key in the vault, and a later Pubky Ring
        // sign-in never clears it. Offering "Back up account" then walks a Ring user into the
        // backup flow for an identity that is not theirs.
        identityRepo.custodyFlow.value = KeyCustody.Loopky(pubky = "someone-elses-abandoned-signup")
        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.holdsOwnKey)
    }

    @Test
    fun `the backup door stays open for a key this account owns`() = runTest(mainDispatcher) {
        // Open regardless of whether it is already backed up: methods accumulate, and a restored
        // account — marked backed-up the moment it signs in — would otherwise have no route at all.
        val pubky = identityRepo.session!!.identity.pubky
        identityRepo.custodyFlow.value = KeyCustody.Loopky(
            pubky = pubky,
            backedUpBy = setOf(BackupMethod.RecoveryPhrase),
        )
        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value.holdsOwnKey)
    }

    @Test
    fun `a Ring-held key offers no backup door`() = runTest(mainDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.holdsOwnKey, "there is nothing on this device to lose")
    }
}
