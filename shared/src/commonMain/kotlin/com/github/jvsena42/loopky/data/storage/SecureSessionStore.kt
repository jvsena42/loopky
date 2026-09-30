package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.domain.model.Capability
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Session
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Platform-keystore-backed store for the signed-in [Session]. Android wraps
 * `EncryptedSharedPreferences` (master key in the Android Keystore); iOS wraps the native
 * Keychain (`kSecClassGenericPassword`). Both go through Liftric KVault.
 */
interface SecureSessionStore {
    /**
     * Where this store keeps the session, in a form a client can print.
     *
     * A client has to be able to *say* where the credential is, rather than describe the rules
     * that decide it: the desktop build picks between the macOS Keychain and a file, and `loopky
     * whoami` reports the answer (#213). Never the value, and never a secret — a path or the name
     * of a keystore.
     */
    val location: String

    suspend fun save(session: Session)
    suspend fun load(): Session?
    suspend fun clear()
}

internal const val SESSION_STORAGE_KEY = "session.v1"
internal const val SESSION_SERVICE_NAME = "loopky.session"

internal val sessionStoreJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
internal data class StoredSession(
    val pubky: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val sessionSecret: String,
    val capabilities: List<String> = emptyList(),
    val homeserver: String,
) {
    fun toDomain(): Session = Session(
        identity = PubkyIdentity(
            pubky = pubky,
            displayName = displayName,
            avatarUrl = avatarUrl,
            bio = bio,
        ),
        sessionSecret = sessionSecret,
        capabilities = capabilities.map(::Capability),
        homeserver = homeserver,
    )

    companion object {
        fun fromDomain(session: Session): StoredSession = StoredSession(
            pubky = session.identity.pubky,
            displayName = session.identity.displayName,
            avatarUrl = session.identity.avatarUrl,
            bio = session.identity.bio,
            sessionSecret = session.sessionSecret,
            capabilities = session.capabilities.map { it.value },
            homeserver = session.homeserver,
        )
    }
}
