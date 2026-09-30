package com.github.jvsena42.loopky.domain.model

import kotlin.jvm.JvmInline

data class PubkyIdentity(
    val pubky: String,
    val displayName: String?,
    val avatarUrl: String?,
    val bio: String?,
)

/**
 * Letter shown when there is no avatar picture. Display name first, pubky as the fallback — the
 * same rule everywhere an avatar placeholder is drawn, so one person never shows two letters.
 */
val PubkyIdentity.avatarInitial: Char
    get() = (displayName?.trim()?.firstOrNull() ?: pubky.firstOrNull())?.uppercaseChar() ?: '?'

/**
 * [avatarUrl] as something an image loader can actually fetch, or null when there is nothing to
 * show.
 *
 * A pubky.app profile stores its picture as a `pubky://` URI pointing at a *file record* —
 * `pubky://{user}/pub/pubky.app/files/{fileId}` — not at an image. Handing that straight to Coil
 * (or `AsyncImage` on iOS) fails silently, which is why avatars never appeared anywhere even for
 * accounts that had set one. The indexer already serves the decoded blob, so the fix is a URL
 * translation rather than fetching and decoding bytes ourselves.
 *
 * [avatarUrl] itself stays the canonical `pubky://` value: it is written straight back out when
 * the profile is saved, and persisting a resolved indexer URL there would corrupt the record.
 *
 * Anything that is neither an http(s) URL nor a recognised file URI returns null, so the avatar
 * falls back to the initial instead of a blank slot.
 */
fun PubkyIdentity.avatarDisplayUrl(indexerBaseUrl: String): String? {
    val raw = avatarUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (raw.startsWith("http://") || raw.startsWith("https://")) return raw

    if (!raw.startsWith(PUBKY_SCHEME)) return null

    val path = raw.removePrefix(PUBKY_SCHEME)
    val user = path.substringBefore('/', missingDelimiterValue = "")
    val fileId = path.substringAfter(PUBKY_APP_FILES, missingDelimiterValue = "").trim('/')
    if (user.isEmpty() || fileId.isEmpty() || '/' in fileId) return null

    return "${indexerBaseUrl.trimEnd('/')}/static/files/$user/$fileId/main"
}

private const val PUBKY_SCHEME = "pubky://"
private const val PUBKY_APP_FILES = "/pub/pubky.app/files/"

data class Session(
    val identity: PubkyIdentity,
    val sessionSecret: String,
    val capabilities: List<Capability>,
    val homeserver: String,
) {
    /**
     * Whether this session was granted write access to the pubky.app namespace.
     *
     * True for the two apps, which ask for `DEFAULT_CAPABILITIES`; false for the headless client,
     * which asks for `/pub/loopky/:rw` and nothing else (#54). The point of asking is that a
     * pubky.app write from a session without the capability is not a *failure worth reporting* —
     * it is a request that should never have been made, and making it costs a round trip and
     * writes a warning about something working exactly as designed.
     *
     * Only ever a reason to **skip** an optional write, never to substitute one. A caller that
     * genuinely needs the namespace should fail rather than silently do something else.
     */
    val canWritePubkyApp: Boolean
        get() = capabilities.any { it.grantsWriteTo(PUBKY_APP_NAMESPACE) }
}

/**
 * One capability as the homeserver states it: a path prefix and the letters granted on it,
 * `"/pub/loopky/:rw"`.
 */
@JvmInline
value class Capability(val value: String) {
    /**
     * True when this grants writes at or above [path].
     *
     * "At or above" because a grant on `/pub/` covers `/pub/pubky.app/`, and comparing for
     * equality would read a broader grant as a narrower one. The letters are checked for `w`
     * specifically: a read-only `:r` on the same prefix is not permission to write.
     *
     * **This is a hint, never an authorisation check.** It is a plain string prefix, so a
     * capability ending mid-segment (`/pub/pubky.ap`) would answer true for `/pub/pubky.app/`.
     * That costs nothing where it is used — one optional write attempted instead of skipped, which
     * is what happened before this existed — and the homeserver is what actually enforces the
     * scope. Do not promote it to a gate on anything that matters.
     */
    fun grantsWriteTo(path: String): Boolean {
        val prefix = value.substringBeforeLast(':', missingDelimiterValue = value)
        val actions = value.substringAfterLast(':', missingDelimiterValue = "")
        return 'w' in actions && path.startsWith(prefix)
    }
}

/** The namespace pubky.app's own records live under — profiles, posts, follows and their tags. */
private const val PUBKY_APP_NAMESPACE = "/pub/pubky.app/"
