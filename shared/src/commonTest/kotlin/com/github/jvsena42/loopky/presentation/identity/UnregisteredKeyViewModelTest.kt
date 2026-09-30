package com.github.jvsena42.loopky.presentation.identity

import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.data.storage.PendingSignup
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakeSignupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UnregisteredKeyViewModelTest {

    private val identityRepo = FakeIdentityRepository(session = null)
    private val mainDispatcher = StandardTestDispatcher()
    private val pubky = "pkorphan"

    @BeforeTest fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun redeemable(homeserver: String = "homeserver-z32") = PendingSignup.Redeemable(
        token = "token-123",
        homeserverPubky = homeserver,
        source = PendingSignup.Source.Invite,
    )

    private fun viewModel(
        signupRepo: FakeSignupRepository,
        custody: KeyCustody = KeyCustody.Loopky(pubky = pubky),
    ) = UnregisteredKeyViewModel(
        pubky = pubky,
        custody = custody,
        signupRepository = signupRepo,
        identityRepository = identityRepo,
    )

    private fun TestScope.collectEffects(vm: UnregisteredKeyViewModel): List<UnregisteredKeyEffect> {
        val effects = mutableListOf<UnregisteredKeyEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.effects.collect { effects.add(it) }
        }
        return effects
    }

    @Test
    fun aStoredTokenIsSpentRatherThanTheUserBeingChargedAgain() = runTest {
        // They may already have paid sats or spent an SMS attempt to get here.
        val signupRepo = FakeSignupRepository(redeemable())
        val vm = viewModel(signupRepo)
        advanceUntilIdle()

        vm.onRegisterConfirmed()
        advanceUntilIdle()

        assertEquals(listOf("homeserver-z32" to "token-123"), identityRepo.registerHeldKeyCalls)
        assertEquals(0, signupRepo.mintCount)
    }

    @Test
    fun registeringNeverMintsAFreshKeyAndAlwaysUsesTheOneInHand() = runTest {
        // The whole point of the screen. Pubky Ring's signup deeplink hardcodes minting, which is
        // how a user's original key ends up account-less forever.
        val vm = viewModel(FakeSignupRepository(redeemable()))
        advanceUntilIdle()

        vm.onRegisterConfirmed()
        advanceUntilIdle()

        assertTrue(identityRepo.createLocalAccountCalls.isEmpty(), "this path must never mint")
        assertEquals(1, identityRepo.registerHeldKeyCalls.size)
    }

    @Test
    fun aRegisteredKeyGoesStraightIntoTheAppRatherThanIntoABackupWall() = runTest {
        // Backing up is offered by the Profile card above sign-out and by Settings, and the
        // sign-out guard refuses to erase an un-backed-up key. A screen between registering and
        // using the app buys none of that.
        val vm = viewModel(FakeSignupRepository(redeemable()))
        val effects = collectEffects(vm)
        advanceUntilIdle()

        vm.onRegisterConfirmed()
        advanceUntilIdle()

        assertEquals(listOf(UnregisteredKeyEffect.NavigateHome), effects)
    }

    @Test
    fun withNoStoredTokenTheUserIsSentToVerifyRatherThanCharged() = runTest {
        val vm = viewModel(FakeSignupRepository(null))
        val effects = collectEffects(vm)
        advanceUntilIdle()

        vm.onRegisterConfirmed()
        advanceUntilIdle()

        assertEquals(listOf(UnregisteredKeyEffect.NavigateSignup), effects)
        assertTrue(identityRepo.registerHeldKeyCalls.isEmpty())
    }

    @Test
    fun theTokenIsClearedOnlyAfterRegistrationSucceeds() = runTest {
        val signupRepo = FakeSignupRepository(redeemable())
        val vm = viewModel(signupRepo)
        advanceUntilIdle()

        vm.onRegisterConfirmed()
        advanceUntilIdle()

        assertEquals(1, signupRepo.clearCount)
    }

    @Test
    fun aFailedRegistrationKeepsTheTokenBecauseItMayNeverHaveBeenSpent() = runTest {
        val signupRepo = FakeSignupRepository(redeemable())
        identityRepo.registerHeldKeyResult = Result.failure(PubkyError("signup failure: 500"))
        val vm = viewModel(signupRepo)
        advanceUntilIdle()

        vm.onRegisterConfirmed()
        advanceUntilIdle()

        assertEquals(0, signupRepo.clearCount)
    }

    @Test
    fun aRingHeldKeyIsNeverRegisteredFromHereBecauseWeDoNotHaveIt() = runTest {
        // `signUp` needs the secret key. A button that "worked" here would be registering a
        // different key than the one the user is stuck with.
        val vm = viewModel(FakeSignupRepository(redeemable()), custody = KeyCustody.External)
        advanceUntilIdle()

        vm.onRegisterConfirmed()
        advanceUntilIdle()

        assertTrue(identityRepo.registerHeldKeyCalls.isEmpty())
        assertEquals(false, vm.state.value.loopkyHoldsKey)
    }

    @Test
    fun thePubkyIsAlwaysOnScreenSoTheUserCanRecogniseItIsNotTheirs() = runTest {
        val vm = viewModel(FakeSignupRepository(null))
        advanceUntilIdle()

        assertEquals(pubky, vm.state.value.pubky)
    }

    @Test
    fun checkingThePhraseAgainGoesBackRatherThanRegisteringAnything() = runTest {
        val vm = viewModel(FakeSignupRepository(redeemable()))
        val effects = collectEffects(vm)
        advanceUntilIdle()

        vm.onCheckPhraseAgainClick()
        advanceUntilIdle()

        assertEquals(listOf(UnregisteredKeyEffect.NavigateBack), effects)
        assertTrue(identityRepo.registerHeldKeyCalls.isEmpty())
    }
}
