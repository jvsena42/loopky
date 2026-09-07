package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.data.repository.CachedDecks
import platform.Foundation.NSUserDefaults

/** iOS [DeckCacheStore] over `NSUserDefaults`, alongside [IosAppPreferences]. */
class IosDeckCacheStore : DeckCacheStore {
    private val defaults = NSUserDefaults(suiteName = PREFERENCES_NAME)

    override suspend fun load(ownerPubky: String): CachedDecks? =
        decodeDeckCache(defaults.stringForKey(KEY_DECK_CACHE), ownerPubky)

    override suspend fun save(ownerPubky: String, decks: CachedDecks) {
        defaults.setObject(encodeDeckCache(ownerPubky, decks), KEY_DECK_CACHE)
    }

    override suspend fun clear() {
        defaults.removeObjectForKey(KEY_DECK_CACHE)
    }
}
