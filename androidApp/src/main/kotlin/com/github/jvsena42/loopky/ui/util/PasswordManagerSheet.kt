package com.github.jvsena42.loopky.ui.util

import android.content.Context
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import com.github.jvsena42.loopky.util.Log

private const val TAG = "Loopky/PasswordManager"

/**
 * Saving the recovery phrase into the device's credential manager, and reading it back.
 *
 * Lives in the Compose layer rather than behind an `expect`/`actual`, because both calls raise a
 * system sheet and so need an **Activity** context and its lifecycle. The shared ViewModel emits
 * an effect and takes the answer back through a callback — the same split Speak and Listen use.
 *
 * Nothing here logs the phrase. The failure paths log an exception *class*, never a message, since
 * a provider is free to put whatever it likes in the latter.
 */
class PasswordManagerSheet(private val context: Context) {

    private val credentialManager = CredentialManager.create(context)

    /**
     * Raise the "save a password" sheet.
     *
     * Returns false for a cancel and for a device with no provider configured, which are the same
     * thing to the caller: nothing was saved. It is deliberately not an error — declining to use a
     * password manager is a legitimate answer, and the other three backup methods remain.
     */
    suspend fun save(account: String, secret: String): Boolean = try {
        credentialManager.createCredential(
            context = context,
            request = CreatePasswordRequest(id = account, password = secret),
        )
        true
    } catch (e: CreateCredentialException) {
        Log.e(TAG, "save: FAILED — ${e::class.simpleName}")
        false
    }

    /**
     * Read the credential back for [account].
     *
     * This is what turns "a sheet appeared" into "the account is recoverable". Returns null when
     * nothing comes back, which the caller must treat as *not backed up* — the whole reason the
     * save is verified rather than assumed.
     */
    suspend fun read(account: String): String? = try {
        val response = credentialManager.getCredential(
            context = context,
            request = GetCredentialRequest(listOf(GetPasswordOption())),
        )
        (response.credential as? PasswordCredential)
            ?.takeIf { it.id == account }
            ?.password
    } catch (e: GetCredentialException) {
        Log.e(TAG, "read: FAILED — ${e::class.simpleName}")
        null
    }
}
