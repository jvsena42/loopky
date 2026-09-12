package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64

/**
 * A session in a [SecureItem], with the 0600 file underneath it.
 *
 * Written against the interface rather than against any one host, because the ordering below was
 * expensive to get right and a second copy of it would be the real risk in adding a second store.
 * Everything here is a statement about *two places holding a credential*, not about the Keychain.
 *
 * **The file is read first, and that one ordering is what makes the rest safe.** It is empty
 * whenever the item holds the session, because a successful write clears it — so on the happy
 * path this costs one lookup in an already-loaded map and changes nothing. When it is *not* empty,
 * it is not empty for exactly one reason: an item write failed and this is the newer credential.
 * Preferring it is therefore not a tie-break, it is the correct answer.
 *
 * Reading the item first is the arrangement that cannot be made safe. A second `login` whose
 * write fails leaves the previous account in the item and the new one in the file, and the
 * reader picks the old one — silently, with `session_live` true, because that session really is
 * live. Deleting the stale item before falling back fixes the read but not the host: `write` and
 * `delete` fail together for almost every reason either fails, so on a locked or absent store —
 * over SSH, under `launchd`, on a CI runner — the delete fails too and sign-in fails outright, on
 * the exact host the fallback exists for.
 *
 * So the invariant is stated as what a reader can rely on rather than as a count: **when both hold
 * something, the file is the newer one and wins.** The duplicate is temporary either way, since
 * every [load] tries to migrate the file copy up and clears the file when it lands.
 *
 * The file is thus reached three ways — the fallback for a store that will not answer, the
 * migration source for installs predating #213, and the second thing [clear] empties.
 */
internal class SecureItemSessionStore(
    private val item: SecureItem,
    private val fallback: SecureSessionStore,
) : SecureSessionStore {

    /**
     * Probed rather than asserted, because this is read by the one command someone runs *when the
     * session has gone missing* — answering "the Keychain" on a Mac whose Keychain is not
     * answering would point them at the wrong place. [SecureItem.exists] rather than
     * [SecureItem.read], so the probe does not pull the secret through a pipe to answer a question
     * about presence.
     *
     * **Two costs a caller other than `:cli` has to know about, because the signature hides
     * both.** An implementation may run a subprocess, so this can block the calling thread for its
     * whole bound on a wedged host — every other member of [SecureSessionStore] is `suspend` and
     * hops to `Dispatchers.IO`, and this one is a `val` a SwiftUI or Compose screen can touch on
     * the main thread. And `by lazy` memoises for the process, so a store that starts answering
     * mid-session keeps reporting that it is not. Both are free for a process that runs one
     * command and exits, which is the only thing reading it today.
     */
    override val location: String by lazy {
        when (item.exists()) {
            // Will not say. Naming the store that cannot be read would point somebody at the one
            // place they cannot check, so this names both and which is which.
            null -> "${fallback.location} — ${item.location} is not answering"
            // **Answering, and holding nothing — so the credential is in the file.** [save] falls
            // back there whenever the item refuses a write, and reporting the item in that state is
            // a false claim about where a credential lives, on the field `--json` offers as its
            // verification channel. It is also right when nobody is signed in at all.
            //
            // This branch used to be folded into `true`, which was latent on macOS — an empty but
            // healthy Keychain after a failed write reports the same — and became *deterministic*
            // with the DPAPI row, whose `exists()` is a file check and can never answer null (#301).
            false -> fallback.location
            true -> item.location
        }
    }

    /**
     * A failed write is a fallback and never a refusal.
     *
     * Nothing is deleted from the item here and nothing throws: whatever it still holds is
     * older than what is going into the file, and [load] reads the file first, so a stale value
     * cannot win. It is cleaned up by the next [load] that finds the store answering again,
     * which overwrites it with the newer session on its way past.
     */
    override suspend fun save(session: Session) = withContext(Dispatchers.IO) {
        item.write(encode(session)).fold(
            // Re-established rather than assumed. `JsonFileStore.persist` rethrows, so this clear
            // can fail on a full or read-only disk — and it would leave the item holding the
            // *new* session and the file an *older* one, which is the single arrangement the
            // file-first ordering assumes cannot exist. It fails loudly at first, but not
            // permanently: once the disk recovers, `storedInFile()` migrates the older credential
            // back over the newer one and nothing is left to notice. A `save` that also fails
            // throws, which is the honest outcome.
            onSuccess = {
                runSuspendCatching { fallback.clear() }.getOrElse { fallback.save(session) }
            },
            onFailure = {
                Log.w(TAG, "secure item write failed, keeping the session in ${fallback.location}", it)
                fallback.save(session)
            },
        )
    }

    override suspend fun load(): Session? = withContext(Dispatchers.IO) {
        storedInFile() ?: fromItem()
    }

    /**
     * Empties the file first, then reports whether the item is gone.
     *
     * Sign-out's remote half already reports its own failure all the way up
     * (`SignOutOutcome.revokedRemotely`) so nobody is told "signed out" while the token lives. The
     * local half — the half this method actually promises — used to report nothing at all, so a
     * store that refused a delete produced "Signed out." over an item still sitting in it.
     *
     * A refused delete is only a failure if there is something to fail about: a store that
     * answers [SecureItemRead.Missing] holds nothing, whatever it thinks of the delete. That check
     * is what stops a clean sign-out on a file-only host from reporting a missing credential as a
     * surviving one.
     */
    override suspend fun clear() = withContext(Dispatchers.IO) {
        fallback.clear()
        item.delete().getOrElse { failure ->
            if (item.exists() != false) throw failure
        }
    }

    /**
     * The file copy, migrated up on the way past.
     *
     * The migration is the same call for both of its jobs: it moves a pre-#213 session into the
     * item, and it overwrites whatever stale value a failed write left behind. Best-effort —
     * on a store that is still not answering the session is simply served from the file again.
     */
    private suspend fun storedInFile(): Session? {
        val session = fallback.load() ?: return null
        item.write(encode(session))
            .onSuccess { fallback.clear() }
            .onFailure { Log.w(TAG, "could not move the stored session into the secure item", it) }
        return session
    }

    private fun fromItem(): Session? = when (val read = item.read()) {
        is SecureItemRead.Found -> decode(read.value)
        SecureItemRead.Missing -> null
        is SecureItemRead.Failed -> {
            Log.w(TAG, "secure item read failed (${read.message}) and ${fallback.location} is empty")
            null
        }
    }

    /**
     * Base64 over the same JSON every other store writes, so the value is one `argv`-safe token —
     * see [SecurityCliKeychain] for why that is a requirement of its write path rather than taste.
     * Every implementation gets it, so none has to ask what it is being handed.
     */
    private fun encode(session: Session): String {
        val json = sessionStoreJson.encodeToString(StoredSession.fromDomain(session))
        return Base64.getEncoder().encodeToString(json.toByteArray())
    }

    private fun decode(value: String): Session? = runCatching {
        val json = String(Base64.getDecoder().decode(value))
        sessionStoreJson.decodeFromString<StoredSession>(json).toDomain()
    }.getOrElse {
        // Same posture as the file store's unreadable-JSON path: the credential is gone either
        // way, and the choice is between starting signed out and not starting.
        Log.w(TAG, "the stored item is not a session; treating it as absent", it)
        null
    }

    private companion object {
        const val TAG = "Loopky/SecureItemSession"
    }
}
