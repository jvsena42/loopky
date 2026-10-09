package com.github.jvsena42.loopky.presentation.restore

import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.HomeserverLookup
import com.github.jvsena42.loopky.domain.model.KeySource
import com.github.jvsena42.loopky.platform.PasswordManagerPresence
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.VALID_TEST_MNEMONIC
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RestorePhraseViewModelTest {

    private val identityRepo = FakeIdentityRepository(session = null)
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(canUsePasswordManager: Boolean = true) = RestorePhraseViewModel(
        identityRepository = identityRepo,
        passwordManager = object : PasswordManagerPresence {
            override fun canSave(): Boolean = canUsePasswordManager
        },
    )

    private fun TestScope.collectEffects(vm: RestorePhraseViewModel): List<RestoreEffect> {
        val effects = mutableListOf<RestoreEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.effects.collect { effects.add(it) }
        }
        return effects
    }

    @Test
    fun aPhraseFromThePasswordManagerSignsInWithoutBeingTyped() = runTest {
        identityRepo.homeserverLookup = HomeserverLookup.Registered("homeserver-pubky")
        val vm = viewModel()
        val effects = collectEffects(vm)

        vm.onUsePasswordManagerClick()
        advanceUntilIdle()
        assertEquals(listOf<RestoreEffect>(RestoreEffect.ReadFromPasswordManager), effects)
        assertFalse(vm.state.value.canSubmit, "nothing to submit while the picker is up")

        vm.onPasswordManagerResult(" $VALID_TEST_MNEMONIC\n")
        advanceUntilIdle()

        val source = assertIs<KeySource.Phrase>(identityRepo.signInWithKeyCalls.single())
        assertEquals(VALID_TEST_MNEMONIC, source.mnemonic)
        assertEquals(RestoreEffect.NavigateHome, effects.last())
    }

    @Test
    fun aSavedPhraseIsCheckedLikeATypedOneAndTheEntryIsNeverTrusted() = runTest {
        // A password manager can hold anything under Loopky's name. What it hands back gets the
        // same derivation and lookup as typed words, and the same honest outcomes.
        identityRepo.derivedPubky = Result.failure(IllegalArgumentException("Invalid mnemonic phrase"))
        val vm = viewModel()

        vm.onUsePasswordManagerClick()
        advanceUntilIdle()
        vm.onPasswordManagerResult("hunter2")
        advanceUntilIdle()

        assertIs<RestoreOutcome.InvalidPhrase>(vm.state.value.outcome)
        assertTrue(identityRepo.signInWithKeyCalls.isEmpty())
    }

    @Test
    fun aCancelledPickerLeavesTheFieldUsableAndSaysNothingCameBack() = runTest {
        val vm = viewModel()

        vm.onUsePasswordManagerClick()
        advanceUntilIdle()
        vm.onPasswordManagerResult(null)
        advanceUntilIdle()

        assertTrue(vm.state.value.passwordManagerEmpty)
        assertFalse(vm.state.value.isReadingPasswordManager)
        assertEquals(0, identityRepo.lookupCount)

        vm.onPhraseChange("abandon")
        assertFalse(vm.state.value.passwordManagerEmpty, "the note is about the picker, not about what is typed next")
    }

    @Test
    fun aPickerAnsweringAfterTheScreenWasLeftSignsNobodyIn() = runTest {
        identityRepo.homeserverLookup = HomeserverLookup.Registered("homeserver-pubky")
        val vm = viewModel()

        vm.onUsePasswordManagerClick()
        advanceUntilIdle()
        vm.onLeave()
        vm.onPasswordManagerResult(VALID_TEST_MNEMONIC)
        advanceUntilIdle()

        assertTrue(identityRepo.signInWithKeyCalls.isEmpty())
        assertEquals("", vm.state.value.phrase)
    }

    @Test
    fun aPlatformWithNoPasswordManagerNeverOffersOrAsks() = runTest {
        val vm = viewModel(canUsePasswordManager = false)
        val effects = collectEffects(vm)

        assertFalse(vm.state.value.canUsePasswordManager)
        vm.onUsePasswordManagerClick()
        advanceUntilIdle()

        assertTrue(effects.isEmpty())
    }

    @Test
    fun aScreenThatRaisesThePickerItselfClaimsTheReadOnceAndGetsNoEffect() = runTest {
        val vm = viewModel()
        val effects = collectEffects(vm)

        assertTrue(vm.beginPasswordManagerRead())
        assertFalse(vm.beginPasswordManagerRead(), "a second picker over the first")
        advanceUntilIdle()

        assertTrue(effects.isEmpty())
        assertTrue(vm.state.value.isReadingPasswordManager)
    }

    @Test
    fun leavingKeepsWhetherThePlatformCanAsk() = runTest {
        val vm = viewModel()
        vm.onLeave()
        assertTrue(vm.state.value.canUsePasswordManager)
    }

    @Test
    fun aChecksumValidTypoIsNeverCalledInvalidAndTheDerivedPubkyIsShown() = runTest {
        // The commonest reason anyone lands on this screen: one wrong word, or two transposed,
        // that still passes the BIP-39 checksum. The phrase is valid — it just is not theirs.
        // Telling them it is invalid sends them hunting for a problem that does not exist, and
        // showing the pubky is the one diagnosis only they can make.
        identityRepo.derivedPubky = Result.success("pkstranger")
        identityRepo.homeserverLookup = HomeserverLookup.NoRecord
        val vm = viewModel()

        vm.onPhraseChange(VALID_TEST_MNEMONIC)
        vm.onSubmit()
        advanceUntilIdle()

        val outcome = assertIs<RestoreOutcome.NoAccount>(vm.state.value.outcome)
        assertEquals("pkstranger", outcome.pubky)
    }

    @Test
    fun aDhtOutageOffersRetryAndNeverSaysTheAccountDoesNotExist() = runTest {
        // pkarr resolves over UDP, which plenty of networks drop while HTTP works fine. A confident
        // "this phrase belongs to no account" here would be a lie we have no basis for.
        identityRepo.homeserverLookup = HomeserverLookup.CouldNotCheck(ErrorReason.HomeserverLookupFailed)
        val vm = viewModel()

        vm.onPhraseChange(VALID_TEST_MNEMONIC)
        vm.onSubmit()
        advanceUntilIdle()

        val outcome = assertIs<RestoreOutcome.CouldNotCheck>(vm.state.value.outcome)
        assertEquals(ErrorReason.HomeserverLookupFailed, outcome.reason)
    }

    @Test
    fun noHomeserverLookupHappensUntilSubmitIsTapped() = runTest {
        // A DHT probe per completed phrase would make this screen an enumeration oracle for
        // "does this pubky exist", besides costing a round trip on every keystroke.
        val vm = viewModel()

        VALID_TEST_MNEMONIC.forEachIndexed { i, _ ->
            vm.onPhraseChange(VALID_TEST_MNEMONIC.take(i + 1))
        }
        advanceUntilIdle()

        assertEquals(0, identityRepo.lookupCount)
    }

    @Test
    fun nothingIsSignedInWhenThePreFlightSaysNoRecord() = runTest {
        // Signup must never happen implicitly from a failed restore: a typo'd phrase would strand
        // the real account behind words the user now believes are broken, and a wallet seed would
        // become a published public identity.
        identityRepo.homeserverLookup = HomeserverLookup.NoRecord
        val vm = viewModel()

        vm.onPhraseChange(VALID_TEST_MNEMONIC)
        vm.onSubmit()
        advanceUntilIdle()

        assertTrue(identityRepo.signInWithKeyCalls.isEmpty(), "no sign-in may be attempted")
    }

    @Test
    fun aRegisteredPubkySignsInAndGoesHome() = runTest {
        identityRepo.homeserverLookup = HomeserverLookup.Registered("homeserver-pubky")
        val vm = viewModel()
        val effects = collectEffects(vm)

        vm.onPhraseChange(VALID_TEST_MNEMONIC)
        vm.onSubmit()
        advanceUntilIdle()

        assertIs<KeySource.Phrase>(identityRepo.signInWithKeyCalls.single())
        assertEquals(listOf(RestoreEffect.NavigateHome), effects)
    }

    @Test
    fun aPhraseThatIsNotBip39IsReportedAsInvalidWithoutAskingTheNetwork() = runTest {
        // The honest failure. It also must not cost a DHT lookup — there is nothing to look up.
        identityRepo.derivedPubky = Result.failure(IllegalArgumentException("Invalid mnemonic phrase"))
        val vm = viewModel()

        vm.onPhraseChange("nonsense words")
        vm.onSubmit()
        advanceUntilIdle()

        assertIs<RestoreOutcome.InvalidPhrase>(vm.state.value.outcome)
        assertEquals(0, identityRepo.lookupCount)
    }

    @Test
    fun theSecretPhraseIsClearedFromStateOnceTheSessionExists() = runTest {
        // A StateFlow outlives the composable reading it, so the words would otherwise sit in
        // memory — and in any heap dump — for the life of the ViewModel.
        identityRepo.homeserverLookup = HomeserverLookup.Registered("homeserver-pubky")
        val vm = viewModel()

        vm.onPhraseChange(VALID_TEST_MNEMONIC)
        vm.onSubmit()
        advanceUntilIdle()

        assertEquals("", vm.state.value.phrase)
    }

    @Test
    fun leavingTheScreenClearsThePhraseEvenWhenNothingWasSubmitted() = runTest {
        val vm = viewModel()
        vm.onPhraseChange(VALID_TEST_MNEMONIC)

        vm.onLeave()

        assertEquals("", vm.state.value.phrase)
    }

    @Test
    fun editingThePhraseClearsTheLastOutcomeSoItIsNotReadAsAVerdictOnTheNewWords() = runTest {
        identityRepo.homeserverLookup = HomeserverLookup.NoRecord
        val vm = viewModel()
        vm.onPhraseChange(VALID_TEST_MNEMONIC)
        vm.onSubmit()
        advanceUntilIdle()

        vm.onPhraseChange("$VALID_TEST_MNEMONIC ")

        assertEquals(null, vm.state.value.outcome)
    }

    @Test
    fun a404AtSignInIsNoAccountAndNeverTheDeckCopy() = runTest {
        // Seen on staging: getHomeserver answered *Registered* — a pkarr record can outlive the
        // account it points at — and the homeserver then 404'd at signin. The generic classifier
        // turns that into ErrorReason.NotFound, whose copy is "This deck no longer exists": deck
        // copy, on a recovery-phrase screen, for someone who has no account.
        identityRepo.homeserverLookup = HomeserverLookup.Registered("homeserver-z32")
        identityRepo.signInWithKeyResult = Result.failure(
            PubkyError("Failed to sign in: Request failed: Server responded with an error: 404 Not Found - Not Found"),
        )
        identityRepo.derivedPubky = Result.success("pkorphan")
        val vm = viewModel()

        vm.onPhraseChange(VALID_TEST_MNEMONIC)
        vm.onSubmit()
        advanceUntilIdle()

        assertEquals("pkorphan", assertIs<RestoreOutcome.NoAccount>(vm.state.value.outcome).pubky)
    }

    @Test
    fun aPubkyWithNoAccountRoutesOnwardRatherThanDeadEnding() = runTest {
        // The key is valid and *can* be registered deliberately, so this is a fork in the flow
        // rather than a terminal error.
        identityRepo.derivedPubky = Result.success("pkorphan")
        identityRepo.homeserverLookup = HomeserverLookup.NoRecord
        val vm = viewModel()
        val effects = collectEffects(vm)

        vm.onPhraseChange(VALID_TEST_MNEMONIC)
        vm.onSubmit()
        advanceUntilIdle()

        assertEquals(listOf(RestoreEffect.NavigateUnregistered("pkorphan")), effects)
    }

    @Test
    fun aRealTransportFailureIsStillReportedAsOneRatherThanAsAMissingAccount() = runTest {
        // The remap must stay narrow: only a not-found means "no account". Everything else keeps
        // its own diagnosis.
        identityRepo.homeserverLookup = HomeserverLookup.Registered("homeserver-z32")
        identityRepo.signInWithKeyResult = Result.failure(PubkyError("HTTP transport error: error sending request"))
        val vm = viewModel()

        vm.onPhraseChange(VALID_TEST_MNEMONIC)
        vm.onSubmit()
        advanceUntilIdle()

        assertIs<RestoreOutcome.SignInFailed>(vm.state.value.outcome)
    }

    @Test
    fun aKeyWithNoAccountIsHeldSoTheNextScreenHasSomethingToRegister() = runTest {
        // The unregistered screen offers "Register this key". Without this the key was never
        // stored, and that button failed on a missing key the user never caused.
        identityRepo.derivedPubky = Result.success("pkorphan")
        identityRepo.homeserverLookup = HomeserverLookup.NoRecord
        val vm = viewModel()

        vm.onPhraseChange(VALID_TEST_MNEMONIC)
        vm.onSubmit()
        advanceUntilIdle()

        assertIs<KeySource.Phrase>(identityRepo.heldForRegistration.single())
    }

    @Test
    fun thePhraseSurvivesTheHopToTheUnregisteredScreen() = runTest {
        // That screen's primary action is "Check my recovery phrase again" — the likeliest fix is
        // one mistyped word. Clearing the field first drops the user on a blank form to retype all
        // twelve, which is the opposite of what that button promises.
        identityRepo.derivedPubky = Result.success("pkorphan")
        identityRepo.homeserverLookup = HomeserverLookup.NoRecord
        val vm = viewModel()
        vm.onPhraseChange(VALID_TEST_MNEMONIC)
        vm.onSubmit()
        advanceUntilIdle()

        vm.onLeaveUnlessCorrecting()

        assertEquals(VALID_TEST_MNEMONIC, vm.state.value.phrase)
    }

    @Test
    fun thePhraseIsStillClearedWhenTheFlowIsLeftForGood() = runTest {
        // The memory-hygiene guarantee has to survive the exception above it.
        val vm = viewModel()
        vm.onPhraseChange(VALID_TEST_MNEMONIC)

        vm.onLeaveUnlessCorrecting()

        assertEquals("", vm.state.value.phrase)
    }
}
