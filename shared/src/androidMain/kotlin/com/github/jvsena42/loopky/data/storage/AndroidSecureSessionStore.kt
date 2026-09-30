package com.github.jvsena42.loopky.data.storage

import android.content.Context
import com.github.jvsena42.loopky.domain.model.Session
import com.liftric.kvault.KVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/**
 * Android [SecureSessionStore] backed by `EncryptedSharedPreferences` through Liftric KVault.
 * The master encryption key is held in the Android Keystore.
 */
class AndroidSecureSessionStore(context: Context) : SecureSessionStore {
    // Null only when the keystore is unusable even after a reset — see openVaultOrNull. Nothing
    // here throws on that; a signed-out app is recoverable, an app that cannot construct its
    // repositories is not.
    private val vault: KVault? = openVaultOrNull(context, SESSION_SERVICE_NAME)

    override val location: String = "the Android keystore (EncryptedSharedPreferences $SESSION_SERVICE_NAME)"

    override suspend fun save(session: Session) = withContext(Dispatchers.IO) {
        val json = sessionStoreJson.encodeToString(StoredSession.fromDomain(session))
        vault?.set(SESSION_STORAGE_KEY, json)
        Unit
    }

    override suspend fun load(): Session? = withContext(Dispatchers.IO) {
        val json = vault.stringOrNull(SESSION_STORAGE_KEY) ?: return@withContext null
        runCatching {
            sessionStoreJson.decodeFromString<StoredSession>(json).toDomain()
        }.getOrNull()
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        vault?.deleteObject(SESSION_STORAGE_KEY)
        Unit
    }
}
