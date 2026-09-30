package com.github.jvsena42.loopky.presentation.backup

import com.github.jvsena42.loopky.data.repository.KeyBackupRepository
import com.github.jvsena42.loopky.data.repository.PhraseQuiz
import com.github.jvsena42.loopky.data.repository.RecoveryFileBlob
import com.github.jvsena42.loopky.domain.model.BackupMethod
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.platform.PasswordManagerPresence
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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val PHRASE = "keep amused equip turkey turtle eyebrow alpha comic twin barely chef feature"

@OptIn(ExperimentalCoroutinesApi::class)
class BackupPhraseViewModelTest {

    private val custody = MutableStateFlow<KeyCustody>(KeyCustody.External)
    private val marked = mutableListOf<BackupMethod>()
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val keyBackup = object : KeyBackupRepository {
        override val custody: Flow<KeyCustody> = this@BackupPhraseViewModelTest.custody
        override suspend fun revealRecoveryPhrase(): Result<String> = Result.success(PHRASE)
        override suspend fun buildPhraseQuiz(): Result<PhraseQuiz> = Result.failure(NotImplementedError())
        override suspend fun createRecoveryFile(passphrase: String): Result<RecoveryFileBlob> =
            Result.failure(NotImplementedError())
        override suspend fun ringExportUrl(): Result<String> = Result.failure(NotImplementedError())
        override suspend fun markBackedUp(method: BackupMethod) { marked += method }
    }

    private fun viewModel(canSave: Boolean = true) = BackupPhraseViewModel(
        keyBackup = keyBackup,
        passwordManager = object : PasswordManagerPresence {
            override fun canSave(): Boolean = canSave
        },
    )

    /**
     * The rotation bug: `onLeave` empties a ViewModel that outlives the screen, and loading used
     * to live in `init`, which does not run again for a retained instance.
     */
    @Test
    fun `re-entering after a leave reloads the words`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(12, vm.state.value.words.size)

        vm.onLeave()
        assertTrue(vm.state.value.words.isEmpty())

        vm.onEnter()
        advanceUntilIdle()
        assertEquals(12, vm.state.value.words.size, "a retained ViewModel must refill on re-entry")
    }

    @Test
    fun `a leave keeps whether this platform can save at all`() = runTest {
        val vm = viewModel(canSave = true)
        advanceUntilIdle()
        vm.onLeave()
        assertTrue(vm.state.value.canSaveToPasswordManager, "presence is a platform fact, not screen state")
    }

    @Test
    fun `the save offer waits for the words to be revealed`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        assertFalse(vm.state.value.showPasswordManagerSave, "offered before the user has seen the phrase")

        vm.onRevealClick()
        assertTrue(vm.state.value.showPasswordManagerSave)
    }

    @Test
    fun `a platform that cannot save never offers`() = runTest {
        val vm = viewModel(canSave = false)
        advanceUntilIdle()
        vm.onRevealClick()
        assertFalse(vm.state.value.showPasswordManagerSave)
    }

    /** A sheet that says "saved" while storing nothing retrievable must not retire the nag. */
    @Test
    fun `a read-back that does not match is not a backup`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onRevealClick()
        vm.onSaveToPasswordManagerClick()
        advanceUntilIdle()

        vm.onPasswordManagerReadBack("something else entirely")
        advanceUntilIdle()

        assertFalse(vm.state.value.savedToPasswordManager)
        assertTrue(vm.state.value.passwordManagerFailed)
        assertTrue(marked.isEmpty(), "nothing verified, so nothing recorded")
    }

    @Test
    fun `a read-back that is missing is not a backup`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onRevealClick()
        vm.onPasswordManagerReadBack(null)
        advanceUntilIdle()

        assertFalse(vm.state.value.savedToPasswordManager)
        assertTrue(marked.isEmpty())
    }

    @Test
    fun `a matching read-back records the method`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onRevealClick()
        vm.onSaveToPasswordManagerClick()
        advanceUntilIdle()

        vm.onPasswordManagerReadBack(PHRASE)
        advanceUntilIdle()

        assertTrue(vm.state.value.savedToPasswordManager)
        assertFalse(vm.state.value.passwordManagerFailed)
        assertContains(marked, BackupMethod.PasswordManager)
    }

    @Test
    fun `a cancelled sheet is a failure and not a backup`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onRevealClick()
        vm.onSaveToPasswordManagerClick()
        advanceUntilIdle()

        vm.onPasswordManagerSaveResult(saved = false)
        advanceUntilIdle()

        assertFalse(vm.state.value.savedToPasswordManager)
        assertTrue(vm.state.value.passwordManagerFailed)
        assertTrue(marked.isEmpty())
    }
}
