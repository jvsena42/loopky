package com.github.jvsena42.loopky.ui.util

import android.content.Context
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import com.github.jvsena42.loopky.R
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
/**
 * Ask a system sheet, and hand [deliver] an answer even when the wait is cancelled.
 *
 * A rotation restarts the effect that is suspended on the sheet while the ViewModel survives it.
 * Without the [fallback] the ViewModel keeps waiting for an answer that can no longer arrive, and
 * the button that raised the sheet stays disabled until the screen is left.
 */
suspend fun <T> answerEvenIfCancelled(fallback: T, deliver: (T) -> Unit, ask: suspend () -> T) {
    var answered = false
    try {
        val answer = ask()
        answered = true
        deliver(answer)
    } finally {
        if (!answered) deliver(fallback)
    }
}

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
     * Read the credential back for [account], a pubky.
     *
     * This is what turns "a sheet appeared" into "the account is recoverable". Returns null when
     * nothing comes back, which the caller must treat as *not backed up* — the whole reason the
     * save is verified rather than assumed.
     *
     * The app's own name is asked for as well: it was every credential's id before the pubky was,
     * and those entries still hold a good phrase. Neither id is proof of whose phrase came back —
     * a provider may ignore the filter — so the caller compares the words themselves.
     */
    suspend fun readBack(account: String): String? =
        read(allowedIds = setOf(account, context.getString(R.string.app_name)))

    /** Let the user pick any phrase saved from Loopky — the restore path, where no pubky is known yet. */
    suspend fun pick(): String? = read(allowedIds = emptySet())

    private suspend fun read(allowedIds: Set<String>): String? = try {
        val response = credentialManager.getCredential(
            context = context,
            request = GetCredentialRequest(listOf(GetPasswordOption(allowedUserIds = allowedIds))),
        )
        (response.credential as? PasswordCredential)?.password
    } catch (e: GetCredentialException) {
        Log.e(TAG, "read: FAILED — ${e::class.simpleName}")
        null
    }
}
