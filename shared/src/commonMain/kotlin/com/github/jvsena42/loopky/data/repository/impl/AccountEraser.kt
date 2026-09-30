package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.PostDto
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.SessionProvider
import com.github.jvsena42.loopky.data.pubky.SessionRevalidator
import com.github.jvsena42.loopky.data.pubky.deleteWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.isNotFound
import com.github.jvsena42.loopky.data.pubky.mapConcurrently
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.data.storage.AppPreferences
import com.github.jvsena42.loopky.data.storage.DeckCacheStore
import com.github.jvsena42.loopky.data.storage.PendingReviewStore
import com.github.jvsena42.loopky.data.storage.StudyProgressStore
import com.github.jvsena42.loopky.data.storage.UnsplashKeyStore
import com.github.jvsena42.loopky.domain.model.DailyStudyProgress
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Removes every record Loopky wrote for the signed-in account, and the local state that outlives a
 * sign-out.
 *
 * Split from [IdentityRepositoryImpl] the way [DeckCompactor] and [DeckMediaSweeper] are split from
 * `DeckRepositoryImpl`: a multi-step pass over the homeserver rather than another identity
 * operation, needing half a dozen collaborators nothing else in that class touches.
 *
 * **It does not delete the Pubky account**, and no copy anywhere may say it does — see
 * [IdentityRepository.deleteAccount] for what is in scope, and why the signup token survives.
 */
// LongParameterList: the collaborator count is the reason this class exists rather than living on
// IdentityRepositoryImpl. Taking an account apart genuinely touches decks, tags, the homeserver and
// four local stores; bundling them behind a holder would hide the blast radius rather than shrink it.
@Suppress("LongParameterList")
class AccountEraser(
    private val pubky: PubkyClient,
    private val session: SessionProvider,
    private val revalidator: SessionRevalidator,
    private val decks: DeckRepository,
    private val tags: TagRepository,
    private val pendingReviews: PendingReviewStore,
    private val studyProgress: StudyProgressStore,
    private val preferences: AppPreferences,
    private val unsplashKeyStore: UnsplashKeyStore,
    private val deckCache: DeckCacheStore,
) {

    /**
     * Sweep the homeserver for [owner]. Throws if anything Loopky owns could not be removed, which
     * is what keeps the caller from signing the user out of a half-deleted account.
     */
    suspend fun erase(owner: String, onProgress: (Int, Int) -> Unit) {
        val owned = decks.listOwned()
        // Taken before the decks go, so `total` covers the whole job. Best-effort: a listing we
        // could not read still leaves the per-deck pass to do the bulk of the work, and step 2
        // re-lists at the end anyway.
        val strays = namespaceRecords(owner)
        val progress = SweepProgress((owned.size + strays.size).coerceAtLeast(1), onProgress)

        // Counted rather than thrown on. One record that will not go must not abandon the rest —
        // that leaves the account in a worse state than before — but it must still be the
        // difference between "deleted" and "tried to delete", which is what the check below is.
        var failures = 0

        // 1. Decks, through the repository that knows how to take one apart: it holds the per-deck
        // write lock, deletes the manifest last, and clears the deck's tag records.
        for (deck in owned) {
            decks.delete(deck.id).onFailure {
                Log.e(TAG, "deck ${deck.id} FAILED — ${it.message}", it)
                failures++
            }
            progress.step()
        }

        // 2. Review state, settings, subscriptions, tag records — and whatever step 1 could not see.
        // Re-listed rather than reusing `strays`, which is stale by now. Counted from the returned
        // list rather than a shared variable: these run concurrently, and `failures++` from two
        // coroutines would under-report and sign the user out of an account that is still half there.
        failures += namespaceRecords(owner)
            .mapConcurrently { path -> deleteRecord(path).also { progress.step() } }
            .count { deleted -> !deleted }

        // 3. The directory entry. Nothing else takes the account out of Discover and search, so a
        // failure here counts: leaving it behind means a deleted account still shows up.
        tags.removeReservedTag(PubkyUri(PubkyPaths.profile(owner)), ReservedTags.USER)
            .onFailure {
                Log.e(TAG, "${ReservedTags.USER.value} removal FAILED — ${it.message}", it)
                failures++
            }

        // 4. Announcement posts, which would otherwise link to decks that no longer resolve.
        // Uncounted on purpose: a stale post in a feed is untidy rather than a failed deletion,
        // and a user with a busy feed should not be blocked by one unreadable record.
        deleteAnnouncementPosts(owner)

        // Re-list rather than trust the counters. `deleteRecord` scores a 404 as success, which is
        // right for a record a previous sweep already removed and wrong as a completeness proof —
        // it is exactly how a sweep that never saw a record could report having deleted it. The
        // only honest check is asking the homeserver what is left.
        val leftovers = namespaceRecords(owner)
        check(failures == 0 && leftovers.isEmpty()) {
            "$failures record(s) could not be deleted, ${leftovers.size} still present"
        }

        wipeLocalState(owner)
    }

    /**
     * Every record Loopky owns for [owner], best-effort.
     *
     * **Each sub-root is listed in its own right, not just the namespace root.** One listing of
     * `pub/loopky/` silently missed records: a followed deck's subscription survived two full sweeps
     * that both reported success, because `subscriptions/{author}/{deckId}.json` never appeared in
     * the namespace-root listing while `loadSubscriptions`, which lists `subscriptions/` directly,
     * found it every time. The root listing stays as the catch-all for anything a future version
     * writes that this list does not name.
     *
     * Directory entries are dropped: a homeserver answering with `…/srs/` rather than the records
     * beneath it hands back something no delete can remove, and a 404 on that would score as a
     * successful deletion.
     */
    private suspend fun namespaceRecords(owner: String): List<String> {
        val root = "pubky://$owner/${PubkyPaths.APP_NAMESPACE}/"
        val roots = listOf(
            root,
            PubkyPaths.subscriptionsRoot(owner),
            PubkyPaths.decksList(owner),
            "${root}srs/",
            "${root}tags/",
        )
        return roots
            .flatMap { pubky.listAllEntriesOrEmpty(it) }
            .filterNot { it.endsWith("/") }
            .distinct()
    }

    /**
     * Delete [path], treating "already gone" as success — a sweep names records a previous,
     * interrupted sweep already removed. Unlike `DeckRepositoryImpl.deleteRecordLocked` this never
     * rethrows: there is no manifest here whose ordering a partial failure could corrupt, and one
     * stubborn record must not strand the rest of the account.
     */
    private suspend fun deleteRecord(path: String): Boolean =
        pubky.deleteWithSessionRetry(path, session, revalidator).fold(
            onSuccess = { true },
            onFailure = { err ->
                if (err.isNotFound()) {
                    Log.d(TAG, "$path already gone")
                    true
                } else {
                    Log.e(TAG, "$path FAILED — ${err.message}", err)
                    false
                }
            },
        )

    /**
     * Remove the posts Loopky published announcing this user's decks.
     *
     * Matched on the embed URI being under **this user's own** deck root, never on the post looking
     * Loopky-ish: `posts/` is the user's ordinary pubky.app feed, shared with every other app, and
     * an over-broad match deletes writing that has nothing to do with Loopky. A post that cannot be
     * fetched or parsed is left where it is.
     */
    private suspend fun deleteAnnouncementPosts(owner: String) {
        val ownDeckRoot = PubkyPaths.decksList(owner)
        pubky.listAllEntriesOrEmpty(PubkyPaths.postsRoot(owner))
            .mapConcurrently { path ->
                val embedUri = runSuspendCatching {
                    loopkyJson.decodeFromString<PostDto>(pubky.get(path).getOrThrow()).embed?.uri
                }.getOrNull()
                if (embedUri?.startsWith(ownDeckRoot) == true) {
                    Log.d(TAG, "removing announcement $path")
                    deleteRecord(path)
                }
            }
    }

    /**
     * Device-local state that outlives sign-out and would otherwise greet a fresh account with the
     * last one's numbers. The signup token is deliberately not here.
     */
    private suspend fun wipeLocalState(owner: String) {
        runSuspendCatching {
            pendingReviews.save(emptyList())
            // Zeroed under [owner] rather than blindly: the counters are account-scoped now, and
            // an erase that stamped them with nobody would leave a record the next account reads
            // as its own.
            studyProgress.save(owner, DailyStudyProgress(dayIndex = 0, newCards = 0, reviews = 0))
            preferences.setCachedStudySettings("")
            unsplashKeyStore.clear()
            // Cleared, not emptied: every deck title, description and tag of the deleted account
            // is in plaintext preferences, and writing an empty snapshot would still leave a record
            // naming the pubky behind. It is keyed by pubky besides, so restoring this key from its
            // phrase would otherwise paint a library of decks that no longer exist.
            deckCache.clear()
        }.onFailure { Log.e(TAG, "local wipe FAILED — ${it.message}", it) }
    }

    private companion object {
        const val TAG = "Loopky/AccountEraser"
    }
}

/**
 * Counts records off against a total and reports them, safely from concurrent callers. A plain
 * `var done` incremented inside `mapConcurrently` is a data race; the worst case is only a progress
 * number that skips, but the fix is three lines.
 */
private class SweepProgress(private val total: Int, private val report: (Int, Int) -> Unit) {
    private val lock = Mutex()
    private var done = 0

    suspend fun step() {
        val current = lock.withLock { ++done }
        report(current, total)
    }
}
