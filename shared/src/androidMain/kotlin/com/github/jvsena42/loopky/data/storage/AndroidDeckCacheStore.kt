package com.github.jvsena42.loopky.data.storage

import android.content.Context
import android.content.SharedPreferences
import com.github.jvsena42.loopky.data.repository.CachedDecks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android [DeckCacheStore] over plain `SharedPreferences`, alongside [AndroidAppPreferences]. */
class AndroidDeckCacheStore(context: Context) : DeckCacheStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun load(ownerPubky: String): CachedDecks? = withContext(Dispatchers.IO) {
        decodeDeckCache(prefs.getString(KEY_DECK_CACHE, null), ownerPubky)
    }

    override suspend fun save(ownerPubky: String, decks: CachedDecks) {
        withContext(Dispatchers.IO) {
            // apply(), like the study counter and unlike the review journal: losing this to a
            // killed process costs one cold start's spinner, not the user's work.
            prefs.edit().putString(KEY_DECK_CACHE, encodeDeckCache(ownerPubky, decks)).apply()
        }
    }
}
