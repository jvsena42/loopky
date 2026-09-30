package com.github.jvsena42.loopky.presentation.backup

import com.github.jvsena42.loopky.data.repository.KeyBackupRepository
import com.github.jvsena42.loopky.data.repository.PhraseQuiz
import com.github.jvsena42.loopky.data.repository.RecoveryFileBlob
import com.github.jvsena42.loopky.domain.model.BackupMethod
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.platform.PasswordManagerPresence
import com.github.jvsena42.loopky.testing.FakePubkyRingPresence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BackupStartViewModelTest {

    private val custody = MutableStateFlow<KeyCustody>(KeyCustody.External)
    private val ring = FakePubkyRingPresence()
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val keyBackup = object : KeyBackupRepository {
        override val custody: Flow<KeyCustody> = this@BackupStartViewModelTest.custody
        override suspend fun revealRecoveryPhrase(): Result<String> = Result.failure(NotImplementedError())
        override suspend fun buildPhraseQuiz(): Result<PhraseQuiz> = Result.failure(NotImplementedError())
        override suspend fun createRecoveryFile(passphrase: String): Result<RecoveryFileBlob> =
            Result.failure(NotImplementedError())
        override suspend fun ringExportUrl(): Result<String> = Result.failure(NotImplementedError())
        override suspend fun markBackedUp(method: BackupMethod) = Unit
    }

    private fun viewModel(canSave: Boolean = true) = BackupStartViewModel(
        keyBackup = keyBackup,
        ringPresence = ring,
        passwordManager = object : PasswordManagerPresence {
            override fun canSave(): Boolean = canSave
        },
    )

    @Test
    fun aRestoredKeyArrivesAlreadyBackedUpSoTheScreenCanSaySoRatherThanWarn() = runTest {
        // A restored key really is backed up — the user typed the phrase in to get here. The
        // screen keys its copy off this, because the default "your key lives only on this device"
        // would describe a risk they do not have.
        custody.value = KeyCustody.Loopky(pubky = "pk1", backedUpBy = setOf(BackupMethod.RecoveryPhrase))
        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value.isBackedUp)
        assertTrue(vm.state.value.hasPhrase)
    }

    @Test
    fun aFreshlyMintedKeyIsNotBackedUpAndTheScreenSaysSo() = runTest {
        custody.value = KeyCustody.Loopky(pubky = "pk1")
        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.isBackedUp)
    }

    @Test
    fun aFileRestoredKeyOffersNoPhraseScreenBecauseItHasNoWords() = runTest {
        custody.value = KeyCustody.Loopky(
            pubky = "pk1",
            backedUpBy = setOf(BackupMethod.EncryptedFile),
            hasPhrase = false,
        )
        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.hasPhrase)
    }

    @Test
    fun whetherRingIsInstalledReachesTheScreenThatDecidesWhatToSayAboutIt() = runTest {
        // Plumbed and then read by nobody: the Ring card claimed nothing either way while the
        // screen behind it did its own check, so "not installed" only surfaced after a tap.
        ring.canImport = false
        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.ringInstalled)
    }
}
