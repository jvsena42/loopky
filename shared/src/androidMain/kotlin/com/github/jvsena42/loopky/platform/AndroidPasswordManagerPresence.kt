package com.github.jvsena42.loopky.platform

/**
 * Android always *can* ask: `CredentialManager` exists from API 21 through the AndroidX library,
 * and `credentials-play-services-auth` supplies a provider below API 34.
 *
 * Whether a provider is actually configured is not knowable without raising the sheet — the API
 * answers that by throwing `NoCreateOptionException` at call time — so this reports the capability
 * and the caller reports the outcome. Guessing here would hide the button on a device where the
 * user has Bitwarden but no Google account.
 */
class AndroidPasswordManagerPresence : PasswordManagerPresence {
    override fun canSave(): Boolean = true
}
