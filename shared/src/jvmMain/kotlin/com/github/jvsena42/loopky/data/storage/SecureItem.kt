package com.github.jvsena42.loopky.data.storage

/** What one lookup can say. [Missing] is an answer; [Failed] is the absence of one. */
internal sealed interface SecureItemRead {
    data class Found(val value: String) : SecureItemRead
    data object Missing : SecureItemRead
    data class Failed(val message: String) : SecureItemRead
}

/**
 * One named secret, held by whatever this host has for the purpose.
 *
 * The contract is deliberately small and host-agnostic, because [SecureItemSessionStore] is written
 * against *it* rather than against any one platform: the file-first ordering, the migration and the
 * two-place `clear()` are the same argument whether the item lives in the macOS Keychain or in a
 * DPAPI blob, and they were hard enough to get right once (#213) that a second copy of them would be
 * the actual risk in adding a second host.
 *
 * Three things an implementation owes its caller.
 *
 * **A failure is a `Result`, never a throw.** Every caller here treats these as total —
 * [SecureItemSessionStore.save]'s fall back to the file most of all — so a guard that threw would
 * take the fallback with it and turn "this host cannot hold the secret" into "you cannot sign in".
 *
 * **[exists] must not move the secret to answer.** Two callers only ever wanted presence:
 * `location`, which asks whether the store is answering at all, and `clear()`'s gate, which asks
 * whether there is anything to fail about. A store that exists partly so the credential is not
 * sitting where it need not be should not extract it to answer either of them. Null is a third
 * answer — "will not say" — and is not a false.
 *
 * **An implementation may constrain the value**, and must refuse rather than mangle one it cannot
 * carry: [SecurityCliKeychain] needs a token that survives `security -i`'s line splitting unquoted.
 * The store hands over Base64 for that reason, so the constraint is satisfied by construction — the
 * check stays anyway, to keep it a checked fact rather than a comment.
 */
internal interface SecureItem {

    /** Where this item lives, in a form a client can print — see [SecureSessionStore.location]. */
    val location: String

    fun read(): SecureItemRead

    /** Whether the item is there, **without moving the secret**. Null when the host will not say. */
    fun exists(): Boolean?

    fun write(value: String): Result<Unit>

    /**
     * Remove the item, reporting whether it is *gone* rather than whether a call was made.
     *
     * A `Result` because sign-out promises the credential is destroyed, and the local half of that
     * promise used to be the one nobody could observe: a store that refused a delete was logged and
     * swallowed, so `loopky logout` printed "Signed out." over a live bearer token. An item that was
     * already absent is a success — this promises the item is gone, not that this call removed it.
     */
    fun delete(): Result<Unit>
}
