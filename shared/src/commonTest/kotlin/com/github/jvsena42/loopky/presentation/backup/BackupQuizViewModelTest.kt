package com.github.jvsena42.loopky.presentation.backup

import com.github.jvsena42.loopky.data.repository.KeyBackupRepository
import com.github.jvsena42.loopky.data.repository.PhraseQuiz
import com.github.jvsena42.loopky.data.repository.RecoveryFileBlob
import com.github.jvsena42.loopky.domain.model.BackupMethod
import com.github.jvsena42.loopky.domain.model.KeyCustody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val PUBKY = "pk1owner"
private const val PHRASE = "keep amused equip turkey turtle eyebrow alpha comic twin barely chef feature"

@OptIn(ExperimentalCoroutinesApi::class)
class BackupQuizViewModelTest {

    private val custody = MutableStateFlow<KeyCustody>(
        KeyCustody.Loopky(pubky = PUBKY, backedUpBy = setOf(BackupMethod.PasswordManager)),
    )
    private val marked = mutableListOf<BackupMethod>()
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val keyBackup = object : KeyBackupRepository {
        override val custody: Flow<KeyCustody> = this@BackupQuizViewModelTest.custody
        override suspend fun revealRecoveryPhrase(): Result<String> = Result.success(PHRASE)
        override suspend fun buildPhraseQuiz(): Result<PhraseQuiz> = Result.failure(NotImplementedError())
        override suspend fun createRecoveryFile(passphrase: String): Result<RecoveryFileBlob> =
            Result.failure(NotImplementedError())
        override suspend fun ringExportUrl(): Result<String> = Result.failure(NotImplementedError())
        override suspend fun markBackedUp(method: BackupMethod) { marked += method }
    }

    private fun TestScope.collectEffects(vm: BackupQuizViewModel): List<BackupEffect> {
        val effects = mutableListOf<BackupEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.effects.collect { effects.add(it) }
        }
        return effects
    }

    @Test
    fun `the saved-phrase check asks for this account's entry`() = runTest {
        val vm = BackupQuizViewModel(keyBackup)
        val effects = collectEffects(vm)
        advanceUntilIdle()
        assertEquals(ConfirmMode.PasswordManager, vm.state.value.mode)

        vm.onCheckSavedClick()
        advanceUntilIdle()

        assertEquals(listOf<BackupEffect>(BackupEffect.ReadBackFromPasswordManager(account = PUBKY)), effects)
    }

    /** Another account's entry, picked from the same sheet, holds a real phrase that is not this one. */
    @Test
    fun `another account's phrase does not confirm this one`() = runTest {
        val vm = BackupQuizViewModel(keyBackup)
        advanceUntilIdle()
        vm.onCheckSavedClick()
        advanceUntilIdle()

        vm.onPasswordManagerReadBack("rigid install section husband immense bench fabric ignore rich letter ladder menu")
        advanceUntilIdle()

        assertTrue(vm.state.value.wrong)
        assertFalse(vm.state.value.isDone)
        assertTrue(marked.isEmpty())
    }

    @Test
    fun `the matching phrase confirms the backup`() = runTest {
        val vm = BackupQuizViewModel(keyBackup)
        val effects = collectEffects(vm)
        advanceUntilIdle()
        vm.onCheckSavedClick()
        advanceUntilIdle()

        vm.onPasswordManagerReadBack(PHRASE)
        advanceUntilIdle()

        assertContains(marked, BackupMethod.PasswordManager)
        assertEquals(BackupEffect.Done, effects.last())
    }
}
