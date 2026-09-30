package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.domain.model.Session
import com.liftric.kvault.KVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/**
 * iOS [SecureSessionStore] backed by the native Keychain (`kSecClassGenericPassword`)
 * through Liftric KVault.
 */
class IosSecureSessionStore : SecureSessionStore {
    private val vault: KVault = KVault(serviceName = SESSION_SERVICE_NAME, accessGroup = null)

    override val location: String = "the iOS Keychain (service $SESSION_SERVICE_NAME)"

    override suspend fun save(session: Session) = withContext(Dispatchers.Default) {
        val json = sessionStoreJson.encodeToString(StoredSession.fromDomain(session))
        vault.set(SESSION_STORAGE_KEY, json)
        Unit
    }

    override suspend fun load(): Session? = withContext(Dispatchers.Default) {
        val json = vault.string(SESSION_STORAGE_KEY) ?: return@withContext null
        runCatching {
            sessionStoreJson.decodeFromString<StoredSession>(json).toDomain()
        }.getOrNull()
    }

    override suspend fun clear() = withContext(Dispatchers.Default) {
        vault.deleteObject(SESSION_STORAGE_KEY)
        Unit
    }
}
